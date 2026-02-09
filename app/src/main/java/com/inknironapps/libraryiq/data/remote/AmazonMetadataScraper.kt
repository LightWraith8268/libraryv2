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
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
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
        // Amazon search results contain links like /dp/ASIN/ or /gp/product/ASIN/
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
        // Primary: span inside title-recipe div
        val titleRecipe = """data-cy="title-recipe"[^>]*>.*?<span[^>]*>([^<]+)</span>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)?.trim()

        // Fallback: a-size-medium text
        val mediumText = """class="a-size-medium[^"]*a-text-normal"[^>]*>([^<]+)</""".toRegex()
            .find(html)?.groupValues?.get(1)?.trim()

        return titleRecipe ?: mediumText
    }

    private fun extractSearchAuthor(html: String): String? {
        // Look for "by Author Name" pattern - the word "by" followed by author link/text
        // Pattern 1: <span class="a-size-base">by </span>...<a ...>Author</a>
        val byLinkRegex = """>\s*by\s*</span>\s*(?:<[^>]+>\s*)*<a[^>]*>([^<]+)</a>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val byLink = byLinkRegex.find(html)?.groupValues?.get(1)?.trim()
        if (byLink != null && isValidAuthor(byLink)) return byLink

        // Pattern 2: "by" as text followed by author name in next element
        val byTextRegex = """\bby\s+</span>\s*<span[^>]*>([^<]+)</span>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val byText = byTextRegex.find(html)?.groupValues?.get(1)?.trim()
        if (byText != null && isValidAuthor(byText)) return byText

        // Pattern 3: author field in structured data
        val ldJsonRegex = """"author"\s*:\s*\[\s*\{\s*"name"\s*:\s*"([^"]+)""""
            .toRegex()
        val ldAuthor = ldJsonRegex.find(html)?.groupValues?.get(1)?.trim()
        if (ldAuthor != null) return ldAuthor

        return null
    }

    private fun extractSearchImage(html: String): String? {
        // s-image class is the main product image in search results
        val imgRegex = """<img[^>]*class="s-image"[^>]*src="([^"]+)"[^>]*/?>""".toRegex()
        return imgRegex.find(html)?.groupValues?.get(1)
    }

    // --- Product Page Parsing ---

    private fun parseProductPage(html: String, isbn: String): Book? {
        val title = extractProductTitle(html)
        if (title == null) {
            DebugLog.d(TAG, "No title found in product page")
            return null
        }

        val author = extractProductAuthor(html)
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

    private fun extractProductTitle(html: String): String? {
        // Try productTitle span first (most reliable)
        val productTitle = extractInnerText(html, """id="productTitle"""")
        if (productTitle != null) return productTitle

        // og:title meta tag
        val ogTitle = extractMeta(html, "og:title")
        if (ogTitle != null) return ogTitle

        // LD+JSON structured data
        val ldTitle = """"name"\s*:\s*"([^"]+)"""".toRegex()
            .find(html)?.groupValues?.get(1)
        return ldTitle
    }

    private fun extractProductAuthor(html: String): String? {
        // Pattern 1: author byline section
        val bylineRegex = """id="bylineInfo".*?class="author[^"]*"[^>]*>.*?<a[^>]*>([^<]+)</a>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val byline = bylineRegex.find(html)?.groupValues?.get(1)?.trim()
        if (byline != null && isValidAuthor(byline)) return byline

        // Pattern 2: any author class link
        val authorRegex = """class="author[^"]*"[^>]*>.*?<a[^>]*>([^<]+)</a>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val author = authorRegex.find(html)?.groupValues?.get(1)?.trim()
        if (author != null && isValidAuthor(author)) return author

        // Pattern 3: "by" followed by a link
        val byRegex = """<span[^>]*>\s*by\s*</span>\s*<span[^>]*>\s*<a[^>]*>([^<]+)</a>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val byAuthor = byRegex.find(html)?.groupValues?.get(1)?.trim()
        if (byAuthor != null && isValidAuthor(byAuthor)) return byAuthor

        // Pattern 4: LD+JSON
        val ldRegex = """"author"\s*:\s*(?:\[\s*\{\s*)?"name"\s*:\s*"([^"]+)""""
            .toRegex()
        return ldRegex.find(html)?.groupValues?.get(1)?.trim()
    }

    private fun extractProductImage(html: String): String? {
        // Main product image (high-res)
        val imgRegex = """"hiRes"\s*:\s*"([^"]+)"""".toRegex()
        val hiRes = imgRegex.find(html)?.groupValues?.get(1)
        if (hiRes != null) return hiRes

        // Landing image
        val landingRegex = """id="landingImage"[^>]*src="([^"]+)"""".toRegex()
        val landing = landingRegex.find(html)?.groupValues?.get(1)
        if (landing != null) return landing

        // og:image
        return extractMeta(html, "og:image")
    }

    private fun extractProductDescription(html: String): String? {
        // Book description div
        val descHtml = extractInnerText(html, """data-a-expander-name="book_description_expander"""")
        if (descHtml != null) return stripHtmlTags(descHtml)

        return extractMeta(html, "description")
    }

    private fun extractPageCount(html: String): Int? {
        // Look for "X pages" in detail bullets or carousel
        val pagesRegex = """(\d+)\s+pages""".toRegex(RegexOption.IGNORE_CASE)
        return pagesRegex.find(html)?.groupValues?.get(1)?.toIntOrNull()
    }

    // --- Utility methods ---

    private fun isValidAuthor(text: String): Boolean {
        val lower = text.lowercase()
        return text.length >= 2 &&
            !lower.contains("see all") &&
            !lower.contains("details") &&
            !lower.contains("format") &&
            !lower.contains("edition") &&
            !lower.contains("learn more") &&
            !lower.contains("click here")
    }

    private fun extractMeta(html: String, property: String): String? {
        val regex = """<meta[^>]*(?:property|name)="$property"[^>]*content="([^"]*)"[^>]*/?>"""
            .toRegex(RegexOption.IGNORE_CASE)
        val altRegex = """<meta[^>]*content="([^"]*)"[^>]*(?:property|name)="$property"[^>]*/?>"""
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
            ?: altRegex.find(html)?.groupValues?.get(1)
    }

    private fun extractInnerText(html: String, marker: String): String? {
        val startIdx = html.indexOf(marker)
        if (startIdx == -1) return null
        // Find the closing > of this tag
        val tagClose = html.indexOf(">", startIdx)
        if (tagClose == -1) return null
        // Find content between > and next closing tag
        val contentStart = tagClose + 1
        // Look for a reasonable chunk of text
        val chunk = html.substring(contentStart, minOf(contentStart + 5000, html.length))
        // Get text content, stripping nested tags
        val firstText = stripHtmlTags(chunk).trim()
        return firstText.ifBlank { null }
    }

    private fun extractDetailBullet(html: String, label: String): String? {
        val regex = """$label\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*[:\u200F\u200E]\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*([^<]+)"""
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)?.trim()?.ifBlank { null }
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
