package com.inknironapps.libraryiq.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Query

/**
 * Wikidata SPARQL endpoint — free, no API key required (CC0).
 * Returns structured linked data for books: original titles, languages,
 * awards, genre classifications, series relationships, and translations.
 * Coverage: Millions of book entities with rich relational data.
 */
interface WikidataApiService {

    @GET("sparql")
    @Headers("Accept: application/sparql-results+json", "User-Agent: LibraryIQ/1.0 (book-cataloging-app)")
    suspend fun query(@Query("query") sparqlQuery: String): WikidataResponse

    companion object {
        const val BASE_URL = "https://query.wikidata.org/"

        /**
         * Builds a SPARQL query to look up a book by ISBN-13.
         * Traverses edition → work relationship to get series, awards, genre.
         */
        fun buildIsbnQuery(isbn: String): String {
            return """
                SELECT ?bookLabel ?authorLabel ?genreLabel ?originalTitle ?languageLabel ?seriesLabel ?seriesOrdinal ?awardLabel
                WHERE {
                  ?edition wdt:P212 "$isbn" .
                  OPTIONAL { ?edition wdt:P629 ?book }
                  BIND(COALESCE(?book, ?edition) AS ?entity)
                  OPTIONAL { ?entity wdt:P50 ?author }
                  OPTIONAL { ?entity wdt:P136 ?genre }
                  OPTIONAL { ?entity wdt:P1476 ?originalTitle }
                  OPTIONAL { ?entity wdt:P407 ?language }
                  OPTIONAL { ?entity wdt:P179 ?series . OPTIONAL { ?entity p:P179/pq:P1545 ?seriesOrdinal } }
                  OPTIONAL { ?entity wdt:P166 ?award }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en" }
                }
                LIMIT 5
            """.trimIndent()
        }
    }
}

data class WikidataResponse(
    val results: WikidataResults?
)

data class WikidataResults(
    val bindings: List<WikidataBinding>?
)

data class WikidataBinding(
    val bookLabel: WikidataValue?,
    val authorLabel: WikidataValue?,
    val genreLabel: WikidataValue?,
    val originalTitle: WikidataValue?,
    val languageLabel: WikidataValue?,
    val seriesLabel: WikidataValue?,
    val seriesOrdinal: WikidataValue?,
    val awardLabel: WikidataValue?
)

data class WikidataValue(
    val value: String?,
    val type: String?
)
