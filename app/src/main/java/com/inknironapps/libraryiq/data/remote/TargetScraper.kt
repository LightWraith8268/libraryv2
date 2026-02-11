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
 * Scrapes Target product pages for book metadata.
 *
 * Target stores comprehensive book details in a collapsed "Specifications"
 * section on product pages. This data is in the HTML even when collapsed
 * (CSS-hidden, not dynamically loaded).
 *
 * Flow:
 * 1. Search Target by ISBN: /s?searchTerm={isbn}
 * 2. If search redirects to product page, parse it directly
 * 3. Otherwise extract product URL from search results, then fetch it
 * 4. Parse the Specifications section + JSON-LD + meta tags
 */
@Singleton
class TargetScraper @Inject constructor() {

    companion object {
        private const val TAG = "TargetScraper"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Looks up a book on Target by ISBN.
     * Searches Target, then fetches the product page for metadata.
     */
    suspend fun lookupByIsbn(isbn: String): Book? = withContext(Dispatchers.IO) {
        try {
            // Target search by ISBN
            val searchUrl = "https://www.target.com/s?searchTerm=$isbn"
            DebugLog.d(TAG, "Searching: $searchUrl")

            val html = fetchPage(searchUrl) ?: return@withContext null

            // Check if we landed on a product page directly (redirect)
            if (isProductPage(html)) {
                DebugLog.d(TAG, "Direct product page from search")
                return@withContext parseProductPage(html, isbn)
            }

            // Extract product URL from search results
            val productUrl = extractProductUrl(html)
            if (productUrl == null) {
                DebugLog.d(TAG, "No product URL found in search results")
                return@withContext null
            }

            DebugLog.d(TAG, "Fetching product: $productUrl")
            val productHtml = fetchPage(productUrl) ?: return@withContext null
            parseProductPage(productHtml, isbn)
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
        return html.contains("Specifications", ignoreCase = true) &&
            (html.contains("\"@type\":\"Book\"", ignoreCase = true) ||
                html.contains("\"@type\": \"Book\"", ignoreCase = true) ||
                html.contains("data-test=\"item-details", ignoreCase = true) ||
                html.contains("pdp-", ignoreCase = true) ||
                html.contains("product-detail", ignoreCase = true))
    }

    /**
     * Extracts the first product page URL from Target search results.
     * Target product URLs follow the pattern: /p/{slug}/-/A-{tcin}
     */
    private fun extractProductUrl(html: String): String? {
        // Target product links: /p/book-title/-/A-12345678
        val linkRegex = """href="(/p/[^"]+/-/A-\d+)"""".toRegex()
        val match = linkRegex.find(html)?.groupValues?.get(1) ?: return null
        return "https://www.target.com$match"
    }

    private fun parseProductPage(html: String, isbn: String): Book? {
        // --- 1. Try __NEXT_DATA__ JSON (Target uses Next.js) ---
        val nextDataBook = parseNextData(html, isbn)
        if (nextDataBook != null) {
            DebugLog.d(TAG, "Parsed from __NEXT_DATA__: '${nextDataBook.title}' by ${nextDataBook.author}")
        }

        // --- 2. Try JSON-LD ---
        val jsonLdBook = parseJsonLd(html, isbn)
        if (jsonLdBook != null) {
            DebugLog.d(TAG, "Parsed from JSON-LD: '${jsonLdBook.title}' by ${jsonLdBook.author}")
        }

        // --- 3. Parse Specifications section ---
        val specs = parseSpecifications(html)
        DebugLog.d(TAG, "Specifications (${specs.size}): ${specs.entries.joinToString { "${it.key}=${it.value.take(40)}" }}")

        // --- 4. Open Graph meta tags ---
        val ogTitle = extractMeta(html, "og:title")
        val ogDesc = extractMeta(html, "og:description")
        val ogImage = extractMeta(html, "og:image")

        // --- 5. Additional HTML fallbacks ---
        val htmlTitle = extractHtmlTitle(html)
        val htmlDesc = extractHtmlDescription(html)

        // Combine all sources
        val title = nextDataBook?.title
            ?: jsonLdBook?.title
            ?: ogTitle?.replace(Regex("""\s*[-:|].*Target.*$""", RegexOption.IGNORE_CASE), "")?.trim()
            ?: htmlTitle
            ?: specs["Title"]
        val author = nextDataBook?.author
            ?: jsonLdBook?.author
            ?: specs["Author"]
            ?: specs["author"]
        val description = nextDataBook?.description
            ?: jsonLdBook?.description
            ?: htmlDesc
            ?: ogDesc
            ?: specs["Description"]
        val coverUrl = nextDataBook?.coverUrl
            ?: jsonLdBook?.coverUrl
            ?: ogImage
        val publisher = nextDataBook?.publisher
            ?: jsonLdBook?.publisher
            ?: specs["Publisher"]
            ?: specs["publisher"]
        val pubDate = nextDataBook?.publishedDate
            ?: jsonLdBook?.publishedDate
            ?: specs["Street Date"]
            ?: specs["Publication Date"]
            ?: specs["Pub Date"]
        val pages = nextDataBook?.pageCount
            ?: jsonLdBook?.pageCount
            ?: specs["Page Count"]?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: specs["Number of Pages"]?.let { Regex("""(\d+)""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val format = nextDataBook?.format
            ?: jsonLdBook?.format
            ?: specs["Format"]
            ?: specs["Book Format"]
            ?: specs["Sub-Genre"]
        val isbn10 = specs["ISBN-10"]
            ?: specs["ISBN (10)"]
        val language = specs["Language"]
            ?: nextDataBook?.language
        val series = nextDataBook?.series
            ?: jsonLdBook?.series
            ?: specs["Series Title"]
            ?: specs["Series"]
        val seriesNumber = nextDataBook?.seriesNumber
            ?: specs["Series Number"]
            ?: specs["Volume Number"]

        if (title == null || title.length < 2) {
            DebugLog.d(TAG, "No valid title found")
            return null
        }

        // Clean title
        var cleanedTitle = title
            .replace(Regex("""\s*[-:|]\s*Target\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (series != null) {
            cleanedTitle = cleanedTitle
                .replace(Regex("""\s*\(\s*${Regex.escape(series)}(?:\s*(?:,\s*#?\s*|(?:Book|Volume|Vol\.?|#)\s*)\d+)?\s*\)""", RegexOption.IGNORE_CASE), "")
                .trim()
        }

        DebugLog.d(TAG, "Product page: '$cleanedTitle' by '$author', " +
            "image=${coverUrl != null}, pages=$pages, publisher=$publisher, " +
            "format=$format, series=$series #$seriesNumber")

        return Book(
            title = cleanedTitle,
            author = author ?: "Unknown Author",
            isbn = isbn,
            isbn10 = isbn10,
            description = cleanDescription(description),
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
    // Specifications Section Parser
    // =====================================================================

    /**
     * Parses the collapsed "Specifications" section on Target product pages.
     * This section contains structured key-value pairs with all book details:
     * ISBN, publisher, page count, format, language, series, etc.
     *
     * Tries multiple HTML patterns since Target may A/B test layouts.
     */
    private fun parseSpecifications(html: String): Map<String, String> {
        val specs = mutableMapOf<String, String>()

        // Find the Specifications section
        val specsSection = extractSpecsSection(html)
        if (specsSection == null) {
            DebugLog.d(TAG, "Specifications section not found")
            return specs
        }

        DebugLog.d(TAG, "Found Specifications section (${specsSection.length} chars)")

        // Pattern 1: <div data-test="*"><b>Label</b><span>Value</span></div>
        val boldSpanRegex = """<b[^>]*>\s*([^<]+?)\s*</b>\s*(?:</[^>]+>\s*)*<span[^>]*>\s*([^<]+?)\s*</span>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in boldSpanRegex.findAll(specsSection)) {
            addSpec(specs, match.groupValues[1], match.groupValues[2])
        }

        // Pattern 2: Key-value divs with test attributes
        // <div data-test="specLabel">Label</div><div data-test="specValue">Value</div>
        val testAttrRegex = """data-test="[^"]*[Ll]abel[^"]*"[^>]*>\s*([^<]+?)\s*</\w+>\s*<\w+[^>]*data-test="[^"]*[Vv]alue[^"]*"[^>]*>\s*([^<]+?)\s*</"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in testAttrRegex.findAll(specsSection)) {
            addSpec(specs, match.groupValues[1], match.groupValues[2])
        }

        // Pattern 3: <dt>/<dd> definition list
        val dlRegex = """<dt[^>]*>\s*([^<]+?)\s*</dt>\s*<dd[^>]*>\s*([^<]+?)\s*</dd>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in dlRegex.findAll(specsSection)) {
            addSpec(specs, match.groupValues[1], match.groupValues[2])
        }

        // Pattern 4: <th>/<td> table rows
        val tableRegex = """<th[^>]*>\s*([^<]+?)\s*</th>\s*<td[^>]*>\s*([^<]+?)\s*</td>"""
            .toRegex(RegexOption.IGNORE_CASE)
        for (match in tableRegex.findAll(specsSection)) {
            addSpec(specs, match.groupValues[1], match.groupValues[2])
        }

        // Pattern 5: <span class="bold/label">Label:</span> <span>Value</span>
        val spanLabelRegex = """<span[^>]*class="[^"]*(?:bold|label|key|title)[^"]*"[^>]*>\s*([^<:]+?)\s*:?\s*</span>\s*(?:<[^>]*>\s*)*<span[^>]*>\s*([^<]+?)\s*</span>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        for (match in spanLabelRegex.findAll(specsSection)) {
            addSpec(specs, match.groupValues[1], match.groupValues[2])
        }

        // Pattern 6: Colon-separated within divs: <div>Label: Value</div>
        val colonRegex = """<div[^>]*>\s*([A-Z][^<:]{1,40}):\s*([^<]+?)\s*</div>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        for (match in colonRegex.findAll(specsSection)) {
            addSpec(specs, match.groupValues[1], match.groupValues[2])
        }

        return specs
    }

    /**
     * Finds the Specifications section in the page HTML.
     * Looks for multiple markers since Target may use different IDs/classes.
     */
    private fun extractSpecsSection(html: String): String? {
        // Try data-test attribute first (Target's primary pattern)
        val testMarkers = listOf(
            "item-details-specifications",
            "itemDetailSpecifications",
            "specifications",
            "product-specifications",
            "productSpecifications"
        )
        for (marker in testMarkers) {
            val idx = html.indexOf("data-test=\"$marker\"", ignoreCase = true)
            if (idx >= 0) {
                val start = (idx - 50).coerceAtLeast(0)
                val end = (idx + 8000).coerceAtMost(html.length)
                return html.substring(start, end)
            }
        }

        // Try by heading text
        val headingMarkers = listOf(
            ">Specifications<",
            ">Specs<",
            ">Product Details<",
            ">Product Specifications<",
            ">Book Details<"
        )
        for (marker in headingMarkers) {
            val idx = html.indexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                val start = (idx - 100).coerceAtLeast(0)
                val end = (idx + 8000).coerceAtMost(html.length)
                return html.substring(start, end)
            }
        }

        // Try by id attribute
        val idMarkers = listOf(
            "id=\"specifications\"",
            "id=\"Specifications\"",
            "id=\"specs\"",
            "id=\"product-details\"",
            "id=\"productDetails\""
        )
        for (marker in idMarkers) {
            val idx = html.indexOf(marker, ignoreCase = true)
            if (idx >= 0) {
                val start = (idx - 50).coerceAtLeast(0)
                val end = (idx + 8000).coerceAtMost(html.length)
                return html.substring(start, end)
            }
        }

        return null
    }

    private fun addSpec(specs: MutableMap<String, String>, rawLabel: String, rawValue: String) {
        val label = rawLabel.replace(Regex("""[\u200F\u200E\u00A0:]+"""), " ").trim()
        val value = rawValue.replace(Regex("""[\u200F\u200E\u00A0]+"""), " ").trim()
        if (label.length in 2..60 && value.isNotBlank() && value.length < 300) {
            specs.putIfAbsent(label, value)
        }
    }

    // =====================================================================
    // __NEXT_DATA__ Parser (Target uses Next.js)
    // =====================================================================

    /**
     * Extracts book metadata from Target's __NEXT_DATA__ JSON blob.
     * Target uses Next.js, so all page data is in <script id="__NEXT_DATA__">.
     */
    private fun parseNextData(html: String, isbn: String): Book? {
        val nextDataRegex = """<script\s+id="__NEXT_DATA__"[^>]*>(.*?)</script>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)
        val json = nextDataRegex.find(html)?.groupValues?.get(1) ?: return null

        DebugLog.d(TAG, "__NEXT_DATA__ found (${json.length} chars)")

        // Find title - look for product title field
        val title = extractNestedJsonString(json, "title")
            ?: extractNestedJsonString(json, "product_title")
            ?: extractNestedJsonString(json, "name")
        if (title == null) return null

        val author = extractNestedJsonString(json, "author")
            ?: extractNestedJsonString(json, "book_author")
        val description = extractNestedJsonString(json, "description")
            ?: extractNestedJsonString(json, "product_description")
        val image = extractNestedJsonString(json, "primary_image_url")
            ?: extractNestedJsonString(json, "image_url")
        val publisher = extractNestedJsonString(json, "publisher")
            ?: extractNestedJsonString(json, "book_publisher")
        val pubDate = extractNestedJsonString(json, "street_date")
            ?: extractNestedJsonString(json, "publication_date")
        val pages = extractNestedJsonString(json, "page_count")?.toIntOrNull()
            ?: extractNestedJsonString(json, "number_of_pages")?.toIntOrNull()
        val format = extractNestedJsonString(json, "format")
            ?: extractNestedJsonString(json, "book_format")
        val series = extractNestedJsonString(json, "series")
            ?: extractNestedJsonString(json, "series_title")
        val language = extractNestedJsonString(json, "language")

        return Book(
            title = title,
            author = author ?: "Unknown Author",
            isbn = isbn,
            description = cleanDescription(description),
            coverUrl = image,
            pageCount = pages,
            publisher = publisher,
            publishedDate = pubDate,
            format = format,
            series = series,
            language = language
        )
    }

    // =====================================================================
    // JSON-LD Parser (schema.org/Book)
    // =====================================================================

    private fun parseJsonLd(html: String, isbn: String): Book? {
        val scriptRegex = """<script[^>]*type="application/ld\+json"[^>]*>(.*?)</script>"""
            .toRegex(RegexOption.DOT_MATCHES_ALL)

        for (match in scriptRegex.findAll(html)) {
            val json = match.groupValues[1].trim()
            if (!json.contains("\"Book\"") && !json.contains("\"book\"") &&
                !json.contains("\"Product\"")) continue

            val title = extractJsonString(json, "name") ?: continue
            val author = extractJsonAuthor(json)
            val description = extractJsonString(json, "description")
            val image = extractJsonString(json, "image")
            val publisher = extractJsonPublisher(json)
            val pages = extractJsonInt(json, "numberOfPages")
            val format = extractJsonString(json, "bookFormat")
            val pubDate = extractJsonString(json, "datePublished")

            var seriesName: String? = null
            var seriesNumber: String? = null
            val isPartOfIdx = json.indexOf("isPartOf")
            if (isPartOfIdx >= 0) {
                val section = json.substring(isPartOfIdx).take(500)
                seriesName = extractJsonString(section, "name")
                seriesNumber = extractJsonString(section, "position")
                    ?: Regex(""""position"\s*:\s*(\d+)""").find(section)?.groupValues?.get(1)
            }

            return Book(
                title = title,
                author = author ?: "Unknown Author",
                isbn = isbn,
                description = cleanDescription(description),
                coverUrl = image,
                pageCount = pages,
                publisher = publisher,
                publishedDate = pubDate,
                format = format,
                series = seriesName,
                seriesNumber = seriesNumber
            )
        }
        return null
    }

    // =====================================================================
    // HTML fallback extractors
    // =====================================================================

    private fun extractHtmlTitle(html: String): String? {
        val h1Regex = """<h1[^>]*>([^<]+)</h1>""".toRegex(RegexOption.IGNORE_CASE)
        h1Regex.find(html)?.let { return it.groupValues[1].trim() }

        val titleRegex = """<title[^>]*>([^<]+)</title>""".toRegex(RegexOption.IGNORE_CASE)
        return titleRegex.find(html)?.groupValues?.get(1)?.trim()
    }

    private fun extractHtmlDescription(html: String): String? {
        val descRegex = """(?:id|class)="[^"]*(?:description|synopsis|overview)[^"]*"[^>]*>(.+?)</div>"""
            .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        descRegex.find(html)?.let {
            val text = stripHtmlTags(it.groupValues[1]).trim()
            if (text.length > 30) return text
        }
        return null
    }

    // =====================================================================
    // JSON utility methods
    // =====================================================================

    private fun extractJsonString(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"([^"]+?)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.trim()
            ?.replace("\\n", "\n")
            ?.replace("\\\"", "\"")
            ?.replace("\\/", "/")
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractJsonInt(json: String, key: String): Int? {
        val regex = """"$key"\s*:\s*"?(\d+)"?""".toRegex()
        return regex.find(json)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Extracts a JSON string value from nested structures using a more flexible search. */
    private fun extractNestedJsonString(json: String, key: String): String? {
        // Try exact key match
        val regex = """"$key"\s*:\s*"([^"]+?)"""".toRegex()
        val match = regex.find(json)
        if (match != null) return match.groupValues[1].trim().takeIf { it.isNotBlank() }

        // Try with underscores/camelCase variations
        val camelKey = key.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }
        if (camelKey != key) {
            val camelRegex = """"$camelKey"\s*:\s*"([^"]+?)"""".toRegex()
            camelRegex.find(json)?.let { return it.groupValues[1].trim().takeIf { v -> v.isNotBlank() } }
        }

        return null
    }

    private fun extractJsonAuthor(json: String): String? {
        val simpleRegex = """"author"\s*:\s*"([^"]+)"""".toRegex()
        simpleRegex.find(json)?.let { return it.groupValues[1].trim() }

        val authorIdx = json.indexOf("\"author\"")
        if (authorIdx < 0) return null
        val section = json.substring(authorIdx).take(1000)
        val nameRegex = """"name"\s*:\s*"([^"]+)"""".toRegex()
        val names = nameRegex.findAll(section)
            .map { it.groupValues[1].trim() }
            .filter { it.length > 1 && !it.contains("Organization") && !it.contains("Person") }
            .toList()
        return names.joinToString(", ").takeIf { it.isNotBlank() }
    }

    private fun extractJsonPublisher(json: String): String? {
        val pubIdx = json.indexOf("\"publisher\"")
        if (pubIdx < 0) return null
        val section = json.substring(pubIdx).take(500)
        return extractJsonString(section, "name")
            ?: extractJsonString(json, "publisher")
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
