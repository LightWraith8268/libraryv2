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
 * by ISBN and parses meta tags / structured data from the HTML.
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

    // --- Search Results Parsing (fallback if product page fails) ---

    private fun parseSearchResults(html: String, isbn: String): Book? {
        val rawTitle = extractSearchTitle(html)
        val author = extractSearchAuthor(html)
        val imageUrl = extractSearchImage(html)

        if (rawTitle == null) {
            DebugLog.d(TAG, "No title found in search results")
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

    // --- Product Page Parsing ---

    private fun parseProductPage(html: String, isbn: String, asin: String): Book? {
        // Skip eBooks / Kindle editions - only want physical books
        if (isEbook(html)) {
            DebugLog.d(TAG, "Skipping eBook/Kindle edition")
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
        val publisher = extractDetailBullet(html, "Publisher")
            ?: extractDetailBullet(html, "Imprint")
        val pages = extractPageCount(html)
        val pubDate = extractDetailBullet(html, "Publication date")
            ?: extractDetailBullet(html, "Publish date")

        // Extract series from multiple sources and combine
        val titleSeries = extractSeriesFromTitleParts(titleTagResult?.third)
        val knownSeriesName = titleSeries?.first?.takeIf { it.isNotBlank() }
        val htmlSeries = extractSeriesFromHtml(html, knownSeriesName)
        // Combine: prefer name from title-tag, number from whichever has it
        val seriesName = knownSeriesName
            ?: htmlSeries?.first?.takeIf { it.isNotBlank() }
        val seriesNumber = titleSeries?.second
            ?: htmlSeries?.second
        val seriesInfo = if (seriesName != null) Pair(seriesName, seriesNumber) else null

        // Strip series parenthetical from title if we extracted it
        if (seriesName != null) {
            title = title
                .replace(Regex("""\s*\(\s*${Regex.escape(seriesName)}(?:\s*(?:Book|Vol\.?|#)\s*\d+)?\s*\)""", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        val format = extractFormat(html)
        val language = extractLanguage(html)
        val genres = extractGenres(html)
        val isbn10 = extractIsbn10(html, asin)
        val detectedAsin = extractAsin(html, asin)
        val edition = extractEdition(html, title)

        DebugLog.d(TAG, "Product page: '$title' by '$author', " +
            "image=${imageUrl != null}, pages=$pages, publisher=$publisher, " +
            "format=$format, series=${seriesInfo?.first} #${seriesInfo?.second}, " +
            "lang=$language, genres=${genres?.take(60)}, isbn10=$isbn10, " +
            "asin=$detectedAsin, edition=$edition")

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
            val text = stripHtmlTags(expander).trim()
            if (text.length > 20) return text
        }

        // iframeContent for description
        val iframeRegex = """id="iframeContent"[^>]*>(.+?)</div>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val iframe = iframeRegex.find(html)?.groupValues?.get(1)
        if (iframe != null) {
            val text = stripHtmlTags(iframe).trim()
            if (text.length > 20) return text
        }

        val desc = extractMeta(html, "description")
        if (desc != null && desc.length > 20) return desc

        return null
    }

    private fun extractPageCount(html: String): Int? {
        // "X pages" pattern in detail section
        val pagesRegex = """(\d{2,4})\s+pages""".toRegex(RegexOption.IGNORE_CASE)
        return pagesRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
    }

    // --- New metadata extraction ---

    /**
     * Extracts language from detail bullets or product information table.
     * Amazon shows this as "Language : English" in the detail section.
     */
    private fun extractLanguage(html: String): String? {
        // Detail bullet pattern
        val bullet = extractDetailBullet(html, "Language")
        if (bullet != null) return bullet

        // Product information table pattern:
        // <th>Language</th><td>English</td>
        val tableRegex = """<th[^>]*>\s*Language\s*</th>\s*<td[^>]*>\s*([^<]+)</td>"""
            .toRegex(RegexOption.IGNORE_CASE)
        val tableMatch = tableRegex.find(html)?.groupValues?.get(1)?.trim()
        if (tableMatch != null && tableMatch.length < 30) return tableMatch

        return null
    }

    /**
     * Extracts genres/categories from Amazon breadcrumbs and Best Sellers Rank.
     * Returns comma-separated genres string.
     */
    private fun extractGenres(html: String): String? {
        val genres = mutableSetOf<String>()

        // Pattern 1: Breadcrumb links (most reliable)
        val breadcrumbRegex = """<ul[^>]*class="[^"]*a-horizontal[^"]*"[^>]*>(.*?)</ul>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in breadcrumbRegex.findAll(html)) {
            val linkRegex = """<a[^>]*>([^<]+)</a>""".toRegex()
            val links = linkRegex.findAll(match.groupValues[1])
                .map { it.groupValues[1].trim() }
                .filter { link ->
                    link.length > 2 &&
                        !link.equals("Books", ignoreCase = true) &&
                        !link.equals("Kindle Store", ignoreCase = true) &&
                        !link.equals("Kindle eBooks", ignoreCase = true) &&
                        !link.equals("See Top 100", ignoreCase = true) &&
                        !link.contains("Amazon", ignoreCase = true) &&
                        !link.startsWith(">")
                }
                .toList()
            genres.addAll(links)
        }

        // Pattern 2: Best Sellers Rank category links "#N in Category"
        val bsrRegex = """#\d+\s+in\s+<a[^>]*>([^<]+)</a>""".toRegex()
        for (match in bsrRegex.findAll(html)) {
            val cat = match.groupValues[1].trim()
            if (cat.length > 2 && !cat.equals("Books", ignoreCase = true)) {
                genres.add(cat)
            }
        }

        // Pattern 3: Category tree links "in <a>Genre</a> > <a>Subgenre</a>"
        val categoryRegex = """in\s+<a[^>]*>([^<]+)</a>\s*(?:>\s*<a[^>]*>([^<]+)</a>)*"""
            .toRegex(RegexOption.IGNORE_CASE)
        for (match in categoryRegex.findAll(html)) {
            val cat = match.groupValues[1].trim()
            if (cat.length > 2 && !cat.equals("Books", ignoreCase = true) &&
                !cat.contains("Amazon", ignoreCase = true)) {
                genres.add(cat)
            }
            val subCat = match.groupValues[2].trim()
            if (subCat.isNotBlank() && subCat.length > 2) {
                genres.add(subCat)
            }
        }

        if (genres.isEmpty()) return null
        val result = genres.take(10).joinToString(", ")
        DebugLog.d(TAG, "Genres: $result")
        return result
    }

    /**
     * Extracts ISBN-10 from detail bullets or from the ASIN if it looks like an ISBN-10.
     * Physical books use their ISBN-10 as the ASIN on Amazon.
     */
    private fun extractIsbn10(html: String, asin: String): String? {
        // Detail bullet: "ISBN-10 : 1234567890"
        val bullet = extractDetailBullet(html, "ISBN-10")
        if (bullet != null && bullet.matches(Regex("""\d{9}[\dXx]"""))) return bullet

        // Product information table
        val tableRegex = """<th[^>]*>\s*ISBN-10\s*</th>\s*<td[^>]*>\s*([^<]+)</td>"""
            .toRegex(RegexOption.IGNORE_CASE)
        val tableMatch = tableRegex.find(html)?.groupValues?.get(1)?.trim()
        if (tableMatch != null && tableMatch.matches(Regex("""\d{9}[\dXx]"""))) return tableMatch

        // Physical book ASINs that look like ISBN-10 (10 digits, not starting with B)
        if (!asin.startsWith("B") && asin.matches(Regex("""\d{9}[\dXx]"""))) return asin

        return null
    }

    /**
     * Extracts the ASIN from the product page. For physical books this is often
     * the ISBN-10, but for some editions it's a separate B-ASIN.
     */
    private fun extractAsin(html: String, fallbackAsin: String): String? {
        // Hidden input: <input type="hidden" name="ASIN" value="...">
        val inputRegex = """<input[^>]*name="ASIN"[^>]*value="([A-Z0-9]{10})"[^>]*/?>""".toRegex()
        val fromInput = inputRegex.find(html)?.groupValues?.get(1)
        if (fromInput != null) return fromInput

        // Detail bullet: "ASIN : B0..."
        val bullet = extractDetailBullet(html, "ASIN")
        if (bullet != null && bullet.matches(Regex("""[A-Z0-9]{10}"""))) return bullet

        // Use the ASIN we navigated to
        return fallbackAsin
    }

    /**
     * Extracts edition info from detail bullets or the title.
     * Examples: "1st Edition", "Deluxe Edition", "Revised & Updated"
     */
    private fun extractEdition(html: String, title: String): String? {
        // Detail bullet: "Edition : 1st"
        val bullet = extractDetailBullet(html, "Edition")
        if (bullet != null && bullet.length < 50) return bullet

        // Product information table
        val tableRegex = """<th[^>]*>\s*Edition\s*</th>\s*<td[^>]*>\s*([^<]+)</td>"""
            .toRegex(RegexOption.IGNORE_CASE)
        val tableMatch = tableRegex.find(html)?.groupValues?.get(1)?.trim()
        if (tableMatch != null && tableMatch.length < 50) return tableMatch

        // Extract from title: "Book Title: Deluxe Edition"
        val editionRegex = """(?:(\d+(?:st|nd|rd|th)|Deluxe|Special|Collector['']?s?|Limited|Anniversary|Revised|Updated|Illustrated|Abridged|Unabridged|International|Student)\s+Edition)"""
            .toRegex(RegexOption.IGNORE_CASE)
        val fromTitle = editionRegex.find(title)?.groupValues?.get(0)?.trim()
        if (fromTitle != null) return fromTitle

        return null
    }

    // --- Series extraction ---

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
        val parenRegex = """\(([^)]{3,50})\)""".toRegex()
        parenRegex.find(text)?.let { match ->
            val candidate = match.groupValues[1].trim()
            // Filter out things that aren't series names
            if (!candidate.lowercase().let { c ->
                c.startsWith("a novel") || c.startsWith("the ") ||
                    c.contains("series") || c.contains("saga") ||
                    c.contains("book ") || c.contains("trilogy")
                || c.all { ch -> ch.isLetter() || ch.isWhitespace() || ch == '\'' }
            }) return null

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
            val seriesName = seriesLinkRegex.find(seriesSection)?.groupValues?.get(1)?.trim()
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
            if (name.isNotBlank()) {
                DebugLog.d(TAG, "Series from bullet: '$name' #$numInBullet")
                return Pair(name, numInBullet)
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

    // --- Format detection ---

    private fun isEbook(html: String): Boolean {
        val titleRegex = """<title[^>]*>([^<]+)</title>""".toRegex(RegexOption.IGNORE_CASE)
        val rawTitle = titleRegex.find(html)?.groupValues?.get(1) ?: ""
        val titleLower = rawTitle.lowercase()
        if (titleLower.contains("kindle") || titleLower.contains("ebook")) return true

        if (html.contains("kindle-price", ignoreCase = true)) return true
        if (html.contains("a]Kindle", ignoreCase = true)) return true

        val selectedFormatRegex = """class="a-button-selected"[^>]*>.*?<span[^>]*>([^<]+)</span>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val selectedFormat = selectedFormatRegex.find(html)?.groupValues?.get(1)?.trim()?.lowercase()
        if (selectedFormat != null && (selectedFormat.contains("kindle") || selectedFormat.contains("ebook"))) return true

        val binding = extractDetailBullet(html, "Binding")
            ?: extractDetailBullet(html, "Format")
        if (binding != null) {
            val bindingLower = binding.lowercase()
            if (bindingLower.contains("kindle") || bindingLower.contains("ebook")) return true
        }

        val asinRegex = """<input[^>]*name="ASIN"[^>]*value="(B[A-Z0-9]{9})"[^>]*/?>""".toRegex()
        val kindleAsinInTitle = rawTitle.contains("Kindle Edition", ignoreCase = true)
        if (asinRegex.find(html) != null && !html.contains("pages", ignoreCase = true)) return true

        return kindleAsinInTitle
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
            it.contains("kindle") || it.contains("ebook")
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

    // --- Utility methods ---

    private fun isValidTitle(text: String): Boolean {
        val lower = text.lowercase()
        return text.length >= 2 &&
            !lower.contains("amazon") &&
            !text.matches(Regex("[a-zA-Z]+[A-Z][a-zA-Z]+")) && // camelCase JS vars
            !lower.startsWith("http") &&
            text.contains(" ") // real titles have spaces
    }

    private fun isValidAuthor(text: String): Boolean {
        val lower = text.lowercase()
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
            !lower.startsWith("(") // parenthetical like "(The Series)"
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
