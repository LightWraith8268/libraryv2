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
     * Uses the Amazon search URL which redirects to the product page.
     */
    suspend fun lookupByIsbn(isbn: String): Book? = withContext(Dispatchers.IO) {
        try {
            // Amazon search-by-ISBN URL pattern (like Calibre uses)
            val url = "https://www.amazon.com/s?k=$isbn&i=stripbooks"
            DebugLog.d(TAG, "Fetching: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            DebugLog.d(TAG, "Response: ${response.code}, body length: ${body.length}")

            if (response.code != 200) {
                DebugLog.w(TAG, "Non-200 response: ${response.code}")
                return@withContext null
            }

            parseSearchResults(body, isbn)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Lookup failed for $isbn", e)
            null
        }
    }

    /**
     * Attempts to fetch the direct product page by ASIN/ISBN.
     */
    suspend fun lookupByProductPage(isbn: String): Book? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.amazon.com/dp/$isbn"
            DebugLog.d(TAG, "Fetching product page: $url")

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            if (response.code != 200) {
                DebugLog.w(TAG, "Product page non-200: ${response.code}")
                return@withContext null
            }

            parseProductPage(body, isbn)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Product page lookup failed for $isbn", e)
            null
        }
    }

    private fun parseSearchResults(html: String, isbn: String): Book? {
        // Look for the first search result's data attributes
        val titleRegex = """data-cy="title-recipe"[^>]*>.*?<span[^>]*>([^<]+)</span>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val authorRegex = """class="a-color-secondary"[^>]*>.*?<a[^>]*>([^<]+)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val imageRegex = """<img[^>]*class="s-image"[^>]*src="([^"]+)"[^>]*>""".toRegex()

        val title = titleRegex.find(html)?.groupValues?.get(1)?.trim()
        val author = authorRegex.find(html)?.groupValues?.get(1)?.trim()
        val imageUrl = imageRegex.find(html)?.groupValues?.get(1)

        // Also try og:title meta tag
        val ogTitle = extractMeta(html, "og:title") ?: title
        val ogImage = extractMeta(html, "og:image") ?: imageUrl

        if (ogTitle == null && title == null) {
            DebugLog.d(TAG, "No title found in search results")
            return null
        }

        val finalTitle = (ogTitle ?: title)!!
        DebugLog.d(TAG, "Parsed: $finalTitle by $author")

        return Book(
            title = cleanTitle(finalTitle),
            author = author ?: "Unknown Author",
            isbn = isbn,
            coverUrl = ogImage
        )
    }

    private fun parseProductPage(html: String, isbn: String): Book? {
        val title = extractMeta(html, "og:title")
            ?: extractBetween(html, "<span id=\"productTitle\"", "</span>")
        val imageUrl = extractMeta(html, "og:image")
        val description = extractMeta(html, "og:description")

        // Try to extract author from byline
        val authorRegex = """class="author[^"]*"[^>]*>.*?<a[^>]*>([^<]+)</a>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val author = authorRegex.find(html)?.groupValues?.get(1)?.trim()

        // Try to extract publisher/pages from detail bullets
        val publisher = extractDetailBullet(html, "Publisher")
        val pages = extractDetailBullet(html, "pages")?.let {
            """(\d+)""".toRegex().find(it)?.value?.toIntOrNull()
        }
        val pubDate = extractDetailBullet(html, "Publication date")

        if (title == null) {
            DebugLog.d(TAG, "No title found in product page")
            return null
        }

        DebugLog.d(TAG, "Product page parsed: $title by $author")

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

    private fun extractMeta(html: String, property: String): String? {
        val regex = """<meta[^>]*(?:property|name)="$property"[^>]*content="([^"]*)"[^>]*/?>""".toRegex(RegexOption.IGNORE_CASE)
        val altRegex = """<meta[^>]*content="([^"]*)"[^>]*(?:property|name)="$property"[^>]*/?>""".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
            ?: altRegex.find(html)?.groupValues?.get(1)
    }

    private fun extractBetween(html: String, startMarker: String, endMarker: String): String? {
        val startIdx = html.indexOf(startMarker)
        if (startIdx == -1) return null
        val afterStart = html.indexOf(">", startIdx) + 1
        if (afterStart == 0) return null
        val endIdx = html.indexOf(endMarker, afterStart)
        if (endIdx == -1) return null
        return html.substring(afterStart, endIdx).trim()
    }

    private fun extractDetailBullet(html: String, label: String): String? {
        val regex = """$label\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*:\s*(?:</[^>]+>\s*)*(?:<[^>]+>\s*)*([^<]+)""".toRegex(RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)?.trim()
    }

    private fun cleanTitle(title: String): String {
        // Remove common Amazon title suffixes
        return title
            .replace(Regex("""\s*:\s*Amazon\.com\s*.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|\s*Amazon\.com\s*.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }
}
