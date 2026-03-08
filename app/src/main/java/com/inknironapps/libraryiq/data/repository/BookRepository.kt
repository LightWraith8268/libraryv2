package com.inknironapps.libraryiq.data.repository

import com.inknironapps.libraryiq.BuildConfig
import com.inknironapps.libraryiq.data.local.dao.BookDao
import com.inknironapps.libraryiq.data.local.dao.CollectionDao
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.BookCollectionCrossRef
import com.inknironapps.libraryiq.data.local.entity.BookWithCollections
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus
import com.inknironapps.libraryiq.data.remote.AmazonMetadataScraper
import com.inknironapps.libraryiq.data.remote.BarnesNobleScraper
import com.inknironapps.libraryiq.data.remote.BookApiService
import com.inknironapps.libraryiq.data.remote.FirestoreSync
import com.inknironapps.libraryiq.data.remote.GoogleBookItem
import com.inknironapps.libraryiq.data.remote.HardcoverApiService
import com.inknironapps.libraryiq.data.remote.HardcoverEdition
import com.inknironapps.libraryiq.data.remote.ITunesApiService
import com.inknironapps.libraryiq.data.remote.OpenLibraryApiService
import com.inknironapps.libraryiq.data.remote.OpenLibraryEdition
import com.inknironapps.libraryiq.data.remote.HathiTrustApiService
import com.inknironapps.libraryiq.data.remote.NytBooksApiService
import com.inknironapps.libraryiq.data.remote.OpenBdApiService
import com.inknironapps.libraryiq.data.remote.PrhApiService
import com.inknironapps.libraryiq.data.remote.TargetScraper
import com.inknironapps.libraryiq.data.remote.WikidataApiService
import com.inknironapps.libraryiq.util.DebugLog
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class LookupResult(
    val book: Book?,
    val diagnostics: String
)

