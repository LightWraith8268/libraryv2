package com.inknironapps.libraryiq.data.repository

import com.inknironapps.libraryiq.BuildConfig
import com.inknironapps.libraryiq.data.local.dao.BookDao
import com.inknironapps.libraryiq.data.local.dao.CollectionDao
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.BookCollectionCrossRef
import com.inknironapps.libraryiq.data.local.entity.BookWithCollections
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.data.remote.AmazonMetadataScraper
import com.inknironapps.libraryiq.data.remote.BookApiService
import com.inknironapps.libraryiq.data.remote.FirestoreSync
import com.inknironapps.libraryiq.data.remote.GoogleBookItem
import com.inknironapps.libraryiq.data.remote.HardcoverApiService
import com.inknironapps.libraryiq.data.remote.HardcoverEdition
import com.inknironapps.libraryiq.data.remote.ITunesApiService
import com.inknironapps.libraryiq.data.remote.OpenLibraryApiService
import com.inknironapps.libraryiq.data.remote.OpenLibraryEdition
import com.inknironapps.libraryiq.util.DebugLog
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

data class LookupResult(
    val book: Book?,
    val diagnostics: String
)

data class CoverOption(
    val source: String,
    val url: String
)

@Singleton
class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val collectionDao: CollectionDao,
    private val bookApiService: BookApiService,
    private val openLibraryApiService: OpenLibraryApiService,
    private val hardcoverApiService: HardcoverApiService,
    private val amazonScraper: AmazonMetadataScraper,
    private val iTunesApiService: ITunesApiService,
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

    /** Update only per-user reading status (doesn't touch shared metadata in Firestore). */
    suspend fun updateReadingStatus(book: Book) {
        val updated = book.copy(dateModified = System.currentTimeMillis())
        bookDao.updateBook(updated)
        firestoreSync.pushUserStatus(updated)

        // Auto-manage "Want to Buy" collection membership
        try {
            val wantToBuy = collectionDao.getCollectionByName("Want to Buy")
            if (wantToBuy != null) {
                val isInCollection = collectionDao.isBookInCollection(book.id, wantToBuy.id)
                if (updated.readingStatus == ReadingStatus.WANT_TO_BUY && !isInCollection) {
                    val crossRef = BookCollectionCrossRef(book.id, wantToBuy.id)
                    collectionDao.addBookToCollection(crossRef)
                    firestoreSync.pushBookCollectionRef(crossRef)
                } else if (updated.readingStatus != ReadingStatus.WANT_TO_BUY && isInCollection) {
                    collectionDao.removeBookFromCollectionById(book.id, wantToBuy.id)
                    firestoreSync.deleteBookCollectionRef(book.id, wantToBuy.id)
                }
            }
        } catch (_: Exception) { }
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

        // 6. Amazon scraping (always try - best source for series, pages, descriptions)
        val amazonBook = tryAmazon(isbn)
        diag.add(if (amazonBook != null) "AMZ: ${amazonBook.title}" else "AMZ: miss")

        // Collect all ISBN-based results and merge.
        // Each field picks the most complete/accurate value across all sources.
        val isbnSources = listOfNotNull(
            amazonBook, hardcoverBook, openLibraryBook, googleBook, googleGeneralBook, googleIsbn10Book
        )

        if (isbnSources.isEmpty()) {
            val diagStr = diag.joinToString(" | ")
            DebugLog.d(TAG, "No sources found for ISBN: $isbn")
            return LookupResult(null, diagStr)
        }

        var merged = isbnSources.drop(1).fold(isbnSources.first()) { acc, book ->
            mergeBooks(acc, book)
        }

        // 7. Title-based enrichment: always search by title+author for additional metadata
        DebugLog.d(TAG, "Enriching by title: '${merged.title}' by '${merged.author}'")
        val titleSources = searchByTitle(merged.title, merged.author, isbn)
        for (enrichment in titleSources) {
            merged = mergeBooks(merged, enrichment)
        }
        if (titleSources.isNotEmpty()) {
            diag.add("title-enrich: ${titleSources.size} hit(s)")
        }

        // Clean up the final title - strip edition/format suffixes and series parentheticals
        merged = merged.copy(title = cleanTitle(merged.title, merged.series))

        // Standardize series name to match existing books in the library
        if (merged.series != null) {
            merged = merged.copy(series = standardizeSeriesName(merged.series!!))
        }

        // 8. Apple Books cover (preferred source - high quality artwork)
        val appleCover = tryAppleBooksCover(merged.title, merged.author)
        if (appleCover != null) {
            merged = merged.copy(coverUrl = appleCover)
            diag.add("Apple: cover")
        } else {
            diag.add("Apple: miss")
        }

        val diagStr = diag.joinToString(" | ")
        DebugLog.d(TAG, "Final: ${merged.title} by ${merged.author} " +
            "[cover=${merged.coverUrl != null}, desc=${merged.description != null}, " +
            "pages=${merged.pageCount}, pub=${merged.publisher}, " +
            "series=${merged.series} #${merged.seriesNumber}]")
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

        // Strip common edition suffixes and series parentheticals for a cleaner search
        val baseTitle = title
            .replace(Regex("""\s*:\s*(Deluxe|Special|Collector['']?s?|Limited|Anniversary)\s+Edition.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(Hardcover|Paperback|Mass Market).*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\([^)]*\)\s*$"""), "") // Strip trailing parentheticals like "(Series Book 1)"
            .trim()

        val searchQuery = if (author != "Unknown Author") {
            "$baseTitle $author"
        } else {
            baseTitle
        }

        // Google Books title search (quote multi-word values for exact matching)
        try {
            val query = if (author != "Unknown Author") {
                "intitle:\"$baseTitle\" inauthor:\"$author\""
            } else {
                "intitle:\"$baseTitle\""
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

    // =====================================================================
    // Cover Picker - fetch covers from all sources
    // =====================================================================

    /**
     * Fetches cover image URLs from all available sources for the cover picker.
     * Queries Google Books, Open Library, Hardcover, Amazon, and iTunes in parallel.
     */
    suspend fun fetchAllCovers(isbn: String?, title: String, author: String): List<CoverOption> {
        val covers = mutableListOf<CoverOption>()

        coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<CoverOption>>>()

            // Open Library - direct ISBN cover URL (no API call needed)
            if (!isbn.isNullOrBlank()) {
                covers.add(CoverOption(
                    "Open Library",
                    "https://covers.openlibrary.org/b/isbn/$isbn-L.jpg"
                ))
            }

            // Google Books
            jobs.add(async { fetchGoogleBooksCovers(isbn, title, author) })

            // Hardcover
            if (BuildConfig.HARDCOVER_API_TOKEN.isNotEmpty()) {
                jobs.add(async { fetchHardcoverCovers(isbn, title) })
            }

            // Amazon (scrapes product page for high-res image)
            if (!isbn.isNullOrBlank()) {
                jobs.add(async { fetchAmazonCovers(isbn) })
            }

            // iTunes (Apple Books covers)
            jobs.add(async { fetchITunesCovers(title, author) })

            for (job in jobs) {
                try {
                    covers.addAll(job.await())
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Cover fetch error: ${e.message}")
                }
            }
        }

        // Remove duplicates by URL and filter empty/blank
        return covers
            .filter { it.url.isNotBlank() && it.url.startsWith("http") }
            .distinctBy { it.url }
    }

    private suspend fun fetchGoogleBooksCovers(isbn: String?, title: String, author: String): List<CoverOption> {
        val results = mutableListOf<CoverOption>()
        try {
            // Try ISBN search first
            if (!isbn.isNullOrBlank()) {
                val response = bookApiService.searchByIsbn("isbn:$isbn")
                response.items?.forEach { item ->
                    item.volumeInfo.imageLinks?.getBestUrl()?.let { url ->
                        results.add(CoverOption("Google Books", url))
                    }
                }
            }
            // Also try title+author search for alternate editions
            if (results.isEmpty()) {
                val query = if (author != "Unknown Author") "intitle:$title+inauthor:$author" else "intitle:$title"
                val response = bookApiService.searchByIsbn(query)
                response.items?.take(3)?.forEach { item ->
                    item.volumeInfo.imageLinks?.getBestUrl()?.let { url ->
                        results.add(CoverOption("Google Books", url))
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Google Books covers error: ${e.message}")
        }
        return results
    }

    private suspend fun fetchHardcoverCovers(isbn: String?, title: String): List<CoverOption> {
        val results = mutableListOf<CoverOption>()
        try {
            // Try ISBN lookup
            if (!isbn.isNullOrBlank()) {
                val response = hardcoverApiService.query(HardcoverApiService.buildIsbnQuery(isbn))
                response.data?.editions?.forEach { edition ->
                    edition.image?.url?.let { url ->
                        results.add(CoverOption("Hardcover", url))
                    }
                }
            }
            // Try title lookup if ISBN didn't work
            if (results.isEmpty()) {
                val response = hardcoverApiService.query(HardcoverApiService.buildTitleQuery(title))
                response.data?.editions?.take(3)?.forEach { edition ->
                    edition.image?.url?.let { url ->
                        results.add(CoverOption("Hardcover", url))
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Hardcover covers error: ${e.message}")
        }
        return results
    }

    private suspend fun fetchAmazonCovers(isbn: String): List<CoverOption> {
        val results = mutableListOf<CoverOption>()
        try {
            val isbn10 = if (isbn.length == 13 && isbn.startsWith("978")) {
                convertIsbn13ToIsbn10(isbn)
            } else isbn

            val book = isbn10?.let { amazonScraper.lookupByProductPage(it) }
                ?: amazonScraper.lookupByIsbn(isbn)

            book?.coverUrl?.let { url ->
                results.add(CoverOption("Amazon", url))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Amazon covers error: ${e.message}")
        }
        return results
    }

    private suspend fun fetchITunesCovers(title: String, author: String): List<CoverOption> {
        val results = mutableListOf<CoverOption>()
        try {
            val searchTerm = if (author != "Unknown Author") "$title $author" else title
            val response = iTunesApiService.searchEbooks(searchTerm)
            response.results?.take(3)?.forEach { result ->
                result.getHighResCoverUrl()?.let { url ->
                    results.add(CoverOption("Apple Books", url))
                }
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "iTunes covers error: ${e.message}")
        }
        return results
    }

    /**
     * Fetches the best Apple Books cover for a book by title+author.
     * Returns the first matching high-res (600x600) cover URL, or null.
     */
    private suspend fun tryAppleBooksCover(title: String, author: String): String? {
        return try {
            val searchTerm = if (author != "Unknown Author") "$title $author" else title
            val response = iTunesApiService.searchEbooks(searchTerm, limit = 1)
            response.results?.firstOrNull()?.getHighResCoverUrl().also {
                DebugLog.d(TAG, "Apple Books cover: ${if (it != null) "found" else "not found"} for '$searchTerm'")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Apple Books cover error: ${e.message}")
            lastErrors.add("Apple: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
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

    /**
     * Strips common edition/format suffixes from titles.
     * "Wildfire: Deluxe Edition Hardcover" → "Wildfire"
     * "Book Title: A Novel" → "Book Title"
     */
    private fun cleanTitle(title: String, seriesName: String? = null): String {
        var cleaned = title
            .replace(Regex("""\s*:\s*(?:Deluxe|Special|Collector['']?s?|Limited|Anniversary)\s+Edition.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*:\s*A\s+Novel.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(\s*(?:A\s+)?\w+\s+Novel\s*\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:Hardcover|Paperback|Mass\s+Market).*$""", RegexOption.IGNORE_CASE), "")
        // Strip series parenthetical if we know the series name
        if (seriesName != null) {
            cleaned = cleaned.replace(
                Regex("""\s*\(\s*${Regex.escape(seriesName)}(?:\s*(?:Book|Vol\.?|#)\s*\d+)?\s*\)""", RegexOption.IGNORE_CASE), ""
            )
        }
        // Strip anything from the first '(' onward — removes edition, series, format info in parens
        cleaned = cleaned.replace(Regex("""\s*\(.*$"""), "")
        return cleaned.trim()
    }

    /**
     * Normalizes a series name for comparison by stripping common suffixes
     * like "Series", "Trilogy", "Saga", "Duology", "Quartet", "Cycle".
     */
    private fun normalizeSeriesForComparison(name: String): String {
        return name.trim()
            .replace(Regex("""\s+(?:Series|Trilogy|Saga|Duology|Quartet|Cycle)\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * Checks existing series names in the library and matches the incoming
     * series name to an established one if they normalize to the same value.
     * E.g. "The Maple Hill" matches "The Maple Hill Series" → uses "The Maple Hill Series".
     */
    private suspend fun standardizeSeriesName(incomingSeries: String): String {
        val normalizedIncoming = normalizeSeriesForComparison(incomingSeries)
        val existingNames = bookDao.getAllSeriesNames()

        for (existing in existingNames) {
            val normalizedExisting = normalizeSeriesForComparison(existing)
            if (normalizedIncoming.equals(normalizedExisting, ignoreCase = true)) {
                return existing // Use the established name from the library
            }
        }

        return incomingSeries // No match — use as-is
    }

    /**
     * Merges two Book records, picking the most complete value per field.
     * - Title/author: prefer non-"Unknown", then longer (more specific; cleanTitle strips cruft)
     * - Description/subjects: prefer longer (more detail)
     * - Page count: prefer higher non-zero value
     * - Series: prefer the source that has both name + number
     * - IDs: first non-null wins (unique identifiers)
     */
    private fun mergeBooks(primary: Book, secondary: Book): Book {
        val pPages = primary.pageCount?.takeIf { it > 0 }
        val sPages = secondary.pageCount?.takeIf { it > 0 }

        // For series, prefer whichever source has both name and number
        val (bestSeries, bestSeriesNum) = pickBestSeries(
            primary.series, primary.seriesNumber,
            secondary.series, secondary.seriesNumber
        )

        return primary.copy(
            title = pickBestTitle(primary.title, secondary.title),
            author = pickBestAuthor(primary.author, secondary.author),
            description = longerOf(primary.description, secondary.description),
            coverUrl = primary.coverUrl ?: secondary.coverUrl,
            pageCount = maxOfNullable(pPages, sPages),
            publisher = longerOf(primary.publisher, secondary.publisher),
            publishedDate = moreSpecificDate(primary.publishedDate, secondary.publishedDate),
            isbn10 = primary.isbn10 ?: secondary.isbn10,
            series = bestSeries,
            seriesNumber = bestSeriesNum,
            genre = longerOf(primary.genre, secondary.genre),
            language = primary.language ?: secondary.language,
            format = primary.format ?: secondary.format,
            subjects = longerOf(primary.subjects, secondary.subjects),
            asin = primary.asin ?: secondary.asin,
            goodreadsId = primary.goodreadsId ?: secondary.goodreadsId,
            openLibraryId = primary.openLibraryId ?: secondary.openLibraryId,
            hardcoverId = primary.hardcoverId ?: secondary.hardcoverId,
            edition = primary.edition ?: secondary.edition,
            originalTitle = primary.originalTitle ?: secondary.originalTitle,
            originalLanguage = primary.originalLanguage ?: secondary.originalLanguage
        )
    }

    /** Prefer non-"Unknown Title", then longer (more specific; cleanTitle strips cruft later). */
    private fun pickBestTitle(a: String, b: String): String {
        val aReal = a != "Unknown Title"
        val bReal = b != "Unknown Title"
        if (aReal && !bReal) return a
        if (!aReal && bReal) return b
        if (!aReal && !bReal) return a
        return if (b.length > a.length) b else a
    }

    /** Prefer non-"Unknown Author", then longer (more complete author list). */
    private fun pickBestAuthor(a: String, b: String): String {
        val aReal = a != "Unknown Author"
        val bReal = b != "Unknown Author"
        if (aReal && !bReal) return a
        if (!aReal && bReal) return b
        if (!aReal && !bReal) return a
        return if (b.length > a.length) b else a
    }

    /** Pick the longer non-null string (more detail = better). */
    private fun longerOf(a: String?, b: String?): String? {
        if (a == null) return b
        if (b == null) return a
        return if (b.length > a.length) b else a
    }

    /** Pick the higher non-null value. */
    private fun maxOfNullable(a: Int?, b: Int?): Int? {
        if (a == null) return b
        if (b == null) return a
        return maxOf(a, b)
    }

    /** Prefer the more specific date (YYYY-MM-DD > YYYY-MM > YYYY). */
    private fun moreSpecificDate(a: String?, b: String?): String? {
        if (a == null) return b
        if (b == null) return a
        return if (b.length > a.length) b else a
    }

    /** Prefer whichever source has both series name and number; otherwise pick the longer name. */
    private fun pickBestSeries(
        aName: String?, aNum: String?,
        bName: String?, bNum: String?
    ): Pair<String?, String?> {
        val aHasBoth = aName != null && aNum != null
        val bHasBoth = bName != null && bNum != null
        return when {
            aHasBoth && !bHasBoth -> aName to aNum
            bHasBoth && !aHasBoth -> bName to bNum
            aName != null && bName != null -> {
                // Both have name (with or without number) — pick longer name, keep its number
                if (bName.length > aName.length) bName to (bNum ?: aNum)
                else aName to (aNum ?: bNum)
            }
            aName != null -> aName to aNum
            bName != null -> bName to bNum
            else -> null to null
        }
    }
}
