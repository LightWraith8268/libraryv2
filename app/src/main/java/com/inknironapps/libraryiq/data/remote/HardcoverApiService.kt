package com.inknironapps.libraryiq.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.POST

interface HardcoverApiService {

    @POST("v1/graphql")
    suspend fun query(@Body request: GraphQLRequest): HardcoverResponse

    companion object {
        const val BASE_URL = "https://api.hardcover.app/"

        fun buildIsbnQuery(isbn: String): GraphQLRequest {
            val query = """
                query BookByISBN(${'$'}isbn: String!) {
                    editions(where: {_or: [{isbn_13: {_eq: ${'$'}isbn}}, {isbn_10: {_eq: ${'$'}isbn}}]}, limit: 1) {
                        isbn_13
                        isbn_10
                        pages
                        title
                        image {
                            url
                        }
                        release_date
                        book {
                            title
                            description
                            contributions(limit: 5) {
                                author {
                                    name
                                }
                            }
                            book_series {
                                series {
                                    name
                                }
                                position
                            }
                        }
                        publisher {
                            name
                        }
                    }
                }
            """.trimIndent()
            return GraphQLRequest(
                query = query,
                variables = mapOf("isbn" to isbn)
            )
        }

        fun buildTitleQuery(title: String): GraphQLRequest {
            val query = """
                query BookByTitle(${'$'}title: String!) {
                    books(where: {title: {_ilike: ${'$'}title}}, limit: 3) {
                        title
                        description
                        contributions(limit: 5) {
                            author {
                                name
                            }
                        }
                        book_series {
                            series {
                                name
                            }
                            position
                        }
                        editions(limit: 1, order_by: {release_date: desc}) {
                            pages
                            image {
                                url
                            }
                            publisher {
                                name
                            }
                            release_date
                        }
                    }
                }
            """.trimIndent()
            return GraphQLRequest(
                query = query,
                variables = mapOf("title" to "%$title%")
            )
        }
    }
}

data class GraphQLRequest(
    val query: String,
    val variables: Map<String, Any?>? = null
)

data class HardcoverResponse(
    @SerializedName("data") val data: HardcoverData?
)

data class HardcoverData(
    @SerializedName("editions") val editions: List<HardcoverEdition>?,
    @SerializedName("books") val books: List<HardcoverBookResult>?
)

data class HardcoverBookResult(
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("contributions") val contributions: List<HardcoverContribution>?,
    @SerializedName("book_series") val bookSeries: List<HardcoverBookSeries>?,
    @SerializedName("editions") val editions: List<HardcoverEditionSummary>?
)

data class HardcoverEditionSummary(
    @SerializedName("pages") val pages: Int?,
    @SerializedName("image") val image: HardcoverImage?,
    @SerializedName("publisher") val publisher: HardcoverPublisher?,
    @SerializedName("release_date") val releaseDate: String?
)

data class HardcoverEdition(
    @SerializedName("isbn_13") val isbn13: String?,
    @SerializedName("isbn_10") val isbn10: String?,
    @SerializedName("pages") val pages: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("image") val image: HardcoverImage?,
    @SerializedName("release_date") val releaseDate: String?,
    @SerializedName("book") val book: HardcoverBook?,
    @SerializedName("publisher") val publisher: HardcoverPublisher?
)

data class HardcoverImage(
    @SerializedName("url") val url: String?
)

data class HardcoverBook(
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("contributions") val contributions: List<HardcoverContribution>?,
    @SerializedName("book_series") val bookSeries: List<HardcoverBookSeries>?
)

data class HardcoverBookSeries(
    @SerializedName("series") val series: HardcoverSeries?,
    @SerializedName("position") val position: Float?
)

data class HardcoverSeries(
    @SerializedName("name") val name: String?
)

data class HardcoverContribution(
    @SerializedName("author") val author: HardcoverAuthor?
)

data class HardcoverAuthor(
    @SerializedName("name") val name: String?
)

data class HardcoverPublisher(
    @SerializedName("name") val name: String?
)
