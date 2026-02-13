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
 * Scrapes Barnes & Noble product pages for book metadata.
 *
 * B&N has a direct ISBN lookup URL: /w/?ean={isbn13}
 * This goes straight to the product page — no search result parsing needed.
 *
 * Extraction priority:
 * 1. JSON-LD structured data (schema.org/Book) — most reliable
 * 2. Open Graph meta tags (og:title, og:description, og:image)
 * 3. HTML pattern matching (product detail sections)
 */
@Singleton
class BarnesNobleScraper @Inject constructor() {

    companion object {
        private const val TAG = "BNScraper"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Looks up a book on Barnes & Noble by ISBN.
     * Uses the direct EAN lookup URL — goes straight to product page.
     */
    suspend fun lookupByIsbn(isbn: String): Book? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.barnesandnoble.com/w/?ean=$isbn"
            DebugLog.d(TAG, "Fetching: $url")

            val html = fetchPage(url) ?: return@withContext null

            // Check if we landed on a real product page vs search/404
            if (!isProductPage(html)) {
                DebugLog.d(TAG, "Not a product page (search results or 404)")
                return@withContext null
            }

            parseProductPage(html, isbn)
        } catch (e: Exception) {
            DebugLog.e(TAG, "Lookup failed for $isbn", e)
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

    private fun isProductPage(html: String): Boolean {
        // B&N product pages typically have product-specific markers
        return html.contains("ProductInfoOverview", ignoreCase = true) ||
            html.contains("pdp-overview", ignoreCase = true) ||
            html.contains("product-detail", ignoreCase = true) ||
            html.contains("schema.org/Book", ignoreCase = true) ||
            html.contains("\"@type\":\"Book\"", ignoreCase = true) ||
            html.contains("\"@type\": \"Book\"", ignoreCase = true) ||
            // Open Graph product type
            html.contains("og:type\" content=\"book", ignoreCase = true) ||
            html.contains("og:type\" content=\"product", ignoreCase = true)
    }

    private fun parseProductPage(html: String, isbn: String): Book? {
        // Strategy 1: JSON-LD structured data (most reliable)
        val jsonLdBook = parseJsonLd(html, isbn)
        if (jsonLdBook != null) {
            DebugLog.d(TAG, "Parsed from JSON-LD: '${jsonLdBook.title}' by ${jsonLdBook.author}")
        }

        // Strategy 2: Open Graph meta tags
        val ogTitle = extractMeta(html, "og:title")
        val ogDesc = extractMeta(html, "og:description")
        val ogImage = extractMeta(html, "og:image")

        // Strategy 3: HTML pattern matching
        val htmlTitle = extractHtmlTitle(html)
        val htmlAuthor = extractHtmlAuthor(html)
        val htmlImage = extractHtmlImage(html)
        val htmlDesc = extractHtmlDescription(html)
        val details = parseProductDetails(html)

        // Combine all sources — JSON-LD first, then OG, then HTML
        val title = jsonLdBook?.title
            ?: ogTitle?.replace(Regex("""\s*\|.*$"""), "")?.trim()
            ?: htmlTitle
        val author = jsonLdBook?.author
            ?: htmlAuthor
        val description = jsonLdBook?.description
            ?: htmlDesc
            ?: ogDesc
        val coverUrl = jsonLdBook?.coverUrl
            ?: ogImage
            ?: htmlImage
        val publisher = jsonLdBook?.publisher
            ?: details["Publisher"]
            ?: details["Imprint"]
        val pubDate = jsonLdBook?.publishedDate
            ?: details["Publication date"]
            ?: details["Publish Date"]
        val pages = jsonLdBook?.pageCount
            ?: details["Pages"]?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val isbn10 = jsonLdBook?.isbn10
            ?: details["ISBN-10"]
        val format = jsonLdBook?.format
            ?: details["Format"]
            ?: details["Product Type"]
        val series = jsonLdBook?.series
            ?: extractSeriesFromDetails(details)
            ?: extractSeriesFromTitle(title)
        val seriesNumber = jsonLdBook?.seriesNumber
            ?: extractSeriesNumberFromDetails(details)
        val language = details["Language"]
            ?: jsonLdBook?.language

        if (title == null || title.length < 2) {
            DebugLog.d(TAG, "No valid title found")
            return null
        }

        // Clean series parenthetical from title
        var cleanedTitle = title
        if (series != null) {
            cleanedTitle = cleanedTitle
                .replace(Regex("""\s*\(\s*${Regex.escape(series)}(?:\s*(?:,\s*#?\s*|(?:Book|Volume|Vol\.?|#)\s*)\d+)?\s*\)""", RegexOption.IGNORE_CASE), "")
                .trim()
        }
        // Strip trailing " | Barnes & Noble" or similar
        cleanedTitle = cleanedTitle
            .replace(Regex("""\s*\|.*$"""), "")
            .replace(Regex("""\s*-\s*Barnes\s*&?\s*Noble.*$""", RegexOption.IGNORE_CASE), "")
            .trim()

        DebugLog.d(TAG, "Product page: '$cleanedTitle' by '$author', " +
            "image=${coverUrl != null}, pages=$pages, publisher=$publisher, " +
            "format=$format, series=$series #$seriesNumber, " +
            "details=${details.size} fields")

        return Book(
            title = cleanedTitle,
            author = author ?: "Unknown Author",
            isbn = isbn,
            isbn10 = isbn10,
            description = description,
            coverUrl = coverUrl,
            pageCount = pages,
            publisher = publisher,
            publishedDate = pubDate,
            format = format,
            series = series,
            seriesNumber = seriesNumber,
            language = language
        )
    }

    // =====================================================================
    // JSON-LD Parser (schema.org/Book)
    // =====================================================================

    /**
     * Parses JSON-LD structured data for schema.org/Book.
     * This is the most reliable extraction method when present.
     *
     * Typical B&N JSON-LD:
     * {
     *   "@type": "Book",
     *   "name": "The Catcher in the Rye",
     *   "author": {"@type": "Person", "name": "J.D. Salinger"},
     *   "isbn": "9780316769488",
     *   "publisher": {"@type": "Organization", "name": "Little, Brown"},
     *   "numberOfPages": 277,
     *   "bookFormat": "Paperback",
     *   "image": "https://...",
     *   "description": "..."
     * }
     */
    private fun parseJsonLd(html: String, isbn: String): Book? {
        val scriptRegex = """<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)

        for (match in scriptRegex.findAll(html)) {
            val json = match.groupValues[1].trim()
            // Look for Book type
            if (!json.contains("\"Book\"") && !json.contains("\"book\"")) continue

            DebugLog.d(TAG, "Found JSON-LD with Book type (${json.length} chars)")

            val title = extractJsonString(json, "name")
            val author = extractJsonAuthor(json)
            val description = extractJsonString(json, "description")
            val image = extractJsonString(json, "image")
            val publisher = extractJsonPublisher(json)
            val pages = extractJsonInt(json, "numberOfPages")
            val bookFormat = extractJsonString(json, "bookFormat")
                ?: extractJsonString(json, "format")
            val pubDate = extractJsonString(json, "datePublished")
                ?: extractJsonString(json, "publicationDate")
            val jsonIsbn = extractJsonString(json, "isbn")
            val isbn10 = if (jsonIsbn != null && jsonIsbn.length == 10) jsonIsbn else null
            val language = extractJsonString(json, "inLanguage")

            // Series from isPartOf
            var seriesName: String? = null
            var seriesNumber: String? = null
            val isPartOfIdx = json.indexOf("isPartOf")
            if (isPartOfIdx >= 0) {
                val section = json.substring(isPartOfIdx).take(500)
                seriesName = extractJsonString(section, "name")
                seriesNumber = extractJsonString(section, "position")
                    ?: Regex(""""position"\s*:\s*(\d+)""").find(section)?.groupValues?.get(1)
            }

            if (title == null) continue

            return Book(
                title = title,
                author = author ?: "Unknown Author",
                isbn = isbn,
                isbn10 = isbn10,
                description = cleanDescription(description),
                coverUrl = image,
                pageCount = pages,
                publisher = publisher,
                publishedDate = pubDate,
                format = bookFormat,
                series = seriesName,
                seriesNumber = seriesNumber,
                language = language
            )
        }
        return null
    }

    /** Extracts a string value from JSON by key. Handles both quoted and nested values. */
    private fun extractJsonString(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]+?)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.trim()
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\/", "/")
            ?.takeIf { it.isNotBlank() }
    }

    /** Extracts an integer value from JSON by key. */
    private fun extractJsonInt(json: String, key: String): Int? {
        val regex = """"$key"\s*:\s*"?(\d+)"?""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Extracts author from JSON-LD. Handles both:
     * - "author": "Name"
     * - "author": {"@type": "Person", "name": "Name"}
     * - "author": [{"@type": "Person", "name": "Name"}, ...]
     */
    private fun extractJsonAuthor(json: String): String? {
        // Simple string author
        val simpleRegex = """"author"\s*:\s*"([^"]+)"""".toRegex()
        simpleRegex.find(json)?.let { return it.groupValues[1].trim() }

        // Object author with "name" field
        val authorIdx = json.indexOf("\"author\"")
        if (authorIdx < 0) return null
        val section = json.substring(authorIdx).take(1000)

        // Collect all author names from the author section
        val nameRegex = """"name"\s*:\s*"([^"]+)"""".toRegex()
        val names = nameRegex.findAll(section)
            .map { it.groupValues[1].trim() }
            .filter { it.length > 1 && !it.contains("Organization") && !it.contains("Person") }
            .toList()

        return names.joinToString(", ").takeIf { it.isNotBlank() }
    }

    /** Extracts publisher name from JSON-LD. */
    private fun extractJsonPublisher(json: String): String? {
        val pubIdx = json.indexOf("\"publisher\"")
        if (pubIdx < 0) return null
        val section = json.substring(pubIdx).take(500)
        return extractJsonString(section, "name")
            ?: extractJsonString(json, "publisher")
    }

    // =====================================================================
    // HTML extraction fallbacks
    // =====================================================================

    private fun extractHtmlTitle(html: String): String? {
        // Product title heading
        val h1Regex = """<h1[^>]*class="[^"]*title[^"]*"[^>]*>([^<]+)</h1>""".toRegex(RegexOption.IGNORE_CASE)
        h1Regex.find(html)?.let { return it.groupValues[1].trim() }

        // Alternate: itemprop="name"
        val itemPropRegex = """itemprop="name"[^>]*>([^<]+)<""".toRegex()
        itemPropRegex.find(html)?.let { return it.groupValues[1].trim() }

        // <title> tag fallback
        val titleRegex = """<title[^>]*>([^<]+)</title>""".toRegex(RegexOption.IGNORE_CASE)
        return titleRegex.find(html)?.groupValues?.get(1)?.trim()
    }

    private fun extractHtmlAuthor(html: String): String? {
        // "by" followed by link
        val byRegex = """(?:by|By)\s*(?:</[^>]+>\s*)*<a[^>]*>([^<]+)</a>""".toRegex()
        byRegex.find(html)?.let { return it.groupValues[1].trim() }

        // itemprop="author"
        val itemPropRegex = """itemprop="author"[^>]*>(?:.*?<[^>]*>)*\s*([^<]+?)\s*<""".toRegex(RegexOption.DOT_MATCHES_ALL)
        itemPropRegex.find(html)?.let {
            val name = it.groupValues[1].trim()
            if (name.length > 1) return name
        }

        // contributorNameID or author link
        val authorLinkRegex = """class="[^"]*contributor[^"]*"[^>]*>([^<]+)</""".toRegex()
        return authorLinkRegex.find(html)?.groupValues?.get(1)?.trim()
    }

    private fun extractHtmlImage(html: String): String? {
        // Product image
        val imgRegex = """<img[^>]*class="[^"]*product-image[^"]*"[^>]*src="(https?://[^"]+)"[^>]*/?>""".toRegex()
        imgRegex.find(html)?.let { return it.groupValues[1] }

        // itemprop="image"
        val itemPropRegex = """itemprop="image"[^>]*(?:src|content)="(https?://[^"]+)"[^>]*/?>""".toRegex()
        return itemPropRegex.find(html)?.groupValues?.get(1)
    }

    private fun extractHtmlDescription(html: String): String? {
        // Overview/description section
        val descRegex = """(?:id|class)="[^"]*(?:overview|description|synopsis)[^"]*"[^>]*>(.+?)</div>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        descRegex.find(html)?.let {
            val text = stripHtmlTags(it.groupValues[1]).trim()
            if (text.length > 30) return cleanDescription(text)
        }
        return null
    }

    /**
     * Parses product detail key-value pairs from the page.
     * B&N typically has a "Product Details" section with structured data.
     */
    private fun parseProductDetails(html: String): Map<String, String> {
        val details = mutableMapOf<String, String>()

        // Pattern 1: <dt>Label</dt><dd>Value</dd> (definition list)
        val dlRegex = """<dt[^>]*>\s*([^<]+?)\s*</dt>\s*<dd[^>]*>\s*([^<]+?)\s*</dd>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in dlRegex.findAll(html)) {
            val label = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (label.length in 2..60 && value.isNotBlank() && value.length < 300) {
                details.putIfAbsent(label, value)
            }
        }

        // Pattern 2: <th>Label</th><td>Value</td> (table)
        val tableRegex = """<th[^>]*>\s*([^<]+?)\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>"""
            .toRegex(RegexOption.IGNORE_CASE)
        for (match in tableRegex.findAll(html)) {
            val label = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (label.length in 2..60 && value.isNotBlank() && value.length < 300) {
                details.putIfAbsent(label, value)
            }
        }

        // Pattern 3: <span class="bold">Label:</span> Value or <b>Label:</b> Value
        val boldRegex = """<(?:span[^>]*class="[^"]*bold[^"]*"|b|strong)[^>]*>\s*([^<:]+?)\s*:?\s*</(?:span|b|strong)>\s*([^<]+)"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in boldRegex.findAll(html)) {
            val label = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            if (label.length in 2..60 && value.isNotBlank() && value.length < 300) {
                details.putIfAbsent(label, value)
            }
        }

        if (details.isNotEmpty()) {
            DebugLog.d(TAG, "Product details (${details.size}): ${details.entries.joinToString { "${it.key}=${it.value.take(40)}" }}")
        }
        return details
    }

    private fun extractSeriesFromDetails(details: Map<String, String>): String? {
        val raw = details["Series"]
            ?: details["Series Title"]
            ?: details["series"]
            ?: return null
        return raw.takeUnless { isFormatNotSeries(it) }
    }

    private fun extractSeriesNumberFromDetails(details: Map<String, String>): String? {
        val raw = details["Series Number"]
            ?: details["Volume"]
            ?: details["Book Number"]
            ?: return null
        return Regex("""(\d+)""").find(raw)?.groupValues?.get(1)
    }

    private fun extractSeriesFromTitle(title: String?): String? {
        if (title == null) return null
        // Pattern: "Title (Series Name Book N)"
        val regex = """\(([^)]+?)(?:\s+(?:Book|Volume|Vol\.?|#)\s*\d+)?\)""".toRegex(RegexOption.IGNORE_CASE)
        val candidate = regex.find(title)?.groupValues?.get(1)?.trim() ?: return null
        return candidate.takeUnless { isFormatNotSeries(it) }
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

    // =====================================================================
    // Utility
    // =====================================================================

    private fun extractMeta(html: String, property: String): String? {
        val regex = """<meta[^>]*(?:property|name)="$property"[^>]*content="([^"]*)"[^>]*/?>"""
            .toRegex(RegexOption.IGNORE_CASE)
        val altRegex = """<meta[^>]*content="([^"]*)"[^>]*(?:property|name)="$property"[^>]*/?>"""
            .toRegex(RegexOption.IGNORE_CASE)
        return regex.find(html)?.groupValues?.get(1)
            ?: altRegex.find(html)?.groupValues?.get(1)
    }

    private fun stripHtmlTags(html: String): String {
        return html.replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanDescription(text: String?): String? {
        if (text == null) return null
        return text
            .replace(Regex("""\s*Read\s*more\.?\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*Read\s*less\.?\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*See\s*(?:all|more).*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .takeIf { it.length > 20 }
    }
}
