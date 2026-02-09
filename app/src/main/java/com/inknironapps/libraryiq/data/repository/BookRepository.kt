package com.inknironapps.libraryiq.data.repository

import com.inknironapps.libraryiq.BuildConfig
import com.inknironapps.libraryiq.data.local.dao.BookDao
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.BookWithCollections
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.data.remote.AmazonMetadataScraper
import com.inknironapps.libraryiq.data.remote.BookApiService
import com.inknironapps.libraryiq.data.remote.FirestoreSync
import com.inknironapps.libraryiq.data.remote.GoogleBookItem
import com.inknironapps.libraryiq.data.remote.HardcoverApiService
import com.inknironapps.libraryiq.data.remote.HardcoverEdition
import com.inknironapps.libraryiq.data.remote.OpenLibraryApiService
import com.inknironapps.libraryiq.data.remote.OpenLibraryEdition
import com.inknironapps.libraryiq.util.DebugLog
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class LookupResult(
    val book: Book?,
    val diagnostics: String
)

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val bookApiService: BookApiService,
    private val openLibraryApiService: OpenLibraryApiService,
    private val hardcoverApiService: HardcoverApiService,
    private val amazonScraper: AmazonMetadataScraper,
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
     * 8. Amazon product page scraping (like Calibre)
     * Merges results for the most complete metadata.
     */
    suspend fun lookupByIsbn(isbn: String): LookupResult = doLookup(isbn, skipLocal = false)

    /**
     * Same as lookupByIsbn but skips the local database check.
     * Used when refreshing metadata for an already-saved book.
     */
    suspend fun lookupByIsbnSkipLocal(isbn: String): LookupResult = doLookup(isbn, skipLocal = true)

    private suspend fun doLookup(isbn: String, skipLocal: Boolean): LookupResult {
        DebugLog.d(TAG, "lookupByIsbn: $isbn (skipLocal=$skipLocal)")
        val diag = mutableListOf<String>()

        // Check local database first (unless refreshing)
        if (!skipLocal) {
            val existingBook = bookDao.getBookByIsbn(isbn)
            if (existingBook != null) {
                DebugLog.d(TAG, "Found in local database: ${existingBook.title}")
                return LookupResult(existingBook, "Found in local database")
            }
        }

        val isbn10 = if (isbn.length == 13 && isbn.startsWith("978")) {
            convertIsbn13ToIsbn10(isbn)
        } else null
        if (isbn10 != null) DebugLog.d(TAG, "ISBN-10 variant: $isbn10")

        // 1. Google Books strict ISBN search
        val googleBook = tryGoogleBooksIsbn(isbn)
        diag.add(if (googleBook != null) "GB(isbn): ${googleBook.title}" else "GB(isbn): miss")

        // 2. Google Books general search (ISBN as plain text)
        val googleGeneralBook = if (googleBook == null) {
            tryGoogleBooksGeneral(isbn).also {
                diag.add(if (it != null) "GB(general): ${it.title}" else "GB(general): miss")
            }
        } else null

        // 3. Google Books ISBN-10 variant
        val googleIsbn10Book = if (googleBook == null && googleGeneralBook == null && isbn10 != null) {
            tryGoogleBooksIsbn(isbn10)?.copy(isbn = isbn).also {
                diag.add(if (it != null) "GB(isbn10): ${it.title}" else "GB(isbn10): miss")
            }
        } else null

        // 4. Open Library direct + search fallback
        val openLibraryBook = tryOpenLibrary(isbn)
        diag.add(if (openLibraryBook != null) "OL: ${openLibraryBook.title}" else "OL: miss")

        // 5. Hardcover
        val hardcoverBook = tryHardcover(isbn)
        diag.add(if (hardcoverBook != null) "HC: ${hardcoverBook.title}" else
            if (BuildConfig.HARDCOVER_API_TOKEN.isEmpty()) "HC: no token" else "HC: miss")

        // 6. Amazon scraping (last resort, like Calibre)
        val amazonBook = if (listOfNotNull(googleBook, googleGeneralBook, googleIsbn10Book, openLibraryBook, hardcoverBook).isEmpty()) {
            tryAmazon(isbn).also {
                diag.add(if (it != null) "AMZ: ${it.title}" else "AMZ: miss")
            }
        } else null

        // Collect all ISBN-based results and merge
        val isbnSources = listOfNotNull(
            googleBook, googleGeneralBook, googleIsbn10Book, openLibraryBook, hardcoverBook, amazonBook
        )

        if (isbnSources.isEmpty()) {
            val diagStr = diag.joinToString(" | ")
            DebugLog.d(TAG, "No sources found for ISBN: $isbn")
            return LookupResult(null, diagStr)
        }

        var merged = isbnSources.drop(1).fold(isbnSources.first()) { acc, book ->
            mergeBooks(acc, book)
        }

        // 7. Title-based enrichment: if metadata is incomplete, search by title+author
        if (needsEnrichment(merged)) {
            DebugLog.d(TAG, "Metadata incomplete, enriching by title: '${merged.title}' by '${merged.author}'")
            val titleSources = searchByTitle(merged.title, merged.author, isbn)
            for (enrichment in titleSources) {
                merged = mergeBooks(merged, enrichment)
            }
            if (titleSources.isNotEmpty()) {
                diag.add("title-enrich: ${titleSources.size} hit(s)")
            }
        }

        val diagStr = diag.joinToString(" | ")
        DebugLog.d(TAG, "Final: ${merged.title} by ${merged.author} " +
            "[cover=${merged.coverUrl != null}, desc=${merged.description != null}, " +
            "pages=${merged.pageCount}, pub=${merged.publisher}]")
        return LookupResult(merged, diagStr)
    }

    var lastErrors = mutableListOf<String>()
        private set

    /**
     * Checks if a book result is missing important metadata that
     * could be filled by searching other APIs by title.
     */
    private fun needsEnrichment(book: Book): Boolean {
        val missingFields = listOf(
            book.coverUrl == null,
            book.description == null,
            book.pageCount == null,
            book.publisher == null,
            book.author == "Unknown Author",
            book.series == null,
            book.seriesNumber == null
        )
        return missingFields.count { it } >= 2
    }

    /**
     * Searches Google Books, Open Library, and Hardcover by title+author
     * to find additional metadata (especially series info) for an
     * already-identified book. Uses the full title to match editions.
     */
    private suspend fun searchByTitle(title: String, author: String, isbn: String): List<Book> {
        val results = mutableListOf<Book>()

        // Strip common edition suffixes for a cleaner base title search
        val baseTitle = title
            .replace(Regex("""\s*:\s*(Deluxe|Special|Collector['']?s?|Limited|Anniversary)\s+Edition.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(Hardcover|Paperback|Mass Market).*""", RegexOption.IGNORE_CASE), "")
            .trim()

        val searchQuery = if (author != "Unknown Author") {
            "$baseTitle $author"
        } else {
            baseTitle
        }

        // Google Books title search (use full title first for edition match)
        try {
            val query = if (author != "Unknown Author") {
                "intitle:$baseTitle+inauthor:$author"
            } else {
                "intitle:$baseTitle"
            }
            val response = bookApiService.searchByIsbn(query)
            val bestMatch = response.items?.firstOrNull()
            if (bestMatch != null) {
                val book = googleBookToBook(bestMatch, isbn)
                DebugLog.d(TAG, "GB(title): '${book.title}' by ${book.author}")
                results.add(book)
            } else {
                DebugLog.d(TAG, "GB(title): miss for '$baseTitle'")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "GB(title) error: ${e.message}")
        }

        // Open Library title search
        try {
            val searchResponse = openLibraryApiService.search(searchQuery)
            val bestMatch = searchResponse.docs?.firstOrNull()
            if (bestMatch != null) {
                val book = Book(
                    title = bestMatch.title ?: title,
                    author = bestMatch.authorNames?.joinToString(", ") ?: author,
                    isbn = isbn,
                    coverUrl = bestMatch.coverId?.let { OpenLibraryApiService.coverUrl(it, "L") },
                    pageCount = bestMatch.pageCount,
                    publisher = bestMatch.publishers?.firstOrNull(),
                    publishedDate = bestMatch.publishDates?.firstOrNull(),
                    subjects = bestMatch.subjects?.take(10)?.joinToString(", ")
                )
                DebugLog.d(TAG, "OL(title): '${book.title}' by ${book.author}")
                results.add(book)
            } else {
                DebugLog.d(TAG, "OL(title): miss for '$searchQuery'")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "OL(title) error: ${e.message}")
        }

        // Hardcover title search (best source for series data)
        // Uses editions table with book title filter (same table as ISBN search)
        if (BuildConfig.HARDCOVER_API_TOKEN.isNotEmpty()) {
            try {
                val response = hardcoverApiService.query(
                    HardcoverApiService.buildTitleQuery(baseTitle)
                )
                val bestMatch = response.data?.editions?.firstOrNull()
                if (bestMatch != null) {
                    val book = hardcoverEditionToBook(bestMatch, isbn)
                    DebugLog.d(TAG, "HC(title): '${book.title}' by ${book.author}, " +
                        "series=${book.series} #${book.seriesNumber}")
                    results.add(book)
                } else {
                    DebugLog.d(TAG, "HC(title): miss for '$baseTitle'")
                }
            } catch (e: Exception) {
                DebugLog.e(TAG, "HC(title) error: ${e.message}")
            }
        }

        return results
    }

    private suspend fun tryGoogleBooksIsbn(isbn: String): Book? {
        return try {
            val response = bookApiService.searchByIsbn("isbn:$isbn")
            response.items?.firstOrNull()?.let { googleBookToBook(it, isbn) }.also {
                DebugLog.d(TAG, "Google Books (isbn:$isbn): ${it?.title ?: "not found"}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Google Books (isbn:) error: ${e.message}")
            lastErrors.add("GB(isbn): ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryGoogleBooksGeneral(isbn: String): Book? {
        return try {
            val response = bookApiService.searchByIsbn(isbn)
            response.items?.firstOrNull()?.let { googleBookToBook(it, isbn) }.also {
                DebugLog.d(TAG, "Google Books (general): ${it?.title ?: "not found"}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Google Books (general) error: ${e.message}")
            lastErrors.add("GB(gen): ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryOpenLibrary(isbn: String): Book? {
        val direct = try {
            val edition = openLibraryApiService.getByIsbn(isbn)
            openLibraryEditionToBook(edition, isbn).also {
                DebugLog.d(TAG, "Open Library (direct): ${it.title}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Open Library (direct) error: ${e.message}")
            lastErrors.add("OL(direct): ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        if (direct != null) return direct

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
                DebugLog.d(TAG, "Open Library (search): ${it?.title ?: "not found"}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Open Library (search) error: ${e.message}")
            lastErrors.add("OL(search): ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryHardcover(isbn: String): Book? {
        if (BuildConfig.HARDCOVER_API_TOKEN.isEmpty()) {
            DebugLog.d(TAG, "Hardcover: skipped (no API token)")
            return null
        }
        return try {
            val response = hardcoverApiService.query(
                HardcoverApiService.buildIsbnQuery(isbn)
            )
            response.data?.editions?.firstOrNull()?.let {
                hardcoverEditionToBook(it, isbn)
            }.also {
                DebugLog.d(TAG, "Hardcover: ${it?.title ?: "not found"}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Hardcover error: ${e.message}")
            lastErrors.add("HC: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryAmazon(isbn: String): Book? {
        return try {
            // Try product page first (ISBN-10 works as ASIN)
            val isbn10 = if (isbn.length == 13 && isbn.startsWith("978")) {
                convertIsbn13ToIsbn10(isbn)
            } else isbn
            val productBook = isbn10?.let { amazonScraper.lookupByProductPage(it) }
            if (productBook != null) return productBook.copy(isbn = isbn)

            // Fall back to search
            amazonScraper.lookupByIsbn(isbn)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Don't swallow cancellation
        } catch (e: Exception) {
            DebugLog.e(TAG, "Amazon scraper error: ${e.message}")
            lastErrors.add("AMZ: ${e.javaClass.simpleName}: ${e.message}")
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

        val seriesInfo = edition.book?.bookSeries?.firstOrNull()
        val seriesName = seriesInfo?.series?.name
        val seriesNumber = seriesInfo?.position?.let {
            if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString()
        }

        return Book(
            title = edition.book?.title ?: edition.title ?: "Unknown Title",
            author = authorNames.joinToString(", ").ifBlank { "Unknown Author" },
            isbn = scannedIsbn ?: edition.isbn13,
            isbn10 = edition.isbn10,
            description = edition.book?.description,
            coverUrl = edition.image?.url,
            pageCount = edition.pages,
            publisher = edition.publisher?.name,
            publishedDate = edition.releaseDate,
            series = seriesName,
            seriesNumber = seriesNumber
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
