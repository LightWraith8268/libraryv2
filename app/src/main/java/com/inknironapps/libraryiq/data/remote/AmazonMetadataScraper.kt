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
        val pages = extractPageCount(html)
        val pubDate = extractDetailBullet(html, "Publication date")

        DebugLog.d(TAG, "Product page: '$title' by '$author', " +
            "image=${imageUrl != null}, pages=$pages, publisher=$publisher")

        return Book(
            title = cleanTitle(title),
            author = author ?: "Unknown Author",
            isbn = isbn,
            description = description,
            coverUrl = imageUrl,
            pageCount = pages,
            publisher = publisher,
            publishedDate = pubDate
        )
    }

    /**
     * Parses the HTML <title> tag which is the most reliable metadata source.
     * Amazon format: "Book Title: Author Last, First: ISBN: Amazon.com: Books"
     * Returns Pair(title, author) or null.
     */
    private fun parseHtmlTitleTag(html: String): Pair<String, String?>? {
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

        // Author is usually the second meaningful part (if it looks like a name)
        val authorCandidate = meaningful.getOrNull(1)
        val author = if (authorCandidate != null && isValidAuthor(authorCandidate) &&
            !authorCandidate.equals(bookTitle, ignoreCase = true)) {
            // Amazon uses "Last, First" format - flip to "First Last"
            if (authorCandidate.contains(",")) {
                authorCandidate.split(",").map { it.trim() }.reversed().joinToString(" ")
            } else {
                authorCandidate
            }
        } else null

        DebugLog.d(TAG, "Title tag parsed: '$bookTitle' by '$author' (raw: '$rawTitle')")
        return Pair(bookTitle, author)
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
            !lower.startsWith("@") &&
            !lower.startsWith("http")
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
