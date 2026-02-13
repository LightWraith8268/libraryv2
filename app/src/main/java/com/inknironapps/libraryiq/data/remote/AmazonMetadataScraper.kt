package com.inknironapps.libraryiq.data.remote

import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scrapes Amazon product pages for book metadata, similar to how
 * Calibre's Amazon metadata plugin works. Fetches the product page
 * by ISBN and parses the Product Details section and other structured
 * data from the HTML.
 *
 * Handles both Amazon HTML layouts:
 * - Layout A: Detail bullets list (detailBullets_feature_div)
 * - Layout B: Product details table (productDetails_detailBullets_sections1)
 * - Product overview table (productOverview_feature_div)
 *
 * Extracts: title, author, cover, description, pages, publisher,
 * publishedDate, format, series/seriesNumber, language, genre/subjects,
 * ASIN, ISBN-10, edition.
 */
@Singleton
class AmazonMetadataScraper @Inject constructor() {

    companion object {
        private const val TAG = "AmazonScraper"
        // Desktop user agent gets more complete HTML than mobile
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Looks up a book on Amazon by ISBN.
     * Searches Amazon, extracts the ASIN from the first result,
     * then fetches the product page for full metadata.
     */
    suspend fun lookupByIsbn(isbn: String): Book? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.amazon.com/s?k=$isbn&i=stripbooks"
            DebugLog.d(TAG, "Searching: $url")

            val html = fetchPage(url) ?: return@withContext null

            // Extract series info from search result title before trying product pages
            // Search results often have "(Series Name Book N)" which product pages may lack
            val searchSeriesInfo = extractSearchTitle(html)?.let { extractSeriesFromText(it) }
            if (searchSeriesInfo != null) {
                DebugLog.d(TAG, "Series from search title: ${searchSeriesInfo.first} #${searchSeriesInfo.second}")
            }

            // Extract all unique ASINs from search results
            val asins = extractAllAsins(html)
            DebugLog.d(TAG, "Found ${asins.size} ASINs: ${asins.take(5)}")

            // Try non-B ASINs first (physical books use ISBN-10 as ASIN)
            for (asin in asins) {
                if (asin.startsWith("B")) continue
                DebugLog.d(TAG, "Trying physical ASIN: $asin")
                val productBook = fetchProductPage(asin, isbn)
                if (productBook != null) return@withContext mergeSearchSeries(productBook, searchSeriesInfo)
            }

            // Then try B-ASINs (some physical books only have B-ASINs)
            for (asin in asins) {
                if (!asin.startsWith("B")) continue
                DebugLog.d(TAG, "Trying B-ASIN: $asin")
                val productBook = fetchProductPage(asin, isbn)
                if (productBook != null) return@withContext mergeSearchSeries(productBook, searchSeriesInfo)
            }

            // Fallback: parse search results directly
            parseSearchResults(html, isbn)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Lookup failed for $isbn", e)
            null
        }
    }

    /**
     * Merges series info from search results into a product page result.
     * Search results often have "Book N" in the title which the product page lacks.
     */
    private fun mergeSearchSeries(book: Book, searchSeries: Pair<String, String?>?): Book {
        if (searchSeries == null) return book
        // If product page already has complete series info, keep it
        if (book.series != null && book.seriesNumber != null) return book
        return book.copy(
            series = book.series ?: searchSeries.first,
            seriesNumber = book.seriesNumber ?: searchSeries.second
        )
    }

    /**
     * Attempts to fetch the direct product page by ASIN/ISBN-10.
     */
    suspend fun lookupByProductPage(isbn: String): Book? = withContext(Dispatchers.IO) {
        try {
            fetchProductPage(isbn, isbn)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Product page lookup failed for $isbn", e)
            null
        }
    }

    private fun fetchPage(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string()

        if (response.code != 200) {
            DebugLog.w(TAG, "$url -> ${response.code}")
            return null
        }
        DebugLog.d(TAG, "$url -> 200 (${body?.length ?: 0} bytes)")
        return body
    }

    private fun fetchProductPage(asin: String, isbn: String): Book? {
        val url = "https://www.amazon.com/dp/$asin"
        val html = fetchPage(url) ?: return null
        return parseProductPage(html, isbn, asin)
    }

    private fun extractAllAsins(html: String): List<String> {
        val dpRegex = """/dp/([A-Z0-9]{10})""".toRegex()
        return dpRegex.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .take(5) // limit to avoid too many requests
            .toList()
    }

    /**
     * Searches Amazon by book title and author name instead of ISBN.
     * Used as a fallback when ISBN-based lookup fails but we have
     * title/author from another source (e.g., B&N, Target, Google Books).
     *
     * @param title The book title to search for
     * @param author The author name (pass "Unknown Author" to omit)
     * @param isbn The original ISBN to validate against product page
     */
    suspend fun lookupByTitleAuthor(title: String, author: String, isbn: String): Book? = withContext(Dispatchers.IO) {
        try {
            val searchTerms = buildString {
                append(title)
                if (author != "Unknown Author") {
                    append(" ")
                    append(author)
                }
            }
            val encoded = java.net.URLEncoder.encode(searchTerms, "UTF-8")
            val url = "https://www.amazon.com/s?k=$encoded&i=stripbooks"
            DebugLog.d(TAG, "Title+author search: $url")

            val html = fetchPage(url) ?: return@withContext null

            val searchSeriesInfo = extractSearchTitle(html)?.let { extractSeriesFromText(it) }

            val asins = extractAllAsins(html)
            DebugLog.d(TAG, "Title+author search found ${asins.size} ASINs")

            // Try non-B ASINs first (physical books)
            for (asin in asins) {
                if (asin.startsWith("B")) continue
                val productBook = fetchProductPage(asin, isbn)
                if (productBook != null) return@withContext mergeSearchSeries(productBook, searchSeriesInfo)
            }

            // Then try B-ASINs
            for (asin in asins) {
                if (!asin.startsWith("B")) continue
                val productBook = fetchProductPage(asin, isbn)
                if (productBook != null) return@withContext mergeSearchSeries(productBook, searchSeriesInfo)
            }

            // Fallback: parse search results
            parseSearchResults(html, isbn)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Title+author lookup failed", e)
            null
        }
    }

    // --- Search Results Parsing (fallback if product page fails) ---

    private fun parseSearchResults(html: String, isbn: String): Book? {
        val rawTitle = extractSearchTitle(html)
        val author = extractSearchAuthor(html)
        val imageUrl = extractSearchImage(html)

        if (rawTitle == null || !isValidTitle(rawTitle)) {
            DebugLog.d(TAG, "No valid title in search results: ${rawTitle?.take(60)}")
            return null
        }

        // Extract series from title like "Fleet School Dropout (Warborn Protocols Book 1)"
        val seriesInfo = extractSeriesFromText(rawTitle)
        // Clean the title: remove series parenthetical
        val title = rawTitle
            .replace(Regex("""\s*\([^)]*\)\s*$"""), "")
            .trim()

        DebugLog.d(TAG, "Search parsed: '$title' by '$author', image=${imageUrl != null}, " +
            "series=${seriesInfo?.first} #${seriesInfo?.second}")

        return Book(
            title = cleanTitle(title),
            author = author ?: "Unknown Author",
            isbn = isbn,
            coverUrl = imageUrl,
            series = seriesInfo?.first,
            seriesNumber = seriesInfo?.second
        )
    }

    private fun extractSearchTitle(html: String): String? {
        val titleRecipe = """data-cy="title-recipe"[^>]*>.*?<span[^>]*>([^<]+)</span>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()

        val mediumText = """class="a-size-medium[^"]*a-text-normal"[^>]*>([^<]+)</""".toRegex()
            .find(html)?.groupValues?.get(1)?.trim()

        return titleRecipe ?: mediumText
    }

    private fun extractSearchAuthor(html: String): String? {
        val byLinkRegex = """>\s*by\s*</span>\s*(?:<[^>]+>\s*)*<a[^>]*>([^<]+)</a>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val byLink = byLinkRegex.find(html)?.groupValues?.get(1)?.trim()
        if (byLink != null && isValidAuthor(byLink)) return byLink

        val byTextRegex = """\bby\s+</span>\s*<span[^>]*>([^<]+)</span>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val byText = byTextRegex.find(html)?.groupValues?.get(1)?.trim()
        if (byText != null && isValidAuthor(byText)) return byText

        return null
    }

    private fun extractSearchImage(html: String): String? {
        val imgRegex = """<img[^>]*class="s-image"[^>]*src="([^"]+)"[^>]*/?>""".toRegex()
        return imgRegex.find(html)?.groupValues?.get(1)
    }

    // =====================================================================
    // Product Page Parsing
    // =====================================================================

    private fun parseProductPage(html: String, isbn: String, asin: String): Book? {
        // Skip eBooks / Kindle / Audible editions - only want physical books
        if (isNonPhysicalFormat(html)) {
            DebugLog.d(TAG, "Skipping non-physical edition (eBook/Kindle/Audible)")
            return null
        }

        // Parse ALL product details into a unified key-value map.
        // This reads from detail bullets, product details table, and product overview.
        val details = parseAllProductDetails(html)

        // Validate this is actually a book product page (not a credit card, electronics, etc.)
        if (!isBookProductPage(html, details)) {
            DebugLog.d(TAG, "Skipping non-book product page")
            return null
        }

        // Verify the product page ISBN matches what we searched for.
        // Prevents returning metadata from a wrong book when Amazon search
        // returns an incorrect result (e.g., Emily McIntire instead of Hannah Grace).
        if (!verifyIsbnMatch(html, isbn, asin, details)) {
            DebugLog.d(TAG, "ISBN mismatch - product page is for a different book")
            return null
        }

        // Most reliable: parse <title> tag
        // Format: "Book Title: Author Last, First: 9781234567890: Amazon.com: Books"
        val titleTagResult = parseHtmlTitleTag(html)

        var title = titleTagResult?.first
            ?: extractProductTitleFromHtml(html)
        if (title == null) {
            DebugLog.d(TAG, "No title found in product page")
            return null
        }

        val author = extractProductAuthor(html)
            ?: titleTagResult?.second
        val imageUrl = extractProductImage(html)
        val description = extractProductDescription(html)

        // Publisher and publication date from details map.
        // Amazon often combines them: "Publisher Name; 1st edition (March 1, 2024)"
        val publisherRaw = details.getByKeys("Publisher", "Imprint")
        val publisher = publisherRaw
            ?.replace(Regex("""\s*;.*"""), "")      // Remove "; 1st edition (date)"
            ?.replace(Regex("""\s*\([^)]*\d{4}[^)]*\)"""), "")  // Remove "(March 1, 2024)"
            ?.trim()
            ?.ifBlank { null }
            ?: extractDetailBullet(html, "Publisher")
            ?: extractDetailBullet(html, "Imprint")

        val pubDate = details.getByKeys("Publication date", "Publish date")
            ?: publisherRaw?.let {
                // Extract date from publisher field: "Publisher (March 1, 2024)"
                Regex("""\(([^)]*\d{4}[^)]*)\)""").find(it)?.groupValues?.get(1)
            }
            ?: extractDetailBullet(html, "Publication date")
            ?: extractDetailBullet(html, "Publish date")

        val pages = extractPageCount(html, details)

        // Extract series from multiple sources and combine
        val titleSeries = extractSeriesFromTitleParts(titleTagResult?.third)
        val knownSeriesName = titleSeries?.first?.takeIf { it.isNotBlank() }
        val htmlSeries = extractSeriesFromHtml(html, knownSeriesName)
        // Also check the parsed product details map (catches cases where series is
        // in the details table/bullets but not in structured HTML sections)
        val detailSeriesRaw = details.getByKeys("Series", "Series Title")
        val detailSeriesName = detailSeriesRaw
            ?.replace(Regex("""\s*\([^)]*\)"""), "")?.trim()?.ifBlank { null }
        val detailSeriesNumber = detailSeriesRaw?.let {
            Regex("""\((?:Book|Vol\.?|#)\s*(\d+)\)""", RegexOption.IGNORE_CASE)
                .find(it)?.groupValues?.get(1)
        }
        // Combine: prefer name from title-tag, then HTML, then details map
        val seriesName = knownSeriesName
            ?: htmlSeries?.first?.takeIf { it.isNotBlank() }
            ?: detailSeriesName
        val seriesNumber = titleSeries?.second
            ?: htmlSeries?.second
            ?: detailSeriesNumber
        val seriesInfo = if (seriesName != null) Pair(seriesName, seriesNumber) else null

        // Strip series parenthetical from title if we extracted it
        // Handles: (Series Book 1), (Series #1), (Series, 1), (Series, #1), (Series)
        if (seriesName != null) {
            title = title
                .replace(Regex("""\s*\(\s*${Regex.escape(seriesName)}(?:\s*(?:,\s*#?\s*|(?:Book|Volume|Vol\.?|#)\s*)\d+)?\s*\)""", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        val format = extractFormat(html)
            ?: details.getByKeys("Format", "Binding")
        val language = extractLanguage(html, details)
        val genres = extractAllGenres(html)
        val isbn10 = extractIsbn10(html, asin, details)
        val detectedAsin = extractAsin(html, asin)
        val edition = extractEdition(html, title, details)

        DebugLog.d(TAG, "Product page: '$title' by '$author', " +
            "image=${imageUrl != null}, pages=$pages, publisher=$publisher, " +
            "format=$format, series=${seriesInfo?.first} #${seriesInfo?.second}, " +
            "lang=$language, genres=${genres?.take(80)}, isbn10=$isbn10, " +
            "asin=$detectedAsin, edition=$edition, details=${details.size} fields")

        return Book(
            title = cleanTitle(title),
            author = author ?: "Unknown Author",
            isbn = isbn,
            isbn10 = isbn10,
            description = description,
            coverUrl = imageUrl,
            pageCount = pages,
            publisher = publisher,
            publishedDate = pubDate,
            format = format,
            series = seriesInfo?.first,
            seriesNumber = seriesInfo?.second,
            language = language,
            subjects = genres,
            asin = detectedAsin,
            edition = edition
        )
    }

    // =====================================================================
    // Product Details Map - unified parsing of ALL detail key-value pairs
    // =====================================================================

    /**
     * Parses ALL key-value pairs from the Product Details section into a map.
     * Handles both Amazon HTML layouts (A/B tested):
     *
     * Layout A - Detail Bullets list:
     *   <span class="a-text-bold">Label‏ : ‎</span><span>Value</span>
     *
     * Layout B - Product Details table:
     *   <th class="prodDetSectionEntry">Label</th><td class="prodDetAttrValue">Value</td>
     *
     * Also reads the Product Overview table for additional fields.
     */
    private fun parseAllProductDetails(html: String): Map<String, String> {
        val details = mutableMapOf<String, String>()

        // --- Layout A: Detail Bullets list ---
        // Labels are in <span class="a-text-bold"> with Unicode marks around the colon.
        // Values are in the immediately following <span>.
        val bulletSection = extractHtmlSection(html, "detailBullets_feature_div", 8000)
            ?: extractHtmlSection(html, "detailBulletsWrapper_feature_div", 8000)
        if (bulletSection != null) {
            val bulletRegex = """<span[^>]*class="a-text-bold"[^>]*>\s*([^<]+?)\s*</span>\s*(?:</span>\s*)?<span[^>]*>\s*([^<]+)"""
                .toRegex(RegexOption.DOT_MATCHES_ALL)
            for (match in bulletRegex.findAll(bulletSection)) {
                val rawLabel = match.groupValues[1]
                    .replace(Regex("""[\u200F\u200E\u00A0:]+"""), " ")
                    .replace(Regex("""&[lr]rm;|&nbsp;"""), " ")
                    .trim()
                val value = match.groupValues[2]
                    .replace(Regex("""[\u200F\u200E\u00A0]+"""), " ")
                    .trim()
                if (isValidDetailEntry(rawLabel, value)) {
                    details.putIfAbsent(rawLabel, value)
                }
            }
        }

        // --- Layout B: Product Details table ---
        // Labels are in <th>, values in <td>.
        val tableSection = extractHtmlSection(html, "productDetails_detailBullets_sections1", 8000)
            ?: extractHtmlSection(html, "productDetails_feature_div", 8000)
            ?: extractHtmlSection(html, "prodDetails", 8000)
        if (tableSection != null) {
            val tableRegex = """<th[^>]*>\s*([^<]+?)\s*</th>\s*<td[^>]*>\s*([^<]+?)(?:\s*<)"""
                .toRegex(RegexOption.IGNORE_CASE)
            for (match in tableRegex.findAll(tableSection)) {
                val label = match.groupValues[1].trim()
                val value = match.groupValues[2]
                    .replace(Regex("""[\u200F\u200E\u00A0]+"""), " ")
                    .trim()
                if (isValidDetailEntry(label, value)) {
                    details.putIfAbsent(label, value)
                }
            }
        }

        // --- Product Overview table ---
        // Structured as: <td><span class="a-text-bold">Label</span></td><td><span>Value</span></td>
        val overviewSection = extractHtmlSection(html, "productOverview_feature_div", 5000)
        if (overviewSection != null) {
            val overviewRegex = """<span[^>]*a-text-bold[^>]*>\s*([^<]+?)\s*</span>\s*</td>\s*<td[^>]*>\s*<span[^>]*>\s*([^<]+?)"""
                .toRegex(RegexOption.DOT_MATCHES_ALL)
            for (match in overviewRegex.findAll(overviewSection)) {
                val label = match.groupValues[1].trim()
                val value = match.groupValues[2].trim()
                if (isValidDetailEntry(label, value)) {
                    details.putIfAbsent(label, value)
                }
            }
        }

        if (details.isNotEmpty()) {
            DebugLog.d(TAG, "Product details (${details.size} entries): ${details.entries.joinToString { "${it.key}=${it.value.take(40)}" }}")
        }
        return details
    }

    /**
     * Extracts a section of HTML starting from an element with the given ID.
     * Returns up to [maxLen] characters starting from the ID attribute.
     */
    private fun extractHtmlSection(html: String, id: String, maxLen: Int): String? {
        val idIndex = html.indexOf("id=\"$id\"")
        if (idIndex < 0) return null
        val start = (idIndex - 100).coerceAtLeast(0)
        val end = (idIndex + maxLen).coerceAtMost(html.length)
        return html.substring(start, end)
    }

    /** Validates a detail entry label/value pair before adding to the map. */
    private fun isValidDetailEntry(label: String, value: String): Boolean {
        return label.length in 2..60 &&
            value.isNotBlank() &&
            value.length < 300 &&
            !label.equals("Best Sellers Rank", ignoreCase = true) &&
            !label.equals("Customer Reviews", ignoreCase = true) &&
            !label.startsWith("http", ignoreCase = true)
    }

    /**
     * Looks up a value from the details map by trying multiple key names.
     * Amazon uses different labels across layouts (e.g. "Print length" vs "Paperback").
     */
    private fun Map<String, String>.getByKeys(vararg keys: String): String? {
        for (key in keys) {
            // Try exact match first
            val exact = this[key]
            if (exact != null) return exact
            // Try case-insensitive match
            val entry = entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
            if (entry != null) return entry.value
        }
        return null
    }

    // =====================================================================
    // Title, Author, Image, Description extraction
    // =====================================================================

    /**
     * Parses the HTML <title> tag which is the most reliable metadata source.
     * Amazon format: "Book Title: Author Last, First: ISBN: Amazon.com: Books"
     * Returns Triple(title, author, allMeaningfulParts) or null.
     */
    private fun parseHtmlTitleTag(html: String): Triple<String, String?, List<String>>? {
        val titleRegex = """<title[^>]*>([^<]+)</title>""".toRegex(RegexOption.IGNORE_CASE)
        val rawTitle = titleRegex.find(html)?.groupValues?.get(1)?.trim() ?: return null

        // Remove "Amazon.com: " prefix if present
        val cleaned = rawTitle
            .replace(Regex("""^Amazon\.com\s*:\s*""", RegexOption.IGNORE_CASE), "")

        // Split on ": " to extract parts
        // Typical: "Book Title: Author: ISBN: Amazon.com: Books"
        val parts = cleaned.split(Regex("""\s*:\s*"""))

        // Find and remove the Amazon.com and Books parts
        val meaningful = parts.filter { part ->
            !part.equals("Amazon.com", ignoreCase = true) &&
            !part.equals("Books", ignoreCase = true) &&
            !part.matches(Regex("""\d{10,13}""")) && // ISBNs
            part.isNotBlank()
        }

        if (meaningful.isEmpty()) return null

        val bookTitle = meaningful.first()

        // Author is usually the LAST meaningful part (after title, subtitle, edition info)
        // Search backwards for the first part that looks like an author name
        var author: String? = null
        for (i in meaningful.lastIndex downTo 1) {
            val candidate = meaningful[i]
            if (isValidAuthor(candidate) && !candidate.equals(bookTitle, ignoreCase = true)) {
                // Amazon uses "Last, First" format - flip to "First Last"
                author = if (candidate.contains(",")) {
                    candidate.split(",").map { it.trim() }.reversed().joinToString(" ")
                } else {
                    candidate
                }
                break
            }
        }

        DebugLog.d(TAG, "Title tag parsed: '$bookTitle' by '$author' (raw: '$rawTitle')")
        return Triple(bookTitle, author, meaningful)
    }

    private fun extractProductTitleFromHtml(html: String): String? {
        // productTitle span
        val spanRegex = """id="productTitle"[^>]*>([^<]+)</""".toRegex()
        val spanTitle = spanRegex.find(html)?.groupValues?.get(1)?.trim()
        if (spanTitle != null && isValidTitle(spanTitle)) return spanTitle

        // og:title meta
        val ogTitle = extractMeta(html, "og:title")
        if (ogTitle != null && isValidTitle(ogTitle)) return ogTitle

        return null
    }

    private fun extractProductAuthor(html: String): String? {
        // Pattern 1: author byline section
        val bylineRegex = """id="bylineInfo".*?class="author[^"]*"[^>]*>.*?<a[^>]*>([^<]+)</a>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val byline = bylineRegex.find(html)?.groupValues?.get(1)?.trim()
        if (byline != null && isValidAuthor(byline)) return byline

        // Pattern 2: any author contributorNameID
        val contribRegex = """class="contributorNameID"[^>]*>([^<]+)</""".toRegex()
        val contrib = contribRegex.find(html)?.groupValues?.get(1)?.trim()
        if (contrib != null && isValidAuthor(contrib)) return contrib

        // Pattern 3: "by" followed by a link
        val byRegex = """<span[^>]*>\s*by\s*</span>\s*(?:<[^>]+>\s*)*<a[^>]*>([^<]+)</a>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val byAuthor = byRegex.find(html)?.groupValues?.get(1)?.trim()
        if (byAuthor != null && isValidAuthor(byAuthor)) return byAuthor

        return null
    }

    private fun extractProductImage(html: String): String? {
        // High-res image from JavaScript data
        val hiResRegex = """"hiRes"\s*:\s*"(https?://[^"]+)"""".toRegex()
        val hiRes = hiResRegex.find(html)?.groupValues?.get(1)
        if (hiRes != null) return hiRes

        // Large image from colorImages data
        val largeRegex = """"large"\s*:\s*"(https?://[^"]+)"""".toRegex()
        val large = largeRegex.find(html)?.groupValues?.get(1)
        if (large != null) return large

        // Landing image tag
        val landingRegex = """id="landingImage"[^>]*src="(https?://[^"]+)"""".toRegex()
        val landing = landingRegex.find(html)?.groupValues?.get(1)
        if (landing != null) return landing

        // og:image
        val ogImage = extractMeta(html, "og:image")
        if (ogImage != null && ogImage.startsWith("http")) return ogImage

        // imgBlkFront (common image ID)
        val blkFrontRegex = """id="imgBlkFront"[^>]*src="(https?://[^"]+)"""".toRegex()
        return blkFrontRegex.find(html)?.groupValues?.get(1)
    }

    private fun extractProductDescription(html: String): String? {
        // Book description expander
        val expanderRegex = """data-a-expander-name="book_description_expander"[^>]*>(.+?)</div>\s*</div>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val expander = expanderRegex.find(html)?.groupValues?.get(1)
        if (expander != null) {
            val text = cleanAmazonText(stripHtmlTags(expander).trim())
            if (text.length > 20) return text
        }

        // iframeContent for description
        val iframeRegex = """id="iframeContent"[^>]*>(.+?)</div>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val iframe = iframeRegex.find(html)?.groupValues?.get(1)
        if (iframe != null) {
            val text = cleanAmazonText(stripHtmlTags(iframe).trim())
            if (text.length > 20) return text
        }

        val desc = extractMeta(html, "description")
        if (desc != null && desc.length > 20) return cleanAmazonText(desc)

        return null
    }

    /**
     * Comprehensive cleanup of text scraped from Amazon pages.
     * Strips trailing UI artifacts, navigation text, and promotional copy
     * that can leak into descriptions and other metadata fields.
     */
    private fun cleanAmazonText(text: String): String {
        var cleaned = text
        // Trailing UI button/link text
        val trailingPatterns = listOf(
            """\s*Read\s*more\.?\s*$""",
            """\s*Read\s*less\.?\s*$""",
            """\s*See\s*(?:all|more)\s*(?:details|formats|editions)?\.?\s*$""",
            """\s*(?:Click|Tap)\s*(?:to\s*)?(?:look\s*inside|here)\.?\s*$""",
            """\s*Look\s*inside\.?\s*$""",
            """\s*Collapse\s*$""",
            """\s*Expand\s*$""",
            """\s*Report\s*(?:an?\s*)?issue\.?\s*$""",
            """\s*Follow\s*the\s*(?:Author|author)\.?\s*$""",
            """\s*Previous\s*page\.?\s*$""",
            """\s*Next\s*page\.?\s*$""",
            """\s*›\s*(?:Visit|See)\s*(?:Amazon'?s?|the)\s*.+(?:Page|Store)\.?\s*$""",
            """\s*(?:FREE|Prime)\s*(?:delivery|shipping).*$""",
        )
        for (pattern in trailingPatterns) {
            cleaned = cleaned.replace(Regex(pattern, RegexOption.IGNORE_CASE), "")
        }
        // Inline Amazon branding that shouldn't be in metadata
        cleaned = cleaned
            .replace(Regex("""\s*Amazon\.com\s*""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
        return cleaned
    }

    // =====================================================================
    // Metadata extraction from Product Details map + fallback regexes
    // =====================================================================

    /**
     * Extracts page count from the details map or HTML.
     * Amazon labels it differently per format: "Paperback", "Hardcover", "Print length", "Pages".
     */
    private fun extractPageCount(html: String, details: Map<String, String>): Int? {
        // From details map - try format-specific keys and generic keys
        val pageKeys = listOf("Print length", "Paperback", "Hardcover", "Pages",
            "Mass Market Paperback", "Board Book", "Library Binding", "Spiral-bound")
        for (key in pageKeys) {
            val value = details.getByKeys(key)
            if (value != null) {
                val pages = Regex("""(\d{2,4})\s*pages""", RegexOption.IGNORE_CASE)
                    .find(value)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""^(\d{2,4})$""").find(value.trim())?.groupValues?.get(1)?.toIntOrNull()
                if (pages != null) return pages
            }
        }

        // Fallback: "X pages" anywhere in HTML
        val pagesRegex = """(\d{2,4})\s+pages""".toRegex(RegexOption.IGNORE_CASE)
        return pagesRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Extracts language from details map or HTML.
     */
    private fun extractLanguage(html: String, details: Map<String, String>): String? {
        // Details map
        val fromDetails = details.getByKeys("Language")
        if (fromDetails != null && fromDetails.length < 30) return fromDetails

        // Detail bullet fallback
        val bullet = extractDetailBullet(html, "Language")
        if (bullet != null) return bullet

        // Table fallback
        val tableRegex = """<th[^>]*>\s*Language\s*</th>\s*<td[^>]*>\s*([^<]+)</td>"""
            .toRegex(RegexOption.IGNORE_CASE)
        val tableMatch = tableRegex.find(html)?.groupValues?.get(1)?.trim()
        if (tableMatch != null && tableMatch.length < 30) return tableMatch

        return null
    }

    /**
     * Extracts ISBN-10 from details map, HTML, or ASIN.
     */
    private fun extractIsbn10(html: String, asin: String, details: Map<String, String>): String? {
        // Details map
        val fromDetails = details.getByKeys("ISBN-10")
        if (fromDetails != null && fromDetails.matches(Regex("""\d{9}[\dXx]"""))) return fromDetails

        // Detail bullet fallback
        val bullet = extractDetailBullet(html, "ISBN-10")
        if (bullet != null && bullet.matches(Regex("""\d{9}[\dXx]"""))) return bullet

        // Table fallback
        val tableRegex = """<th[^>]*>\s*ISBN-10\s*</th>\s*<td[^>]*>\s*([^<]+)</td>"""
            .toRegex(RegexOption.IGNORE_CASE)
        val tableMatch = tableRegex.find(html)?.groupValues?.get(1)?.trim()
        if (tableMatch != null && tableMatch.matches(Regex("""\d{9}[\dXx]"""))) return tableMatch

        // Physical book ASINs that look like ISBN-10 (10 digits, not starting with B)
        if (!asin.startsWith("B") && asin.matches(Regex("""\d{9}[\dXx]"""))) return asin

        return null
    }

    /**
     * Extracts the ASIN from the product page.
     */
    private fun extractAsin(html: String, fallbackAsin: String): String? {
        // Hidden input: <input type="hidden" name="ASIN" value="...">
        val inputRegex = """<input[^>]*name="ASIN"[^>]*value="([A-Z0-9]{10})"[^>]*/?>""".toRegex()
        val fromInput = inputRegex.find(html)?.groupValues?.get(1)
        if (fromInput != null) return fromInput

        // cerberus-data-metrics div
        val cerberusRegex = """id="cerberus-data-metrics"[^>]*data-asin="([A-Z0-9]{10})"[^>]*/?>""".toRegex()
        val fromCerberus = cerberusRegex.find(html)?.groupValues?.get(1)
        if (fromCerberus != null) return fromCerberus

        // Detail bullet: "ASIN : B0..."
        val bullet = extractDetailBullet(html, "ASIN")
        if (bullet != null && bullet.matches(Regex("""[A-Z0-9]{10}"""))) return bullet

        // Use the ASIN we navigated to
        return fallbackAsin
    }

    /**
     * Extracts edition info from details map, HTML, or title.
     * Examples: "1st Edition", "Deluxe Edition", "Revised & Updated"
     */
    private fun extractEdition(html: String, title: String, details: Map<String, String>): String? {
        // Details map
        val fromDetails = details.getByKeys("Edition")
        if (fromDetails != null && fromDetails.length < 50) return fromDetails

        // Detail bullet fallback
        val bullet = extractDetailBullet(html, "Edition")
        if (bullet != null && bullet.length < 50) return bullet

        // Table fallback
        val tableRegex = """<th[^>]*>\s*Edition\s*</th>\s*<td[^>]*>\s*([^<]+)</td>"""
            .toRegex(RegexOption.IGNORE_CASE)
        val tableMatch = tableRegex.find(html)?.groupValues?.get(1)?.trim()
        if (tableMatch != null && tableMatch.length < 50) return tableMatch

        // Publisher field sometimes contains edition: "Publisher; 1st edition (date)"
        val publisherRaw = details.getByKeys("Publisher")
        if (publisherRaw != null) {
            val editionFromPublisher = Regex(""";\s*(.+?edition)""", RegexOption.IGNORE_CASE)
                .find(publisherRaw)?.groupValues?.get(1)?.trim()
            if (editionFromPublisher != null) return editionFromPublisher
        }

        // Extract from title: "Book Title: Deluxe Edition"
        val editionRegex = """(?:(\d+(?:st|nd|rd|th)|Deluxe|Special|Collector['']?s?|Limited|Anniversary|Revised|Updated|Illustrated|Abridged|Unabridged|International|Student)\s+Edition)"""
            .toRegex(RegexOption.IGNORE_CASE)
        val fromTitle = editionRegex.find(title)?.groupValues?.get(0)?.trim()
        if (fromTitle != null) return fromTitle

        return null
    }

    // =====================================================================
    // Genre/Category extraction - comprehensive
    // =====================================================================

    /**
     * Extracts ALL genres/categories from every available source on the page:
     * 1. Breadcrumbs (wayfinding-breadcrumbs section)
     * 2. BSR subcategories (zg_hrsr_ladder spans - most comprehensive)
     * 3. BSR inline "#N in Category" links
     * 4. Legacy SalesRank section
     * 5. Horizontal breadcrumb lists (a-horizontal class)
     *
     * Returns a comma-separated string of unique genres.
     */
    private fun extractAllGenres(html: String): String? {
        val genres = mutableSetOf<String>()

        // 1. Breadcrumbs from wayfinding-breadcrumbs section
        val breadcrumbSection = extractHtmlSection(html, "wayfinding-breadcrumbs", 2000)
        if (breadcrumbSection != null) {
            val linkRegex = """<a[^>]*>\s*([^<]+?)\s*</a>""".toRegex()
            for (match in linkRegex.findAll(breadcrumbSection)) {
                val cat = match.groupValues[1].trim()
                if (isValidGenre(cat)) genres.add(cat)
            }
        }

        // 2. BSR subcategories using zg_hrsr_ladder spans (most comprehensive)
        // Structure: <span class="zg_hrsr_ladder">in <a>Genre</a> > <a>Subgenre</a></span>
        val bsrLadderRegex = """class="zg_hrsr_ladder"[^>]*>(.*?)</span>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in bsrLadderRegex.findAll(html)) {
            val ladder = match.groupValues[1]
            // Extract all linked categories within each ladder
            val linkRegex = """<a[^>]*>\s*([^<]+?)\s*</a>""".toRegex()
            for (linkMatch in linkRegex.findAll(ladder)) {
                val cat = linkMatch.groupValues[1].trim()
                if (isValidGenre(cat)) genres.add(cat)
            }
            // Also extract any unlinked category text between delimiters
            val plainText = stripHtmlTags(ladder)
            for (part in plainText.split(Regex("""[>›»]"""))) {
                val cat = part
                    .replace(Regex("""^(?:in\s+|&gt;\s*)""", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (isValidGenre(cat)) genres.add(cat)
            }
        }

        // 3. BSR inline "#N in <a>Category</a>" for pages without zg_hrsr
        val bsrInlineRegex = """#[\d,]+\s+in\s+<a[^>]*>\s*([^<]+?)\s*</a>""".toRegex()
        for (match in bsrInlineRegex.findAll(html)) {
            val cat = match.groupValues[1].trim()
            if (isValidGenre(cat)) genres.add(cat)
        }

        // 4. Legacy SalesRank section (older Amazon layout)
        val salesRankIndex = html.indexOf("id=\"SalesRank\"")
        if (salesRankIndex >= 0) {
            val salesSection = html.substring(salesRankIndex, (salesRankIndex + 3000).coerceAtMost(html.length))
            val linkRegex = """<a[^>]*>\s*([^<]+?)\s*</a>""".toRegex()
            for (match in linkRegex.findAll(salesSection)) {
                val cat = match.groupValues[1].trim()
                if (isValidGenre(cat)) genres.add(cat)
            }
        }

        // 5. Horizontal breadcrumb links (a-horizontal class, used on some pages)
        val breadcrumbListRegex = """<ul[^>]*class="[^"]*a-horizontal[^"]*"[^>]*>(.*?)</ul>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in breadcrumbListRegex.findAll(html)) {
            val linkRegex = """<a[^>]*>\s*([^<]+?)\s*</a>""".toRegex()
            for (linkMatch in linkRegex.findAll(match.groupValues[1])) {
                val cat = linkMatch.groupValues[1].trim()
                if (isValidGenre(cat)) genres.add(cat)
            }
        }

        // 6. Category tree links pattern: "in <a>Genre</a> > <a>Subgenre</a>"
        // (catches categories in BSR text that aren't in zg_hrsr_ladder spans)
        val categoryTreeRegex = """in\s+<a[^>]*>([^<]+)</a>\s*(?:(?:&gt;|>|›)\s*<a[^>]*>([^<]+)</a>\s*)*"""
            .toRegex(RegexOption.IGNORE_CASE)
        for (match in categoryTreeRegex.findAll(html)) {
            val cat = match.groupValues[1].trim()
            if (isValidGenre(cat)) genres.add(cat)
            // The second capture group only gets the last subcategory due to regex limitations
            val subCat = match.groupValues[2].trim()
            if (subCat.isNotBlank() && isValidGenre(subCat)) genres.add(subCat)
        }

        if (genres.isEmpty()) return null
        val result = genres.take(15).joinToString(", ")
        DebugLog.d(TAG, "Genres (${genres.size}): $result")
        return result
    }

    /**
     * Verifies that the product page's ISBN matches the one we searched for.
     * Checks ISBN-13 and ISBN-10 from the page's detail fields and HTML content.
     * Returns true if we can confirm a match OR if we can't find any ISBN on the page
     * (some pages don't show ISBNs, and we don't want to discard them).
     */
    private fun verifyIsbnMatch(html: String, searchedIsbn: String, asin: String, details: Map<String, String>): Boolean {
        val pageIsbn13 = details.getByKeys("ISBN-13")
            ?: extractDetailBullet(html, "ISBN-13")
        val pageIsbn10 = details.getByKeys("ISBN-10")
            ?: extractDetailBullet(html, "ISBN-10")

        // If we can't find ANY ISBN on the page, allow it (some pages don't show ISBNs)
        if (pageIsbn13 == null && pageIsbn10 == null) return true

        // Clean ISBNs for comparison (remove hyphens, spaces)
        val cleanSearched = searchedIsbn.replace(Regex("""[-\s]"""), "")

        if (pageIsbn13 != null) {
            val cleanPage13 = pageIsbn13.replace(Regex("""[-\s]"""), "")
            if (cleanPage13 == cleanSearched) return true
        }

        if (pageIsbn10 != null) {
            val cleanPage10 = pageIsbn10.replace(Regex("""[-\s]"""), "")
            if (cleanPage10 == cleanSearched) return true
            // If we searched ISBN-13, check if the page's ISBN-10 is the converted form
            if (cleanSearched.length == 13 && cleanSearched.startsWith("978")) {
                val convertedIsbn10 = convertIsbn13ToIsbn10(cleanSearched)
                if (convertedIsbn10 != null && cleanPage10 == convertedIsbn10) return true
            }
        }

        // ASIN that looks like ISBN-10 can also match
        if (!asin.startsWith("B") && asin.length == 10) {
            if (asin == cleanSearched) return true
            if (cleanSearched.length == 13 && cleanSearched.startsWith("978")) {
                val convertedIsbn10 = convertIsbn13ToIsbn10(cleanSearched)
                if (convertedIsbn10 != null && asin == convertedIsbn10) return true
            }
        }

        DebugLog.w(TAG, "ISBN mismatch: searched=$cleanSearched, page13=$pageIsbn13, page10=$pageIsbn10, asin=$asin")
        return false
    }

    /** Converts ISBN-13 (978 prefix) to ISBN-10. */
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

    /** Validates a genre/category name. Filters out generic labels and noise. */
    private fun isValidGenre(text: String): Boolean {
        val lower = text.lowercase().trim()
        return text.length > 2 &&
            lower != "books" &&
            lower != "kindle store" &&
            lower != "kindle ebooks" &&
            lower != "audible books & originals" &&
            !lower.startsWith("see top") &&
            !lower.startsWith("see all") &&
            !lower.contains("amazon") &&
            !lower.startsWith(">") &&
            !lower.startsWith("http") &&
            !lower.matches(Regex("""#\d+.*""")) &&
            !lower.matches(Regex("""\d+"""))
    }

    // =====================================================================
    // Series extraction
    // =====================================================================

    /**
     * Extracts series info from Amazon title tag parts.
     * Looks for patterns like "A Novel (The Maple Hills)" or "(Series Name, #2)"
     */
    private fun extractSeriesFromTitleParts(parts: List<String>?): Pair<String, String?>? {
        if (parts == null) return null
        for (part in parts) {
            val seriesMatch = extractSeriesFromText(part)
            if (seriesMatch != null) return seriesMatch
        }
        return null
    }

    /**
     * Extracts series name and number from text patterns like:
     * "A Novel (The Maple Hills)" -> "The Maple Hills"
     * "(Series Name, #2)" -> "Series Name" / "2"
     * "(The Series Book 3)" -> "The Series" / "3"
     */
    private fun extractSeriesFromText(text: String): Pair<String, String?>? {
        // Pattern: (Series Name, #N) or (Series Name Book N)
        val numberedRegex = """\(([^)]+?)(?:,\s*#?|,?\s+(?:Book|Volume|Vol\.?|#)\s*)(\d+)\)""".toRegex(RegexOption.IGNORE_CASE)
        numberedRegex.find(text)?.let {
            return Pair(it.groupValues[1].trim(), it.groupValues[2])
        }

        // Pattern: "A Novel (Series Name)" or just "(Series Name)"
        // Accept parentheticals as series unless they match known non-series patterns
        val parenRegex = """\(([^)]{3,50})\)""".toRegex()
        parenRegex.find(text)?.let { match ->
            val candidate = match.groupValues[1].trim()
            val c = candidate.lowercase()
            // Reject known non-series parentheticals
            val nonSeriesPatterns = listOf(
                "a novel", "a thriller", "a memoir", "a romance", "a mystery",
                "a fantasy", "a story", "novel", "reprint", "mass market",
                "paperback", "hardcover", "audio", "audiobook", "large print",
                "illustrated", "abridged", "unabridged", "revised", "updated",
                "expanded", "annotated", "kindle", "ebook", "e-book",
                "special edition", "anniversary edition", "collector's edition",
                "deluxe edition", "limited edition", "first edition"
            )
            val formatKeywords = listOf(
                "audible", "audio", "kindle", "ebook", "e-book", "paperback",
                "hardcover", "hardback", "edition", "mass market", "board book",
                "library binding", "cd", "mp3", "digital"
            )
            val containsFormat = formatKeywords.any { c.contains(it) }
            val isNotSeries = containsFormat || nonSeriesPatterns.any { pattern ->
                c == pattern || c.startsWith("$pattern ") || c.startsWith("a ") && c.endsWith(" novel")
            }
            if (isNotSeries) return null
            // Also reject if it's just a single common word (genre, format, etc.)
            if (c.split(" ").size == 1 && c in listOf("novel", "fiction", "nonfiction", "memoir", "thriller", "romance", "mystery", "fantasy")) return null

            // "A Novel of The Maple Hills" -> "The Maple Hills"
            val cleaned = candidate
                .replace(Regex("""^A\s+\w+\s+(?:of\s+|in\s+)?(?:the\s+)?""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { candidate }

            // Extract number if present: "The Maple Hills Series 2" -> "The Maple Hills Series" / "2"
            val numRegex = """^(.+?)\s+(\d+)$""".toRegex()
            numRegex.find(cleaned)?.let {
                return Pair(it.groupValues[1].trim(), it.groupValues[2])
            }

            return Pair(cleaned, null)
        }
        return null
    }

    /**
     * Extracts series info from Amazon product page HTML.
     * Looks for the series link section on the page.
     * @param knownSeriesName If we already found the series name (from title tag),
     *   use it to search for the number nearby in the HTML.
     */
    private fun extractSeriesFromHtml(html: String, knownSeriesName: String? = null): Pair<String, String?>? {
        val bookNumRegex = """(?:Book|Volume|Part)\s+(\d+)""".toRegex(RegexOption.IGNORE_CASE)

        // Pattern 0: JSON-LD structured data (most reliable when present)
        val jsonLdRegex = """<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in jsonLdRegex.findAll(html)) {
            val json = match.groupValues[1]
            val seriesNameJson = """"name"\s*:\s*"([^"]+)"""".toRegex()
            val isPartOf = json.indexOf("isPartOf")
            if (isPartOf >= 0) {
                val partOfSection = json.substring(isPartOf).take(500)
                val name = seriesNameJson.find(partOfSection)?.groupValues?.get(1)
                val pos = """"position"\s*:\s*"?(\d+)"?""".toRegex().find(partOfSection)?.groupValues?.get(1)
                    ?: """"position"\s*:\s*"?(\d+)"?""".toRegex().find(json)?.groupValues?.get(1)
                if (name != null) {
                    DebugLog.d(TAG, "Series from JSON-LD: '$name' #$pos")
                    return Pair(name, pos)
                }
            }
        }

        // Pattern 1: seriesTitle section
        val seriesSection = html.substringAfter("seriesTitle", "").take(500)
        if (seriesSection.isNotEmpty()) {
            val seriesLinkRegex = """<a[^>]*>([^<]+)</a>""".toRegex()
            val seriesName = seriesLinkRegex.find(seriesSection)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            val seriesNumber = bookNumRegex.find(seriesSection)?.groupValues?.get(1)
            if (seriesName != null) {
                DebugLog.d(TAG, "Series from seriesTitle: '$seriesName' #$seriesNumber")
                return Pair(seriesName, seriesNumber)
            }
        }

        // Pattern 2: "Part of: <a>Series Name</a>" or "Series: <a>...</a>"
        val partOfRegex = """(?:Part of|Series):\s*<a[^>]*>([^<]+)</a>""".toRegex(RegexOption.IGNORE_CASE)
        val partOf = partOfRegex.find(html)?.groupValues?.get(1)?.trim()
        if (partOf != null) {
            val num = bookNumRegex.find(html.substringAfter(partOf, "").take(200))?.groupValues?.get(1)
            DebugLog.d(TAG, "Series from Part-of: '$partOf' #$num")
            return Pair(partOf, num)
        }

        // Pattern 3: Detail bullets with series info
        val seriesBullet = extractDetailBullet(html, "Series")
        if (seriesBullet != null) {
            val numInBullet = """\((?:Book|Vol\.?|#)\s*(\d+)\)""".toRegex(RegexOption.IGNORE_CASE)
                .find(seriesBullet)?.groupValues?.get(1)
            val name = seriesBullet.replace("""\s*\([^)]*\)""".toRegex(), "").trim()
            val formatKeywords = listOf(
                "audible", "audio", "kindle", "ebook", "e-book", "paperback",
                "hardcover", "hardback", "edition", "mass market", "board book",
                "library binding", "spiral", "cd", "mp3", "digital"
            )
            val nameLower = name.lowercase()
            val isFormat = formatKeywords.any { nameLower.contains(it) }
            if (name.isNotBlank() && !isFormat) {
                DebugLog.d(TAG, "Series from bullet: '$name' #$numInBullet")
                return Pair(name, numInBullet)
            }
            if (isFormat) {
                DebugLog.d(TAG, "Series bullet '$name' rejected as format/edition info")
            }
        }

        // Pattern 4: "Book N of N: <a>Series Name</a>" inline format
        val bookOfLinkRegex = """Book\s+(\d+)\s+of\s+\d+\s*:?\s*<a[^>]*>([^<]+)</a>""".toRegex(RegexOption.IGNORE_CASE)
        bookOfLinkRegex.find(html)?.let {
            val num = it.groupValues[1]
            val name = it.groupValues[2].trim()
            DebugLog.d(TAG, "Series from 'Book N of M: Link': '$name' #$num")
            return Pair(name, num)
        }

        // Pattern 5: If we know the series name, search for "Book N" nearby
        if (knownSeriesName != null) {
            var searchFrom = 0
            var occurrence = 0
            while (searchFrom < html.length) {
                val nameIndex = html.indexOf(knownSeriesName, searchFrom, ignoreCase = true)
                if (nameIndex < 0) break
                occurrence++
                val start = (nameIndex - 300).coerceAtLeast(0)
                val end = (nameIndex + knownSeriesName.length + 300).coerceAtMost(html.length)
                val window = html.substring(start, end)
                if (occurrence <= 3) {
                    val stripped = stripHtmlTags(window).replace(Regex("\\s+"), " ").take(200)
                    DebugLog.d(TAG, "Series context #$occurrence: $stripped")
                }
                val num = bookNumRegex.find(window)?.groupValues?.get(1)
                if (num != null) {
                    DebugLog.d(TAG, "Series number from near '$knownSeriesName': #$num")
                    return Pair(knownSeriesName, num)
                }
                searchFrom = nameIndex + knownSeriesName.length
            }
            if (occurrence == 0) {
                DebugLog.d(TAG, "Series name '$knownSeriesName' not found in HTML")
            } else {
                DebugLog.d(TAG, "Found '$knownSeriesName' $occurrence time(s) but no book number nearby")
            }
        }

        return null
    }

    // =====================================================================
    // Format detection
    // =====================================================================

    private fun isNonPhysicalFormat(html: String): Boolean {
        val titleRegex = """<title[^>]*>([^<]+)</title>""".toRegex(RegexOption.IGNORE_CASE)
        val rawTitle = titleRegex.find(html)?.groupValues?.get(1) ?: ""
        val titleLower = rawTitle.lowercase()
        if (titleLower.contains("kindle") || titleLower.contains("ebook")) return true
        if (titleLower.contains("audible") || titleLower.contains("audiobook") || titleLower.contains("audio cd")) return true

        if (html.contains("kindle-price", ignoreCase = true)) return true
        if (html.contains("a]Kindle", ignoreCase = true)) return true
        if (html.contains("audible_", ignoreCase = true)) return true
        if (html.contains("a]Audible", ignoreCase = true)) return true

        val selectedFormatRegex = """class="a-button-selected"[^>]*>.*?<span[^>]*>([^<]+)</span>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val selectedFormat = selectedFormatRegex.find(html)?.groupValues?.get(1)?.trim()?.lowercase()
        if (selectedFormat != null && (selectedFormat.contains("kindle") || selectedFormat.contains("ebook"))) return true
        if (selectedFormat != null && (selectedFormat.contains("audible") || selectedFormat.contains("audiobook") || selectedFormat.contains("audio cd"))) return true

        val binding = extractDetailBullet(html, "Binding")
            ?: extractDetailBullet(html, "Format")
        if (binding != null) {
            val bindingLower = binding.lowercase()
            if (bindingLower.contains("kindle") || bindingLower.contains("ebook")) return true
            if (bindingLower.contains("audible") || bindingLower.contains("audiobook") || bindingLower.contains("audio cd")) return true
        }

        val asinRegex = """<input[^>]*name="ASIN"[^>]*value="(B[A-Z0-9]{9})"[^>]*/?>""".toRegex()
        val kindleAsinInTitle = rawTitle.contains("Kindle Edition", ignoreCase = true)
        if (asinRegex.find(html) != null && !html.contains("pages", ignoreCase = true)) return true

        return kindleAsinInTitle
    }

    /**
     * Validates that a product page is actually a book, not a non-book product
     * (credit cards, electronics, etc.) that can appear in Amazon search results.
     */
    private fun isBookProductPage(html: String, details: Map<String, String>): Boolean {
        // Check if details contain any book-specific keys
        val bookKeys = listOf(
            "ISBN-13", "ISBN-10", "Publisher", "Imprint", "Print length",
            "Paperback", "Hardcover", "Pages", "Mass Market Paperback",
            "Board Book", "Library Binding", "Reading age", "Lexile measure"
        )
        if (details.keys.any { key -> bookKeys.any { it.equals(key, ignoreCase = true) } }) {
            return true
        }

        // Check if <title> tag ends with ": Books" (standard Amazon book page pattern)
        val titleRegex = """<title[^>]*>([^<]+)</title>""".toRegex(RegexOption.IGNORE_CASE)
        val titleTag = titleRegex.find(html)?.groupValues?.get(1) ?: ""
        if (titleTag.trimEnd().endsWith(": Books") || titleTag.contains(": Books:")) {
            return true
        }

        // Check breadcrumbs for "Books" category
        val breadcrumbs = extractHtmlSection(html, "wayfinding-breadcrumbs", 2000)
        if (breadcrumbs != null && breadcrumbs.contains(">Books<", ignoreCase = true)) {
            return true
        }

        return false
    }

    private fun extractFormat(html: String): String? {
        val selectedFormatRegex = """class="a-button-selected"[^>]*>.*?<span[^>]*>([^<]+)</span>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val selected = selectedFormatRegex.find(html)?.groupValues?.get(1)?.trim()
        if (selected != null && selected.lowercase().let {
            it.contains("hardcover") || it.contains("paperback") ||
                it.contains("mass market") || it.contains("board book") ||
                it.contains("library binding") || it.contains("spiral")
        }) return selected

        val binding = extractDetailBullet(html, "Binding")
            ?: extractDetailBullet(html, "Format")
        if (binding != null && !binding.lowercase().let {
            it.contains("kindle") || it.contains("ebook") ||
                it.contains("audible") || it.contains("audiobook") || it.contains("audio cd")
        }) return binding

        val titleRegex = """<title[^>]*>([^<]+)</title>""".toRegex(RegexOption.IGNORE_CASE)
        val title = titleRegex.find(html)?.groupValues?.get(1)?.lowercase() ?: ""
        return when {
            title.contains("hardcover") -> "Hardcover"
            title.contains("paperback") -> "Paperback"
            title.contains("mass market") -> "Mass Market Paperback"
            title.contains("board book") -> "Board Book"
            else -> null
        }
    }

    // =====================================================================
    // Utility methods
    // =====================================================================

    private fun isValidTitle(text: String): Boolean {
        val lower = text.lowercase()
        return text.length >= 2 &&
            !lower.contains("amazon") &&
            !text.matches(Regex("[a-zA-Z]+[A-Z][a-zA-Z]+")) && // camelCase JS vars
            !lower.startsWith("http") &&
            text.contains(" ") // real titles have spaces
    }

    private fun isValidAuthor(text: String): Boolean {
        val lower = text.lowercase().trim()
        return text.length >= 2 &&
            !lower.contains("see all") &&
            !lower.contains("details") &&
            !lower.contains("format") &&
            !lower.contains("edition") &&
            !lower.contains("learn more") &&
            !lower.contains("click here") &&
            !lower.contains("amazon") &&
            !lower.contains("novel") &&
            !lower.contains("hardcover") &&
            !lower.contains("paperback") &&
            !lower.startsWith("a ") &&
            !lower.startsWith("@") &&
            !lower.startsWith("http") &&
            !lower.startsWith("(") && // parenthetical like "(The Series)"
            // Filter placeholder text (Amazon pre-release books)
            !lower.contains("to be confirmed") &&
            !lower.contains("to be announced") &&
            lower != "tbd" && lower != "tba" && lower != "tbc" &&
            !lower.contains("forthcoming") &&
            !lower.contains("pre-order") &&
            // Filter publisher names that leak into author fields
            !isPublisherName(lower)
    }

    /** Checks if text matches a known publisher name or imprint. */
    private fun isPublisherName(lower: String): Boolean {
        val publishers = listOf(
            "atria", "penguin", "harpercollins", "harper collins", "simon & schuster",
            "simon and schuster", "random house", "hachette", "macmillan", "scholastic",
            "bloomsbury", "vintage", "knopf", "doubleday", "bantam", "dell",
            "ace books", "ace", "berkley", "putnam", "dutton", "plume",
            "avon", "mira", "harlequin", "tor", "daw", "baen", "orbit",
            "gallery", "pocket books", "scribner", "little brown", "little, brown",
            "grand central", "forever", "st. martin", "st martin",
            "william morrow", "piatkus", "hodder", "headline", "orion",
            "pan macmillan", "transworld", "cornerstone", "century"
        )
        return publishers.any { lower == it || lower == "$it books" || lower == "$it press" }
    }

    private fun extractMeta(html: String, property: String): String? {
        val regex = """<meta[^>]*(?:property|name)="$property"[^>]*content="([^"]*)"[^>]*/?>"""
            .toRegex(RegexOption.IGNORE_CASE)
        val altRegex = """<meta[^>]*content="([^"]*)"[^>]*(?:property|name)="$property"[^>]*/?>"""
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
            ?: altRegex.find(html)?.groupValues?.get(1)
    }

    private fun extractDetailBullet(html: String, label: String): String? {
        // Match label followed by colon and value, with possible HTML tags/RTL marks between
        val regex = """$label\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*[:\u200F\u200E]\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*([^<]+)"""
            .toRegex(RegexOption.IGNORE_CASE)
        val result = regex.find(html)?.groupValues?.get(1)?.trim()
        // Validate - reject social media handles, URLs, long garbage
        if (result != null && result.length < 100 && !result.startsWith("@") && !result.startsWith("http")) {
            return result
        }
        return null
    }

    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("&quot;"), "\"")
            .replace(Regex("&#39;"), "'")
            .replace(Regex("&lrm;|&rlm;"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\s*:\s*Amazon\.com\s*.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*Amazon\.com\s*.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*-\s*Amazon\.com\s*.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
