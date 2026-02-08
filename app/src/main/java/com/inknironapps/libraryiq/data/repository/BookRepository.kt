package com.inknironapps.libraryiq.data.repository

import android.util.Log
import com.inknironapps.libraryiq.BuildConfig
import com.inknironapps.libraryiq.data.local.dao.BookDao
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.BookWithCollections
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.data.remote.BookApiService
import com.inknironapps.libraryiq.data.remote.FirestoreSync
import com.inknironapps.libraryiq.data.remote.GoogleBookItem
import com.inknironapps.libraryiq.data.remote.HardcoverApiService
import com.inknironapps.libraryiq.data.remote.HardcoverEdition
import com.inknironapps.libraryiq.data.remote.OpenLibraryApiService
import com.inknironapps.libraryiq.data.remote.OpenLibraryEdition
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val bookApiService: BookApiService,
    private val openLibraryApiService: OpenLibraryApiService,
    private val hardcoverApiService: HardcoverApiService,
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
     * Looks up a book by ISBN using multiple sources and strategies:
     * 1. Local database
     * 2. Google Books (isbn: prefix)
     * 3. Google Books (general search - catches books not formally indexed by ISBN)
     * 4. Google Books (ISBN-10 variant if ISBN-13 was scanned)
     * 5. Open Library (direct ISBN endpoint)
     * 6. Open Library (search endpoint)
     * 7. Hardcover API (if token configured)
     * Merges results for the most complete metadata.
     */
    suspend fun lookupByIsbn(isbn: String): Book? {
        Log.d(TAG, "lookupByIsbn: $isbn")

        // Check local database first
        val existingBook = bookDao.getBookByIsbn(isbn)
        if (existingBook != null) {
            Log.d(TAG, "Found in local database: ${existingBook.title}")
            return existingBook
        }

        val isbn10 = if (isbn.length == 13 && isbn.startsWith("978")) {
            convertIsbn13ToIsbn10(isbn)
        } else null
        if (isbn10 != null) Log.d(TAG, "ISBN-10 variant: $isbn10")

        // 1. Google Books strict ISBN search
        val googleBook = tryGoogleBooksIsbn(isbn)

        // 2. Google Books general search (ISBN as plain text, catches non-indexed books)
        val googleGeneralBook = if (googleBook == null) {
            tryGoogleBooksGeneral(isbn)
        } else null

        // 3. Google Books ISBN-10 variant (some databases only index ISBN-10)
        val googleIsbn10Book = if (googleBook == null && googleGeneralBook == null && isbn10 != null) {
            tryGoogleBooksIsbn(isbn10)?.copy(isbn = isbn)
        } else null

        // 4. Open Library direct + search fallback
        val openLibraryBook = tryOpenLibrary(isbn)

        // 5. Hardcover
        val hardcoverBook = tryHardcover(isbn)

        // Collect all results and merge
        val sources = listOfNotNull(
            googleBook, googleGeneralBook, googleIsbn10Book, openLibraryBook, hardcoverBook
        )
        Log.d(TAG, "Total sources found: ${sources.size}")

        if (sources.isNotEmpty()) {
            val merged = sources.drop(1).fold(sources.first()) { acc, book ->
                mergeBooks(acc, book)
            }
            Log.d(TAG, "Final result: ${merged.title} by ${merged.author}")
            return merged
        }

        Log.d(TAG, "No sources found for ISBN: $isbn")
        return null
    }

    private suspend fun tryGoogleBooksIsbn(isbn: String): Book? {
        return try {
            val response = bookApiService.searchByIsbn("isbn:$isbn")
            response.items?.firstOrNull()?.let { googleBookToBook(it, isbn) }.also {
                Log.d(TAG, "Google Books (isbn:$isbn): ${it?.title ?: "not found"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Books (isbn:) error: ${e.message}")
            null
        }
    }

    private suspend fun tryGoogleBooksGeneral(isbn: String): Book? {
        return try {
            val response = bookApiService.searchByIsbn(isbn)
            response.items?.firstOrNull()?.let { googleBookToBook(it, isbn) }.also {
                Log.d(TAG, "Google Books (general): ${it?.title ?: "not found"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google Books (general) error: ${e.message}")
            null
        }
    }

    private suspend fun tryOpenLibrary(isbn: String): Book? {
        // Direct ISBN endpoint
        val direct = try {
            val edition = openLibraryApiService.getByIsbn(isbn)
            openLibraryEditionToBook(edition, isbn).also {
                Log.d(TAG, "Open Library (direct): ${it.title}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Open Library (direct) error: ${e.message}")
            null
        }

        if (direct != null) return direct

        // Search endpoint fallback
        return try {
            val searchResponse = openLibraryApiService.search(isbn)
            searchResponse.docs?.firstOrNull()?.let { doc ->
                Book(
                    title = doc.title ?: "Unknown Title",
                    author = doc.authorNames?.joinToString(", ") ?: "Unknown Author",
                    isbn = isbn,
                    coverUrl = doc.coverId?.let { OpenLibraryApiService.coverUrl(it, "L") },
                    pageCount = doc.pageCount,
                    publisher = doc.publishers?.firstOrNull(),
                    publishedDate = doc.publishDates?.firstOrNull(),
                    subjects = doc.subjects?.take(10)?.joinToString(", ")
                )
            }.also {
                Log.d(TAG, "Open Library (search): ${it?.title ?: "not found"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Open Library (search) error: ${e.message}")
            null
        }
    }

    private suspend fun tryHardcover(isbn: String): Book? {
        if (BuildConfig.HARDCOVER_API_TOKEN.isEmpty()) {
            Log.d(TAG, "Hardcover: skipped (no API token)")
            return null
        }
        return try {
            val response = hardcoverApiService.query(
                HardcoverApiService.buildIsbnQuery(isbn)
            )
            response.data?.editions?.firstOrNull()?.let {
                hardcoverEditionToBook(it, isbn)
            }.also {
                Log.d(TAG, "Hardcover: ${it?.title ?: "not found"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Hardcover error: ${e.message}")
            null
        }
    }

    /**
     * Converts an ISBN-13 (starting with 978) to ISBN-10.
     * Some databases only index ISBN-10.
     */
    private fun convertIsbn13ToIsbn10(isbn13: String): String? {
        if (isbn13.length != 13 || !isbn13.startsWith("978")) return null
        val body = isbn13.substring(3, 12)
        var sum = 0
        for (i in body.indices) {
            sum += (10 - i) * (body[i] - '0')
        }
        val check = (11 - (sum % 11)) % 11
        val checkChar = if (check == 10) "X" else check.toString()
        return body + checkChar
    }

    companion object {
        private const val TAG = "BookRepository"
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

        val workData = edition.works?.firstOrNull()?.key?.let { workKey ->
            try {
                openLibraryApiService.getWork(workKey.trimStart('/'))
            } catch (_: Exception) {
                null
            }
        }

        val description = extractDescription(edition.description)
            ?: workData?.let { extractDescription(it.description) }

        val coverUrl = edition.covers?.firstOrNull()?.let {
            OpenLibraryApiService.coverUrl(it, "L")
        }

        val subjects = edition.subjects
            ?: workData?.subjects

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

    private fun hardcoverEditionToBook(
        edition: HardcoverEdition,
        scannedIsbn: String? = null
    ): Book {
        val authorNames = edition.book?.contributions
            ?.mapNotNull { it.author?.name }
            ?: emptyList()

        return Book(
            title = edition.book?.title ?: edition.title ?: "Unknown Title",
            author = authorNames.joinToString(", ").ifBlank { "Unknown Author" },
            isbn = scannedIsbn ?: edition.isbn13,
            isbn10 = edition.isbn10,
            description = edition.book?.description,
            coverUrl = edition.image?.url,
            pageCount = edition.pages,
            publisher = edition.publisher?.name,
            publishedDate = edition.releaseDate
        )
    }

    private fun extractDescription(desc: Any?): String? {
        return when (desc) {
            is String -> desc
            is Map<*, *> -> desc["value"] as? String
            else -> null
        }
    }

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
