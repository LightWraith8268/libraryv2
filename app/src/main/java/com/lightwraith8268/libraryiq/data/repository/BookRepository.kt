package com.lightwraith8268.libraryiq.data.repository

import com.lightwraith8268.libraryiq.data.local.dao.BookDao
import com.lightwraith8268.libraryiq.data.local.entity.Book
import com.lightwraith8268.libraryiq.data.local.entity.BookWithCollections
import com.lightwraith8268.libraryiq.data.local.entity.ReadingStatus
import com.lightwraith8268.libraryiq.data.remote.BookApiService
import com.lightwraith8268.libraryiq.data.remote.FirestoreSync
import com.lightwraith8268.libraryiq.data.remote.GoogleBookItem
import com.lightwraith8268.libraryiq.data.remote.OpenLibraryApiService
import com.lightwraith8268.libraryiq.data.remote.OpenLibraryEdition
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val bookApiService: BookApiService,
    private val openLibraryApiService: OpenLibraryApiService,
    private val firestoreSync: FirestoreSync
) {
    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooks()

    fun getBooksByStatus(status: ReadingStatus): Flow<List<Book>> =
        bookDao.getBooksByStatus(status)

    fun getBookById(bookId: String): Flow<Book?> = bookDao.getBookById(bookId)

    fun getBookWithCollections(bookId: String): Flow<BookWithCollections?> =
        bookDao.getBookWithCollections(bookId)

    fun searchBooks(query: String): Flow<List<Book>> = bookDao.searchBooks(query)

    fun getBookCount(): Flow<Int> = bookDao.getBookCount()

    suspend fun addBook(book: Book) {
        bookDao.insertBook(book)
        firestoreSync.pushBook(book)
    }

    suspend fun updateBook(book: Book) {
        val updated = book.copy(dateModified = System.currentTimeMillis())
        bookDao.updateBook(updated)
        firestoreSync.pushBook(updated)
    }

    suspend fun deleteBook(book: Book) {
        bookDao.deleteBook(book)
        firestoreSync.deleteBook(book.id)
    }

    /**
     * Looks up a book by ISBN using multiple sources:
     * 1. Local database
     * 2. Google Books API
     * 3. Open Library API
     * Merges results to get the most complete metadata.
     */
    suspend fun lookupByIsbn(isbn: String): Book? {
        // Check local database first
        val existingBook = bookDao.getBookByIsbn(isbn)
        if (existingBook != null) return existingBook

        // Try Google Books first (usually faster)
        val googleBook = try {
            val response = bookApiService.searchByIsbn(BookApiService.buildIsbnQuery(isbn))
            response.items?.firstOrNull()?.let { googleBookToBook(it, isbn) }
        } catch (_: Exception) {
            null
        }

        // Try Open Library for additional/fallback metadata
        val openLibraryBook = try {
            val edition = openLibraryApiService.getByIsbn(isbn)
            openLibraryEditionToBook(edition, isbn)
        } catch (_: Exception) {
            null
        }

        // Merge results: prefer Google Books as primary, fill gaps from Open Library
        return when {
            googleBook != null && openLibraryBook != null -> mergeBooks(googleBook, openLibraryBook)
            googleBook != null -> googleBook
            openLibraryBook != null -> openLibraryBook
            else -> null
        }
    }

    private fun googleBookToBook(item: GoogleBookItem, scannedIsbn: String? = null): Book {
        val info = item.volumeInfo
        val isbn13 = scannedIsbn
            ?: info.industryIdentifiers
                ?.firstOrNull { it.type == "ISBN_13" }?.identifier
        val isbn10 = info.industryIdentifiers
            ?.firstOrNull { it.type == "ISBN_10" }?.identifier

        return Book(
            title = info.title ?: "Unknown Title",
            author = info.authors?.joinToString(", ") ?: "Unknown Author",
            isbn = isbn13 ?: isbn10,
            isbn10 = isbn10,
            description = info.description,
            coverUrl = info.imageLinks?.getBestUrl(),
            pageCount = info.pageCount,
            publisher = info.publisher,
            publishedDate = info.publishedDate
        )
    }

    private suspend fun openLibraryEditionToBook(
        edition: OpenLibraryEdition,
        scannedIsbn: String? = null
    ): Book {
        // Resolve author names from author keys
        val authorNames = edition.authors?.mapNotNull { ref ->
            ref.key?.let { key ->
                try {
                    val author = openLibraryApiService.getAuthor(key.trimStart('/'))
                    author.name ?: author.personalName
                } catch (_: Exception) {
                    null
                }
            }
        } ?: emptyList()

        // Get work-level data (may have better description and subjects)
        val workData = edition.works?.firstOrNull()?.key?.let { workKey ->
            try {
                openLibraryApiService.getWork(workKey.trimStart('/'))
            } catch (_: Exception) {
                null
            }
        }

        // Extract description (can be a String or an object with "value" key)
        val description = extractDescription(edition.description)
            ?: workData?.let { extractDescription(it.description) }

        // Cover URL
        val coverUrl = edition.covers?.firstOrNull()?.let {
            OpenLibraryApiService.coverUrl(it, "L")
        }

        // Subjects from edition or work
        val subjects = edition.subjects
            ?: workData?.subjects

        // Language extraction
        val language = edition.languages?.firstOrNull()?.key
            ?.replace("/languages/", "")
            ?.uppercase()

        return Book(
            title = edition.title ?: "Unknown Title",
            author = authorNames.joinToString(", ").ifBlank { "Unknown Author" },
            isbn = scannedIsbn ?: edition.isbn13?.firstOrNull(),
            isbn10 = edition.isbn10?.firstOrNull(),
            description = description,
            coverUrl = coverUrl,
            pageCount = edition.numberOfPages,
            publisher = edition.publishers?.firstOrNull(),
            publishedDate = edition.publishDate,
            series = edition.series?.firstOrNull(),
            language = language,
            format = edition.physicalFormat,
            subjects = subjects?.take(10)?.joinToString(", ")
        )
    }

    private fun extractDescription(desc: Any?): String? {
        return when (desc) {
            is String -> desc
            is Map<*, *> -> desc["value"] as? String
            else -> null
        }
    }

    /**
     * Merges two Book objects, preferring non-null values from primary,
     * falling back to secondary for missing fields.
     */
    private fun mergeBooks(primary: Book, secondary: Book): Book {
        return primary.copy(
            description = primary.description ?: secondary.description,
            coverUrl = primary.coverUrl ?: secondary.coverUrl,
            pageCount = primary.pageCount ?: secondary.pageCount,
            publisher = primary.publisher ?: secondary.publisher,
            publishedDate = primary.publishedDate ?: secondary.publishedDate,
            isbn10 = primary.isbn10 ?: secondary.isbn10,
            series = primary.series ?: secondary.series,
            seriesNumber = primary.seriesNumber ?: secondary.seriesNumber,
            genre = primary.genre ?: secondary.genre,
            language = primary.language ?: secondary.language,
            format = primary.format ?: secondary.format,
            subjects = primary.subjects ?: secondary.subjects
        )
    }
}
