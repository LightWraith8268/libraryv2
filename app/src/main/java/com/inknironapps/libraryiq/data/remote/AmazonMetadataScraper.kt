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

            // Extract ASIN from first search result link
            val asin = extractFirstAsin(html)
            if (asin != null) {
                DebugLog.d(TAG, "Found ASIN: $asin, fetching product page")
                val productBook = fetchProductPage(asin, isbn)
                if (productBook != null) return@withContext productBook
            }

            // Fallback: parse search results directly
            parseSearchResults(html, isbn)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Lookup failed for $isbn", e)
            null
        }
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
        return parseProductPage(html, isbn)
    }

    private fun extractFirstAsin(html: String): String? {
        val dpRegex = """/dp/([A-Z0-9]{10})""".toRegex()
        return dpRegex.find(html)?.groupValues?.get(1)
    }

    // --- Search Results Parsing (fallback if product page fails) ---

    private fun parseSearchResults(html: String, isbn: String): Book? {
        val title = extractSearchTitle(html)
        val author = extractSearchAuthor(html)
        val imageUrl = extractSearchImage(html)

        if (title == null) {
            DebugLog.d(TAG, "No title found in search results")
            return null
        }

        DebugLog.d(TAG, "Search parsed: '$title' by '$author', image=${imageUrl != null}")

        return Book(
            title = cleanTitle(title),
            author = author ?: "Unknown Author",
            isbn = isbn,
            coverUrl = imageUrl
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

    private fun parseProductPage(html: String, isbn: String): Book? {
        // Most reliable: parse <title> tag
        // Format: "Book Title: Author Last, First: 9781234567890: Amazon.com: Books"
        val titleTagResult = parseHtmlTitleTag(html)

        val title = titleTagResult?.first
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

        DebugLog.d(TAG, "Product page: '$title' by '$author', " +
            "image=${imageUrl != null}, pages=$pages, publisher=$publisher, " +
            "series=${seriesInfo?.first} #${seriesInfo?.second}")

        return Book(
            title = cleanTitle(title),
            author = author ?: "Unknown Author",
            isbn = isbn,
            description = description,
            coverUrl = imageUrl,
            pageCount = pages,
            publisher = publisher,
            publishedDate = pubDate,
            series = seriesInfo?.first,
            seriesNumber = seriesInfo?.second
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
     * "A Novel (The Maple Hills)" → "The Maple Hills"
     * "(Series Name, #2)" → "Series Name" / "2"
     * "(The Series Book 3)" → "The Series" / "3"
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

            // "A Novel of The Maple Hills" → "The Maple Hills"
            val cleaned = candidate
                .replace(Regex("""^A\s+\w+\s+(?:of\s+|in\s+)?(?:the\s+)?""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { candidate }

            // Extract number if present: "The Maple Hills Series 2" → "The Maple Hills Series" / "2"
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

        // Pattern 1: seriesTitle section (common on Amazon book pages)
        // e.g., <div id="seriesTitle">Book 4 of 5: <a href="...">The Maple Hills</a></div>
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

        // Pattern 2: "Part of: <a>Series Name</a> (N books)" or "Series: ..."
        val partOfRegex = """(?:Part of|Series):\s*<a[^>]*>([^<]+)</a>""".toRegex(RegexOption.IGNORE_CASE)
        val partOf = partOfRegex.find(html)?.groupValues?.get(1)?.trim()
        if (partOf != null) {
            val num = bookNumRegex.find(html.substringAfter(partOf, "").take(200))?.groupValues?.get(1)
            DebugLog.d(TAG, "Series from Part-of: '$partOf' #$num")
            return Pair(partOf, num)
        }

        // Pattern 3: Breadcrumb or detail bullets with series info
        val seriesBullet = extractDetailBullet(html, "Series")
        if (seriesBullet != null) {
            // May be "The Maple Hills (Book 4)" or just "The Maple Hills"
            val numInBullet = """\((?:Book|Vol\.?|#)\s*(\d+)\)""".toRegex(RegexOption.IGNORE_CASE)
                .find(seriesBullet)?.groupValues?.get(1)
            val name = seriesBullet.replace("""\s*\([^)]*\)""".toRegex(), "").trim()
            if (name.isNotBlank()) {
                DebugLog.d(TAG, "Series from bullet: '$name' #$numInBullet")
                return Pair(name, numInBullet)
            }
        }

        // Pattern 4: "#N in <a>Series Name</a>" anywhere in the page
        val hashInRegex = """#(\d+)\s+in\s+<a[^>]*>([^<]+)</a>""".toRegex(RegexOption.IGNORE_CASE)
        hashInRegex.find(html)?.let {
            val num = it.groupValues[1]
            val name = it.groupValues[2].trim()
            DebugLog.d(TAG, "Series from #N-in pattern: '$name' #$num")
            return Pair(name, num)
        }

        // Pattern 5: If we know the series name, look for "Book N" near it in HTML
        if (knownSeriesName != null) {
            // Search for series name in HTML, then look for "Book N" within 300 chars
            val nameIndex = html.indexOf(knownSeriesName, ignoreCase = true)
            if (nameIndex >= 0) {
                // Look in a window around the series name mention
                val start = (nameIndex - 200).coerceAtLeast(0)
                val end = (nameIndex + knownSeriesName.length + 200).coerceAtMost(html.length)
                val window = html.substring(start, end)
                val num = bookNumRegex.find(window)?.groupValues?.get(1)
                if (num != null) {
                    DebugLog.d(TAG, "Series number from near '$knownSeriesName': #$num")
                    return Pair(knownSeriesName, num)
                }
            }
        }

        return null
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