data class SearchResult(
    val title: String,
    val author: String,
    val isbn: String?,
    val coverUrl: String?,
    val publisher: String?,
    val publishedDate: String?,
    val pageCount: Int?,
    val source: String
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
    private val barnesNobleScraper: BarnesNobleScraper,
    private val targetScraper: TargetScraper,
    private val iTunesApiService: ITunesApiService,
    private val prhApiService: PrhApiService,
    private val hathiTrustApiService: HathiTrustApiService,
    private val wikidataApiService: WikidataApiService,
    private val openBdApiService: OpenBdApiService,
    private val nytBooksApiService: NytBooksApiService,
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

        // Want to Buy is a shared flag visible to all library members
        firestoreSync.pushWantToBuyFlag(
            book.id,
            updated.readingStatus == ReadingStatus.WANT_TO_BUY
        )

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
     * 5. Open Library (direct ISBN endpoint + search fallback)
     * 6. Hardcover API (if token configured)
     * 7. Amazon product page scraping (like Calibre)
     * 8. Barnes & Noble (direct ISBN/EAN lookup)
     * 9. Target (Specifications section)
     * 10. Amazon title+author fallback (if ISBN missed but other sources found it)
     * 11. Title-based enrichment from Google Books, Open Library, Hardcover
     * 12. Apple Books cover (high-resolution artwork)
     * Merges results for the most complete metadata.
     */
    suspend fun lookupByIsbn(isbn: String): LookupResult = doLookup(isbn, skipLocal = false)

    /**
     * Same as lookupByIsbn but skips the local database check.
     * Used when refreshing metadata for an already-saved book.
     */
    suspend fun lookupByIsbnSkipLocal(isbn: String): LookupResult = doLookup(isbn, skipLocal = true)

    /**
     * Searches for books by title and optional author across all API-based sources:
     * Google Books, Open Library, Hardcover, and iTunes/Apple Books.
     * Returns a list of search results for the user to choose from.
     * Used by the interactive "Add Book" search feature.
     */
    suspend fun searchByTitleAuthor(title: String, author: String?): List<SearchResult> {
        DebugLog.d(TAG, "searchByTitleAuthor: '$title' by '${author ?: "any"}'")
        val results = mutableListOf<SearchResult>()

        coroutineScope {
            // 1. Google Books
            val googleJob = async {
                try {
                    val query = if (!author.isNullOrBlank()) {
                        "intitle:\"$title\" inauthor:\"$author\""
                    } else {
                        "intitle:\"$title\""
                    }
                    val response = bookApiService.searchByIsbn(query)
                    response.items?.take(10)?.map { item ->
                        val info = item.volumeInfo
                        val isbn13 = info.industryIdentifiers
                            ?.firstOrNull { it.type == "ISBN_13" }?.identifier
                        val isbn10 = info.industryIdentifiers
                            ?.firstOrNull { it.type == "ISBN_10" }?.identifier
                        SearchResult(
                            title = info.title ?: "Unknown Title",
                            author = info.authors?.joinToString(", ") ?: "Unknown Author",
                            isbn = isbn13 ?: isbn10,
                            coverUrl = info.imageLinks?.getBestUrl(),
                            publisher = info.publisher,
                            publishedDate = info.publishedDate,
                            pageCount = info.pageCount,
                            source = "Google Books"
                        )
                    } ?: emptyList()
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Search Google Books error: ${e.message}")
                    emptyList()
                }
            }

            // 2. Open Library
            val olJob = async {
                try {
                    val query = if (!author.isNullOrBlank()) {
                        "$title $author"
                    } else {
                        title
                    }
                    val response = openLibraryApiService.searchByTitle(query)
                    response.docs?.take(10)?.map { doc ->
                        SearchResult(
                            title = doc.title ?: "Unknown Title",
                            author = doc.authorNames?.joinToString(", ") ?: "Unknown Author",
                            isbn = doc.isbns?.firstOrNull { it.length == 13 }
                                ?: doc.isbns?.firstOrNull(),
                            coverUrl = doc.coverId?.let { OpenLibraryApiService.coverUrl(it, "M") },
                            publisher = doc.publishers?.firstOrNull(),
                            publishedDate = doc.publishDates?.firstOrNull(),
                            pageCount = doc.pageCount,
                            source = "Open Library"
                        )
                    } ?: emptyList()
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Search Open Library error: ${e.message}")
                    emptyList()
                }
            }

            // 3. Hardcover (best for series data)
            val hardcoverJob = if (BuildConfig.HARDCOVER_API_TOKEN.isNotEmpty()) {
                async {
                    try {
                        val response = hardcoverApiService.query(
                            HardcoverApiService.buildTitleQuery(title)
                        )
                        val editions = response.data?.editions.orEmpty()
                        // Filter by author if provided
                        val filtered = if (!author.isNullOrBlank()) {
                            val normalizedAuthor = author.lowercase().trim()
                            val matched = editions.filter { edition ->
                                edition.book?.contributions?.any { contrib ->
                                    contrib.author?.name?.lowercase()?.trim()?.contains(normalizedAuthor) == true
                                } == true
                            }
                            matched.ifEmpty { editions }
                        } else {
                            editions
                        }
                        filtered.take(10).mapNotNull { edition ->
                            val bookTitle = edition.book?.title ?: edition.title ?: return@mapNotNull null
                            val authorNames = edition.book?.contributions
                                ?.mapNotNull { it.author?.name }
                                ?.joinToString(", ")
                                ?.ifBlank { null }
                            SearchResult(
                                title = bookTitle,
                                author = authorNames ?: "Unknown Author",
                                isbn = edition.isbn13 ?: edition.isbn10,
                                coverUrl = edition.image?.url,
                                publisher = edition.publisher?.name,
                                publishedDate = edition.releaseDate,
                                pageCount = edition.pages,
                                source = "Hardcover"
                            )
                        }
                    } catch (e: Exception) {
                        DebugLog.e(TAG, "Search Hardcover error: ${e.message}")
                        emptyList()
                    }
                }
            } else null

            // 4. iTunes / Apple Books
            val itunesJob = async {
                try {
                    val searchTerm = if (!author.isNullOrBlank()) "$title $author" else title
                    val response = iTunesApiService.searchEbooks(searchTerm, limit = 10)
                    response.results?.mapNotNull { result ->
                        val trackName = result.trackName ?: return@mapNotNull null
                        SearchResult(
                            title = trackName,
                            author = result.artistName ?: "Unknown Author",
                            isbn = null,
                            coverUrl = result.getHighResCoverUrl(),
                            publisher = null,
                            publishedDate = result.releaseDate?.take(10),
                            pageCount = null,
                            source = "Apple Books"
                        )
                    } ?: emptyList()
                } catch (e: Exception) {
                    DebugLog.e(TAG, "Search iTunes error: ${e.message}")
                    emptyList()
                }
            }

            results.addAll(googleJob.await())
            results.addAll(olJob.await())
            hardcoverJob?.let { results.addAll(it.await()) }
            results.addAll(itunesJob.await())
        }

        // Deduplicate by ISBN first, then by title+author for results without ISBN
        val seen = mutableSetOf<String>()
        val deduped = mutableListOf<SearchResult>()
        for (result in results) {
            val key = result.isbn ?: "${result.title.lowercase().trim()}|${result.author.lowercase().trim()}"
            if (seen.add(key)) {
                deduped.add(result)
            }
        }

        DebugLog.d(TAG, "searchByTitleAuthor: ${deduped.size} results (${results.size} before dedup)")
        return deduped
    }

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

        // Fire all API/scraper calls in parallel for maximum speed.
        // Each source is independent; we launch them all then await results.
        val parallelResults = coroutineScope {
            val gb = async { tryGoogleBooksIsbn(isbn) }
            val ol = async { tryOpenLibrary(isbn) }
            val hc = async { tryHardcover(isbn) }
            val prh = async { tryPrh(isbn) }
            val ht = async { tryHathiTrust(isbn) }
            val wd = async { tryWikidata(isbn) }
            val obd = async { tryOpenBd(isbn) }
            val nyt = async { tryNytBooks(isbn) }
            val amz = async { tryAmazon(isbn) }
            val bn = async { tryBarnesNoble(isbn) }
            val tgt = async { tryTarget(isbn) }
            arrayOf(gb.await(), ol.await(), hc.await(), prh.await(), ht.await(), wd.await(), obd.await(), nyt.await(), amz.await(), bn.await(), tgt.await())
        }
        val googleBook = parallelResults[0]
        val openLibraryBook = parallelResults[1]
        val hardcoverBook = parallelResults[2]
        val prhBook = parallelResults[3]
        val hathiTrustBook = parallelResults[4]
        val wikidataBook = parallelResults[5]
        val openBdBook = parallelResults[6]
        val nytBook = parallelResults[7]
        val amazonBook = parallelResults[8]
        val bnBook = parallelResults[9]
        val targetBook = parallelResults[10]

        diag.add(if (googleBook != null) "GB(isbn): ${googleBook.title}" else "GB(isbn): miss")

        // Google Books fallbacks run sequentially only if strict ISBN missed
        val googleGeneralBook = if (googleBook == null) {
            tryGoogleBooksGeneral(isbn).also {
                diag.add(if (it != null) "GB(general): ${it.title}" else "GB(general): miss")
            }
        } else null

        val googleIsbn10Book = if (googleBook == null && googleGeneralBook == null && isbn10 != null) {
            tryGoogleBooksIsbn(isbn10)?.copy(isbn = isbn).also {
                diag.add(if (it != null) "GB(isbn10): ${it.title}" else "GB(isbn10): miss")
            }
        } else null

        diag.add(if (openLibraryBook != null) "OL: ${openLibraryBook.title}" else "OL: miss")
        diag.add(if (hardcoverBook != null) "HC: ${hardcoverBook.title}" else
            if (BuildConfig.HARDCOVER_API_TOKEN.isEmpty()) "HC: no token" else "HC: miss")
        diag.add(if (prhBook != null) "PRH: ${prhBook.title}" else "PRH: miss")
        diag.add(if (hathiTrustBook != null) "HT: ${hathiTrustBook.title}" else "HT: miss")
        diag.add(if (wikidataBook != null) "WD: ${wikidataBook.title}" else "WD: miss")
        diag.add(if (openBdBook != null) "OBD: ${openBdBook.title}" else "OBD: miss")
        diag.add(if (nytBook != null) "NYT: ${nytBook.title}" else "NYT: miss")
        diag.add(if (amazonBook != null) "AMZ: ${amazonBook.title}" else "AMZ: miss")
        diag.add(if (bnBook != null) "BN: ${bnBook.title}" else "BN: miss")
        diag.add(if (targetBook != null) "TGT: ${targetBook.title}" else "TGT: miss")

        // Amazon title+author fallback: if Amazon ISBN search missed but we have
        // title+author from other sources, retry Amazon with title+author search.
        val amazonTitleBook = if (amazonBook == null) {
            val knownTitle = hardcoverBook?.title ?: openLibraryBook?.title
                ?: googleBook?.title ?: googleGeneralBook?.title
                ?: prhBook?.title ?: hathiTrustBook?.title ?: openBdBook?.title
                ?: bnBook?.title ?: targetBook?.title
            val knownAuthor = hardcoverBook?.author ?: openLibraryBook?.author
                ?: googleBook?.author ?: googleGeneralBook?.author
                ?: prhBook?.author ?: wikidataBook?.author ?: openBdBook?.author
                ?: bnBook?.author ?: targetBook?.author
            if (knownTitle != null && knownTitle != "Unknown Title") {
                tryAmazonByTitleAuthor(knownTitle, knownAuthor ?: "Unknown Author", isbn).also {
                    diag.add(if (it != null) "AMZ(title): ${it.title}" else "AMZ(title): miss")
                }
            } else null
        } else null

        // Collect all ISBN-based results and merge.
        // API sources first (higher trust / structured data), then scrapers
        // (fill gaps only). The fold gives earlier items priority for
        // "first non-null wins" fields like coverUrl, language, format.
        val isbnSources = listOfNotNull(
            hardcoverBook, openLibraryBook, googleBook, googleGeneralBook, googleIsbn10Book,
            prhBook, hathiTrustBook, wikidataBook, openBdBook, nytBook,
            amazonBook, amazonTitleBook, bnBook, targetBook
        )

        if (isbnSources.isEmpty()) {
            val diagStr = diag.joinToString(" | ")
            DebugLog.d(TAG, "No sources found for ISBN: $isbn")
            return LookupResult(null, diagStr)
        }

        // Tiered cover assignment: only ISBN-validated API sources (Tier 1)
        // auto-assign covers. Scrapers (Tier 2) can only fill in if no API
        // source had a cover AND the scraper's title matches. Target/title
        // searches (Tier 3) never auto-assign covers.
        val apiSources = listOfNotNull(
            hardcoverBook, openLibraryBook, googleBook, googleGeneralBook, googleIsbn10Book,
            prhBook, hathiTrustBook, wikidataBook, openBdBook, nytBook
        )
        val apiTitle = apiSources
            .map { it.title }
            .firstOrNull { it != "Unknown Title" }
            ?.lowercase()?.trim()

        // Strip ALL scraper covers initially — scrapers contribute metadata
        // but their covers are only used as Tier 2 fallback below.
        val sanitizedSources = isbnSources.map { book ->
            val isScraper = book === amazonBook || book === amazonTitleBook ||
                book === bnBook || book === targetBook
            if (isScraper && book.coverUrl != null) {
                book.copy(coverUrl = null)
            } else book
        }

        var merged = sanitizedSources.drop(1).fold(sanitizedSources.first()) { acc, book ->
            mergeBooks(acc, book)
        }

        // Tier 2: If no API source had a cover, try scrapers whose title matches
        if (merged.coverUrl == null && apiTitle != null) {
            val tier2Sources = listOfNotNull(amazonBook, bnBook)
            for (scraper in tier2Sources) {
                if (scraper.coverUrl != null) {
                    val scraperTitle = scraper.title.lowercase().trim()
                    if (scraperTitle == apiTitle || scraperTitle.contains(apiTitle) || apiTitle.contains(scraperTitle)) {
                        DebugLog.d(TAG, "Tier 2 cover from ${scraper.publisher ?: "scraper"}: title matches API")
                        merged = merged.copy(coverUrl = scraper.coverUrl)
                        break
                    } else {
                        DebugLog.d(TAG, "Tier 2 cover rejected: '${scraper.title}' vs API '$apiTitle'")
                    }
                }
            }
        }

        // Consensus-based author selection: when multiple sources return different
        // authors, pick the one most sources agree on (weighted by quality score).
        // This prevents a single bad source (e.g., wrong Amazon result) from overriding
        // correct data from Google Books, Open Library, and Hardcover.
        val authorVotes = isbnSources
            .map { it.author }
            .filter { it != "Unknown Author" }
        if (authorVotes.size >= 2) {
            val grouped = authorVotes.groupBy { it.lowercase().trim() }
            val best = grouped.entries
                .sortedWith(compareByDescending<Map.Entry<String, List<String>>> { it.value.size }
                    .thenByDescending { scoreAuthor(it.value.first()) })
                .firstOrNull()
            if (best != null) {
                val consensusAuthor = best.value.first() // Use original casing
                if (consensusAuthor != merged.author) {
                    DebugLog.d(TAG, "Author consensus: '${merged.author}' -> '$consensusAuthor' " +
                        "(${best.value.size}/${authorVotes.size} sources agree)")
                    merged = merged.copy(author = consensusAuthor)
                }
            }
        }

        // 7. Title-based enrichment: search by title+author for additional metadata
        // (especially series info). Save edition-specific fields first because title
        // searches may return a different edition (e.g. paperback instead of hardcover)
        // or even a completely different book with the same title.
        val isbnPageCount = merged.pageCount?.takeIf { it > 0 }
        val isbnPublishedDate = merged.publishedDate
        val isbnCoverUrl = merged.coverUrl
        DebugLog.d(TAG, "Enriching by title: '${merged.title}' by '${merged.author}'")
        val titleSources = searchByTitle(merged.title, merged.author, isbn)
        for (enrichment in titleSources) {
            // Strip cover from title-based results — they search by title, not ISBN,
            // so the cover can belong to a completely different book or edition.
            merged = mergeBooks(merged, enrichment.copy(coverUrl = null))
        }
        // Restore edition-specific fields from ISBN-based sources — title searches
        // can't distinguish editions, so their pageCount/publishedDate may be wrong.
        if (isbnPageCount != null) {
            merged = merged.copy(pageCount = isbnPageCount)
        }
        if (isbnPublishedDate != null) {
            merged = merged.copy(publishedDate = isbnPublishedDate)
        }
        if (titleSources.isNotEmpty()) {
            diag.add("title-enrich: ${titleSources.size} hit(s)")
        }

        // Clean up the final title - strip edition/format suffixes and series parentheticals
        merged = merged.copy(title = cleanTitle(merged.title, merged.series))

        // Clean up description - strip trailing "Read More" artifacts from scraped sources
        if (merged.description != null) {
            merged = merged.copy(description = cleanDescription(merged.description!!))
        }

        // Sanitize blank series/seriesNumber to null before further processing
        if (merged.series?.isBlank() == true) {
            merged = merged.copy(series = null)
        }
        if (merged.seriesNumber?.isBlank() == true) {
            merged = merged.copy(seriesNumber = null)
        }

        // Reject format/edition strings that scrapers sometimes return as series names
        if (merged.series != null && isFormatNotSeries(merged.series!!)) {
            DebugLog.d(TAG, "Rejected series '${merged.series}' as format/edition info")
            merged = merged.copy(series = null, seriesNumber = null)
        }

        // If we have a series name but no number, try to extract it from the title.
        // Catches patterns like "Book Title (Series Name Book 3)", "Title #2", etc.
        if (merged.series != null && merged.seriesNumber == null) {
            val title = merged.title
            val numFromTitle = Regex(
                """(?:Book|Volume|Vol\.?|#|,)\s*(\d+)""",
                RegexOption.IGNORE_CASE
            ).find(title)?.groupValues?.get(1)
            if (numFromTitle != null) {
                DebugLog.d(TAG, "Series number from title: #$numFromTitle")
                merged = merged.copy(seriesNumber = numFromTitle)
            }
        }

        // Standardize series name to match existing books in the library
        if (merged.series != null) {
            merged = merged.copy(series = standardizeSeriesName(merged.series!!))
        }

        // If no series found from APIs, check if title prefix (before ':') matches
        // a known series in the library. Catches patterns like "Fallen Academy: Year One"
        // or "Gods of the Game Book 1: Subtitle" where the series is part of the title.
        if (merged.series == null && merged.title.contains(":")) {
            val titlePrefix = merged.title.substringBefore(":").trim()
                // Strip trailing "Book N", "#N", "Volume N", ", N" from prefix
                .replace(Regex("""\s*(?:,\s*#?\s*|(?:Book|Volume|Vol\.?|#)\s*)\d+\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (titlePrefix.length >= 3) {
                val normalizedPrefix = normalizeSeriesForComparison(titlePrefix)
                val existingNames = bookDao.getAllSeriesNames()
                val matchedSeries = existingNames.firstOrNull { existing ->
                    normalizeSeriesForComparison(existing).equals(normalizedPrefix, ignoreCase = true)
                }
                if (matchedSeries != null) {
                    DebugLog.d(TAG, "Series from title prefix '$titlePrefix': '$matchedSeries'")
                    merged = merged.copy(series = matchedSeries)
                }

                // If no existing series matched, check if OTHER books in the library
                // share the same title prefix. This catches series like "Fallen Academy"
                // where "Fallen Academy: Year One" + "Fallen Academy: Year Two" both exist.
                if (merged.series == null) {
                    val booksWithSamePrefix = bookDao.getBooksWithTitlePrefix("$titlePrefix:%")
                    if (booksWithSamePrefix.isNotEmpty()) {
                        DebugLog.d(TAG, "Series from shared prefix: '$titlePrefix' (${booksWithSamePrefix.size} other book(s))")
                        merged = merged.copy(series = titlePrefix)
                        // Also retroactively tag the other books with this series
                        for (sibling in booksWithSamePrefix) {
                            if (sibling.series == null) {
                                bookDao.updateSeries(sibling.id, titlePrefix)
                                DebugLog.d(TAG, "Retroactively tagged '${sibling.title}' with series '$titlePrefix'")
                            }
                        }
                    }
                }

                // If still no series, try Hardcover API to see if the prefix is a known series
                if (merged.series == null && BuildConfig.HARDCOVER_API_TOKEN.isNotEmpty()) {
                    val seriesFromApi = tryHardcoverSeriesSearch(titlePrefix)
                    if (seriesFromApi != null) {
                        DebugLog.d(TAG, "Series from Hardcover prefix search: '${seriesFromApi.first}' #${seriesFromApi.second}")
                        merged = merged.copy(
                            series = seriesFromApi.first,
                            seriesNumber = merged.seriesNumber ?: seriesFromApi.second
                        )
                        diag.add("HC-series: ${seriesFromApi.first}")
                    }
                }
            }
        }

        // 8. Validate that the cover URL points to a real image (not a
        // placeholder). Open Library returns a 1x1 pixel transparent GIF for
        // missing covers; other sources can return dead URLs.
        if (merged.coverUrl != null && !isValidCoverUrl(merged.coverUrl!!)) {
            DebugLog.d(TAG, "Cover URL invalid/placeholder, discarding: ${merged.coverUrl}")
            diag.add("cover-validate: rejected")
            merged = merged.copy(coverUrl = null)
        }

        // 9. Apple Books cover (fallback only - high quality but can't filter by edition)
        // Only use Apple Books cover if no ISBN-based source provided one, because
        // Apple Books searches by title+author and may return a different edition's cover
        // (e.g. paperback cover instead of deluxe hardcover).
        if (merged.coverUrl == null) {
            val appleCover = tryAppleBooksCover(isbn, merged.title, merged.author)
            if (appleCover != null) {
                merged = merged.copy(coverUrl = appleCover)
                diag.add("Apple: cover (fallback)")
            } else {
                diag.add("Apple: miss")
            }
        } else {
            diag.add("Apple: skipped (ISBN cover exists)")
        }

        // Build metadata sources list from which APIs returned data
        val sources = mutableListOf<String>()
        if (googleBook != null || googleGeneralBook != null || googleIsbn10Book != null) sources.add("Google Books")
        if (openLibraryBook != null) sources.add("Open Library")
        if (hardcoverBook != null) sources.add("Hardcover")
        if (prhBook != null) sources.add("Penguin Random House")
        if (hathiTrustBook != null) sources.add("HathiTrust")
        if (wikidataBook != null) sources.add("Wikidata")
        if (openBdBook != null) sources.add("OpenBD")
        if (nytBook != null) sources.add("NYT Books")
        if (amazonBook != null || amazonTitleBook != null) sources.add("Amazon")
        if (bnBook != null) sources.add("Barnes & Noble")
        if (targetBook != null) sources.add("Target")
        merged = merged.copy(metadataSources = sources.joinToString(","))

        val diagStr = diag.joinToString(" | ")
        DebugLog.d(TAG, "Final: ${merged.title} by ${merged.author} " +
            "[cover=${merged.coverUrl != null}, desc=${merged.description != null}, " +
            "pages=${merged.pageCount}, pub=${merged.publisher}, " +
            "series=${merged.series} #${merged.seriesNumber}]")
        return LookupResult(merged, diagStr)
    }

    /** Recent lookup errors (capped at 50 to prevent unbounded growth). */
    var lastErrors = mutableListOf<String>()
        private set

    private fun addError(msg: String) {
        if (lastErrors.size >= 50) lastErrors.removeAt(0)
        lastErrors.add(msg)
    }

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
        // For common titles (e.g. "Wildfire"), multiple different books may share
        // the same title — filter by author to pick the right one.
        if (BuildConfig.HARDCOVER_API_TOKEN.isNotEmpty()) {
            try {
                val response = hardcoverApiService.query(
                    HardcoverApiService.buildTitleQuery(baseTitle)
                )
                val editions = response.data?.editions.orEmpty()
                // Prefer the edition whose author matches the known author
                val bestMatch = if (author != "Unknown Author") {
                    val normalizedAuthor = author.lowercase().trim()
                    editions.firstOrNull { edition ->
                        edition.book?.contributions?.any { contrib ->
                            contrib.author?.name?.lowercase()?.trim() == normalizedAuthor
                        } == true
                    } ?: editions.firstOrNull()
                } else {
                    editions.firstOrNull()
                }
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
            // Validate the result's industryIdentifiers actually contain the scanned ISBN.
            // Google Books can return a completely wrong book for an isbn: query due to
            // metadata errors in their database.
            val isbn10 = if (isbn.length == 13 && isbn.startsWith("978")) convertIsbn13ToIsbn10(isbn) else null
            val matchedItem = response.items?.firstOrNull { item ->
                val ids = item.volumeInfo.industryIdentifiers?.map { it.identifier } ?: emptyList()
                ids.contains(isbn) || (isbn10 != null && ids.contains(isbn10))
            } ?: return null

            // Two-step lookup: fetch the full volume by ID for more complete data
            // (higher-res images, seriesInfo, full description)
            val fullItem = try {
                bookApiService.getVolume(matchedItem.id)
            } catch (e: Exception) {
                DebugLog.d(TAG, "Google Books full volume fetch failed, using search result: ${e.message}")
                matchedItem
            }

            googleBookToBook(fullItem, isbn).also {
                DebugLog.d(TAG, "Google Books (isbn:$isbn): ${it.title}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Google Books (isbn:) error: ${e.message}")
            addError("GB(isbn): ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryGoogleBooksGeneral(isbn: String): Book? {
        return try {
            val response = bookApiService.searchByIsbn(isbn)
            // Validate the result actually contains the scanned ISBN — plain-text search
            // can match a different edition (e.g. paperback instead of deluxe hardcover).
            val isbn10 = if (isbn.length == 13 && isbn.startsWith("978")) convertIsbn13ToIsbn10(isbn) else null
            response.items?.firstOrNull { item ->
                val ids = item.volumeInfo.industryIdentifiers?.map { it.identifier } ?: emptyList()
                ids.contains(isbn) || (isbn10 != null && ids.contains(isbn10))
            }?.let { googleBookToBook(it, isbn) }.also {
                DebugLog.d(TAG, "Google Books (general): ${it?.title ?: "not found"}")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Google Books (general) error: ${e.message}")
            addError("GB(gen): ${e.javaClass.simpleName}: ${e.message}")
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
            addError("OL(direct): ${e.javaClass.simpleName}: ${e.message}")
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
            addError("OL(search): ${e.javaClass.simpleName}: ${e.message}")
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
            addError("HC: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryAmazon(isbn: String): Book? {
        return try {
            // Try product page first (ISBN-10 works as ASIN)
            // Only 978-prefix ISBNs have an ISBN-10 equivalent (usable as Amazon ASIN)
            val isbn10 = if (isbn.length == 13 && isbn.startsWith("978")) {
                convertIsbn13ToIsbn10(isbn)
            } else null
            val productBook = isbn10?.let { amazonScraper.lookupByProductPage(it) }
            if (productBook != null) return productBook.copy(isbn = isbn)

            // Fall back to search
            amazonScraper.lookupByIsbn(isbn)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // Don't swallow cancellation
        } catch (e: Exception) {
            DebugLog.e(TAG, "Amazon scraper error: ${e.message}")
            addError("AMZ: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryBarnesNoble(isbn: String): Book? {
        return try {
            barnesNobleScraper.lookupByIsbn(isbn).also {
                DebugLog.d(TAG, "Barnes & Noble: ${it?.title ?: "not found"}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Barnes & Noble error: ${e.message}")
            addError("BN: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryTarget(isbn: String): Book? {
        return try {
            targetScraper.lookupByIsbn(isbn).also {
                DebugLog.d(TAG, "Target: ${it?.title ?: "not found"}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Target error: ${e.message}")
            addError("TGT: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryAmazonByTitleAuthor(title: String, author: String, isbn: String): Book? {
        return try {
            amazonScraper.lookupByTitleAuthor(title, author, isbn).also {
                DebugLog.d(TAG, "Amazon (title+author): ${it?.title ?: "not found"}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            DebugLog.e(TAG, "Amazon title+author error: ${e.message}")
            addError("AMZ(title): ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun tryPrh(isbn: String): Book? {
        return try {
            val title = prhApiService.getByIsbn(isbn)
            if (title.titleweb.isNullOrBlank()) return null
            val pages = title.pages?.toIntOrNull()
            // PRH flapcopy is the highest quality description available
            val description = title.flapcopy?.trim()?.ifBlank { null }
            // BISAC subject categories (industry standard)
            val subjects = title.subjectcategorydescription?.trim()?.ifBlank { null }
            Book(
                title = title.titleweb ?: "Unknown Title",
                author = title.authorweb ?: title.author ?: "Unknown Author",
                isbn = title.isbn13 ?: isbn,
                isbn10 = title.isbn10,
                description = description,
                pageCount = pages,
                publisher = title.imprint ?: "Penguin Random House",
                publishedDate = title.onsaledate,
                format = title.formatname,
                subjects = subjects,
                genre = title.subjectcategorydescription
            ).also {
                DebugLog.d(TAG, "PRH: '${it.title}' by ${it.author}, format=${title.formatname}")
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "PRH: miss (${e.javaClass.simpleName})")
            null
        }
    }

    private suspend fun tryHathiTrust(isbn: String): Book? {
        return try {
            val response = hathiTrustApiService.getByIsbn(isbn)
            val records = response.getAsJsonObject("records")
            if (records == null || records.size() == 0) return null
            val firstKey = records.keySet().first()
            val record = records.getAsJsonObject(firstKey)
            val titles = record.getAsJsonArray("titles")
            val title = titles?.firstOrNull()?.asString?.trim()
            if (title.isNullOrBlank()) return null
            val publishDates = record.getAsJsonArray("publishDates")
            val publishDate = publishDates?.firstOrNull()?.asString?.trim()
            val oclcs = record.getAsJsonArray("oclcs")
            val lccns = record.getAsJsonArray("lccns")
            val oclc = oclcs?.firstOrNull()?.asString
            val lccn = lccns?.firstOrNull()?.asString
            Book(
                title = title,
                author = "Unknown Author",
                isbn = isbn,
                publishedDate = publishDate,
                subjects = listOfNotNull(
                    oclc?.let { "OCLC:$it" },
                    lccn?.let { "LCCN:$it" }
                ).joinToString(", ").ifBlank { null }
            ).also {
                DebugLog.d(TAG, "HathiTrust: '${it.title}', OCLC=$oclc, LCCN=$lccn")
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "HathiTrust: miss (${e.javaClass.simpleName})")
            null
        }
    }

    private suspend fun tryWikidata(isbn: String): Book? {
        return try {
            val query = WikidataApiService.buildIsbnQuery(isbn)
            val response = wikidataApiService.query(query)
            val binding = response.results?.bindings?.firstOrNull() ?: return null
            val title = binding.bookLabel?.value?.takeIf { !it.startsWith("Q") }
            val author = binding.authorLabel?.value?.takeIf { !it.startsWith("Q") }
            val genre = binding.genreLabel?.value?.takeIf { !it.startsWith("Q") }
            val originalTitle = binding.originalTitle?.value
            val language = binding.languageLabel?.value?.takeIf { !it.startsWith("Q") }
            val series = binding.seriesLabel?.value?.takeIf { !it.startsWith("Q") }
            val seriesOrdinal = binding.seriesOrdinal?.value
            // Collect unique awards from all bindings
            val awards = response.results?.bindings
                ?.mapNotNull { it.awardLabel?.value?.takeIf { v -> !v.startsWith("Q") } }
                ?.distinct()
                ?.joinToString(", ")
                ?.ifBlank { null }
            if (title == null && author == null && genre == null) return null
            Book(
                title = title ?: "Unknown Title",
                author = author ?: "Unknown Author",
                isbn = isbn,
                genre = genre,
                originalTitle = originalTitle,
                originalLanguage = language,
                series = series,
                seriesNumber = seriesOrdinal,
                tags = awards
            ).also {
                DebugLog.d(TAG, "Wikidata: '${it.title}' by ${it.author}, genre=$genre, awards=$awards")
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "Wikidata: miss (${e.javaClass.simpleName})")
            null
        }
    }

    private suspend fun tryOpenBd(isbn: String): Book? {
        return try {
            val response = openBdApiService.getByIsbn(isbn)
            if (response.size() == 0) return null
            val item = response.get(0)
            if (item == null || item.isJsonNull) return null
            val obj = item.asJsonObject
            val summary = obj.getAsJsonObject("summary") ?: return null
            val title = summary.get("title")?.asString?.trim()
            if (title.isNullOrBlank()) return null
            val author = summary.get("author")?.asString?.trim()
            val publisher = summary.get("publisher")?.asString?.trim()
            val pubdate = summary.get("pubdate")?.asString?.trim()
            val cover = summary.get("cover")?.asString?.trim()?.ifBlank { null }
            // Try to get description from ONIX CollateralDetail
            var description: String? = null
            try {
                val onix = obj.getAsJsonObject("onix")
                val collateral = onix?.getAsJsonObject("CollateralDetail")
                val textContents = collateral?.getAsJsonArray("TextContent")
                if (textContents != null && textContents.size() > 0) {
                    // TextType "03" = description, "02" = short description
                    for (tc in textContents) {
                        val tcObj = tc.asJsonObject
                        val text = tcObj.get("Text")?.asString?.trim()
                        if (!text.isNullOrBlank()) {
                            if (description == null || text.length > description.length) {
                                description = text
                            }
                        }
                    }
                }
            } catch (_: Exception) { /* ONIX parsing is best-effort */ }
            Book(
                title = title,
                author = author ?: "Unknown Author",
                isbn = isbn,
                publisher = publisher,
                publishedDate = pubdate,
                coverUrl = cover,
                description = description
            ).also {
                DebugLog.d(TAG, "OpenBD: '${it.title}' by ${it.author}, cover=${cover != null}")
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "OpenBD: miss (${e.javaClass.simpleName})")
            null
        }
    }

    private suspend fun tryNytBooks(isbn: String): Book? {
        val apiKey = BuildConfig.NYT_API_KEY
        if (apiKey.isBlank()) return null
        return try {
            // Try bestseller history first (has description + rank data)
            val bsResponse = nytBooksApiService.getBestsellerHistory(isbn, apiKey)
            val entry = bsResponse.results?.firstOrNull()
            if (entry != null) {
                val bestRank = entry.ranksHistory?.minByOrNull { it.rank ?: Int.MAX_VALUE }
                val rankInfo = bestRank?.let {
                    "NYT Bestseller: #${it.rank} on ${it.displayName ?: it.listName}" +
                        (it.weeksOnList?.let { w -> " ($w weeks)" } ?: "")
                }
                return Book(
                    title = entry.title ?: "Unknown Title",
                    author = entry.author ?: "Unknown Author",
                    isbn = isbn,
                    publisher = entry.publisher,
                    description = entry.description?.takeIf { it.isNotBlank() },
                    tags = rankInfo
                ).also {
                    DebugLog.d(TAG, "NYT: '${it.title}' — $rankInfo")
                }
            }
            // Fall back to reviews endpoint
            val revResponse = nytBooksApiService.getReviews(isbn, apiKey)
            val review = revResponse.results?.firstOrNull() ?: return null
            Book(
                title = review.bookTitle ?: "Unknown Title",
                author = review.bookAuthor ?: "Unknown Author",
                isbn = isbn,
                description = review.summary?.takeIf { it.isNotBlank() }
            ).also {
                DebugLog.d(TAG, "NYT: '${it.title}' — review found")
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "NYT: miss (${e.javaClass.simpleName})")
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
                    OpenLibraryApiService.coverUrlByIsbn(isbn)
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
            jobs.add(async { fetchITunesCovers(isbn, title, author) })

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

    private suspend fun fetchITunesCovers(isbn: String?, title: String, author: String): List<CoverOption> {
        val results = mutableListOf<CoverOption>()
        try {
            // Try direct ISBN lookup first (exact match, no search ambiguity)
            if (!isbn.isNullOrBlank()) {
                val lookupResponse = iTunesApiService.lookupByIsbn(isbn)
                lookupResponse.results?.forEach { result ->
                    result.getHighResCoverUrl()?.let { url ->
                        results.add(CoverOption("Apple Books (ISBN)", url))
                    }
                }
            }
            // Fall back to title+author search for additional options
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
     * Fetches the best Apple Books cover for a book.
     * Tries direct ISBN lookup first (exact match), then falls back to title+author search.
     */
    private suspend fun tryAppleBooksCover(isbn: String?, title: String, author: String): String? {
        return try {
            // Try direct ISBN lookup first
            if (!isbn.isNullOrBlank()) {
                val lookupResponse = iTunesApiService.lookupByIsbn(isbn)
                val isbnCover = lookupResponse.results?.firstOrNull()?.getHighResCoverUrl()
                if (isbnCover != null) {
                    DebugLog.d(TAG, "Apple Books cover: found via ISBN lookup")
                    return isbnCover
                }
            }
            // Fall back to title+author search
            val searchTerm = if (author != "Unknown Author") "$title $author" else title
            val response = iTunesApiService.searchEbooks(searchTerm, limit = 1)
            response.results?.firstOrNull()?.getHighResCoverUrl().also {
                DebugLog.d(TAG, "Apple Books cover: ${if (it != null) "found" else "not found"} for '$searchTerm'")
            }
        } catch (e: Exception) {
            DebugLog.e(TAG, "Apple Books cover error: ${e.message}")
            addError("Apple: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /**
     * Validates that a cover URL points to a real image (not a placeholder).
     * Open Library returns a tiny 1x1 pixel transparent GIF (43 bytes) for
     * missing covers instead of a 404. This catches that and similar cases.
     */
    private suspend fun isValidCoverUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = true
            try {
                val code = connection.responseCode
                val contentLength = connection.contentLengthLong
                val contentType = connection.contentType ?: ""
                // Reject non-200, non-image, or tiny placeholder images (< 1KB)
                code == 200 && contentType.startsWith("image") && contentLength > 1000
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            DebugLog.d(TAG, "Cover validation failed for $url: ${e.message}")
            false
        }
    }

    /**
     * Searches Hardcover API by a title prefix to find series info.
     * Used when the title has a "Series Name: Subtitle" pattern but no API
     * returned series data. Queries Hardcover with just the prefix to see
     * if it's a known series.
     */
    private suspend fun tryHardcoverSeriesSearch(titlePrefix: String): Pair<String, String?>? {
        return try {
            val response = hardcoverApiService.query(
                HardcoverApiService.buildTitleQuery(titlePrefix)
            )
            // Check if any result has series info
            for (edition in response.data?.editions.orEmpty()) {
                val seriesInfo = edition.book?.bookSeries?.firstOrNull()
                if (seriesInfo?.series?.name != null) {
                    val seriesNumber = seriesInfo.position?.let {
                        if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString()
                    }
                    return Pair(seriesInfo.series.name, seriesNumber)
                }
            }
            null
        } catch (e: Exception) {
            DebugLog.e(TAG, "Hardcover series search error: ${e.message}")
            null
        }
    }

    /** Rejects format/edition strings that scrapers sometimes return as series names. */
    private fun isFormatNotSeries(name: String): Boolean {
        val lower = name.lowercase().trim()
        val formatKeywords = listOf(
            "audible", "audio", "kindle", "ebook", "e-book", "paperback",
            "hardcover", "hardback", "edition", "mass market", "board book",
            "library binding", "cd", "mp3", "digital"
        )
        return formatKeywords.any { lower.contains(it) }
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

        // Google Books language codes are ISO 639-1 (e.g. "en"); capitalize for display
        val language = info.language?.let { code ->
            Locale(code).displayLanguage.ifBlank { null }
        }

        // Build title with subtitle if available
        val fullTitle = if (!info.subtitle.isNullOrBlank() && info.title != null) {
            "${info.title}: ${info.subtitle}"
        } else {
            info.title ?: "Unknown Title"
        }

        // Extract series info from Google Books seriesInfo field
        val seriesNumber = info.seriesInfo?.bookDisplayNumber
        // Google Books doesn't provide series name directly in seriesInfo,
        // but the bookDisplayNumber tells us it IS part of a series

        return Book(
            title = fullTitle,
            author = info.authors?.joinToString(", ") ?: "Unknown Author",
            isbn = isbn13 ?: isbn10,
            isbn10 = isbn10,
            description = info.description,
            coverUrl = info.imageLinks?.getBestUrl(),
            pageCount = info.pageCount,
            publisher = info.publisher,
            publishedDate = info.publishedDate,
            subjects = info.categories?.joinToString(", "),
            language = language,
            seriesNumber = seriesNumber
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

        // Combine genres with subjects for richer genre data
        val genreList = (edition.genres.orEmpty() + subjects.orEmpty()).distinct()

        // Build edition info from edition_name field
        val editionInfo = edition.editionName?.trim()?.ifBlank { null }

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
            series = edition.series?.firstOrNull()?.trim()?.ifBlank { null },
            language = language,
            format = edition.physicalFormat,
            subjects = genreList.take(10).joinToString(", ").ifBlank { null },
            edition = editionInfo
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
        val seriesName = seriesInfo?.series?.name?.trim()?.ifBlank { null }
        val seriesNumber = seriesInfo?.position?.let {
            if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString()
        }

        // Use cached_tags for genre/tags enrichment
        val tags = edition.book?.cachedTags?.joinToString(", ")?.ifBlank { null }

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
            seriesNumber = seriesNumber,
            format = edition.editionFormat,
            tags = tags
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
        // Handles: (Series Book 1), (Series #1), (Series, 1), (Series, #1), (Series)
        if (seriesName != null) {
            cleaned = cleaned.replace(
                Regex("""\s*\(\s*${Regex.escape(seriesName)}(?:\s*(?:,\s*#?\s*|(?:Book|Volume|Vol\.?|#)\s*)\d+)?\s*\)""", RegexOption.IGNORE_CASE), ""
            )
        }
        // Strip anything from the first '(' onward — removes edition, series, format info in parens
        cleaned = cleaned.replace(Regex("""\s*\(.*$"""), "")
        return cleaned.trim()
    }

    /** Strips trailing "Read More", "Read more", etc. scraped from Amazon/other sources. */
    private fun cleanDescription(desc: String): String {
        return desc
            .replace(Regex("""\s*Read\s*more\.?\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Read\s*less\.?\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
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
            tags = longerOf(primary.tags, secondary.tags),
            edition = primary.edition ?: secondary.edition,
            originalTitle = primary.originalTitle ?: secondary.originalTitle,
            originalLanguage = primary.originalLanguage ?: secondary.originalLanguage,
            metadataSources = primary.metadataSources ?: secondary.metadataSources
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

    /**
     * Prefer non-"Unknown Author", then score by quality, then length as tiebreaker.
     * Prevents garbage like "To Be To Be Confirmed Atria" from winning over "Hannah Grace".
     */
    private fun pickBestAuthor(a: String, b: String): String {
        val aReal = a != "Unknown Author"
        val bReal = b != "Unknown Author"
        if (aReal && !bReal) return a
        if (!aReal && bReal) return b
        if (!aReal && !bReal) return a
        val scoreA = scoreAuthor(a)
        val scoreB = scoreAuthor(b)
        if (scoreA > scoreB) return a
        if (scoreB > scoreA) return b
        // Tied score - prefer longer (more complete author list)
        return if (b.length > a.length) b else a
    }

    /**
     * Scores an author name by quality. Higher = more likely a real author name.
     * Penalizes publisher names, placeholders, and suspiciously long/short strings.
     */
    private fun scoreAuthor(name: String): Int {
        if (name == "Unknown Author") return 0
        var score = 10
        val lower = name.lowercase().trim()
        // Placeholder text (Amazon pre-release)
        if (lower.contains("to be confirmed") || lower.contains("to be announced") ||
            lower.contains("tbd") || lower.contains("tba") || lower.contains("forthcoming")) {
            score -= 8
        }
        // Publisher names leaking into author field
        val publishers = listOf(
            "atria", "penguin", "harpercollins", "simon & schuster", "random house",
            "hachette", "macmillan", "scholastic", "bloomsbury", "vintage", "knopf",
            "doubleday", "bantam", "berkley", "putnam", "dutton", "avon", "mira",
            "harlequin", "tor", "orbit", "gallery", "pocket books", "scribner",
            "grand central", "st. martin", "william morrow", "piatkus"
        )
        if (publishers.any { lower.contains(it) }) score -= 6
        // Suspiciously long (likely concatenated garbage)
        if (name.length > 40) score -= 3
        // Typical author names have 2-3 words
        val words = name.trim().split(Regex("""\s+"""))
        if (words.size in 2..3) score += 2
        if (words.size > 5) score -= 2
        return score
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
        // Treat blank series names as null
        val aN = aName?.trim()?.ifBlank { null }
        val bN = bName?.trim()?.ifBlank { null }
        val aNo = aNum?.trim()?.ifBlank { null }
        val bNo = bNum?.trim()?.ifBlank { null }
        val aHasBoth = aN != null && aNo != null
        val bHasBoth = bN != null && bNo != null
        return when {
            aHasBoth && !bHasBoth -> aN to aNo
            bHasBoth && !aHasBoth -> bN to bNo
            aN != null && bN != null -> {
                // Both have name (with or without number) — pick longer name, keep its number
                if (bN.length > aN.length) bN to (bNo ?: aNo)
                else aN to (aNo ?: bNo)
            }
            aN != null -> aN to aNo
            bN != null -> bN to bNo
            else -> null to null
        }
    }
}
