package com.inknironapps.libraryiq.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * New York Times Books API — free API key (register at developer.nytimes.com).
 * Returns book reviews and bestseller history by ISBN.
 * Rate limits: 4,000 requests/day, 10 requests/minute.
 * Coverage: NYT-reviewed books and NYT bestseller list entries.
 */
interface NytBooksApiService {

    @GET("svc/books/v3/reviews.json")
    suspend fun getReviews(
        @Query("isbn") isbn: String,
        @Query("api-key") apiKey: String
    ): NytReviewsResponse

    @GET("svc/books/v3/lists/best-sellers/history.json")
    suspend fun getBestsellerHistory(
        @Query("isbn") isbn: String,
        @Query("api-key") apiKey: String
    ): NytBestsellerResponse

    companion object {
        const val BASE_URL = "https://api.nytimes.com/"
    }
}

data class NytReviewsResponse(
    val status: String?,
    @SerializedName("num_results") val numResults: Int?,
    val results: List<NytReview>?
)

data class NytReview(
    @SerializedName("book_title") val bookTitle: String?,
    @SerializedName("book_author") val bookAuthor: String?,
    val summary: String?,
    val url: String?,
    @SerializedName("publication_dt") val publicationDate: String?,
    val byline: String?
)

data class NytBestsellerResponse(
    val status: String?,
    @SerializedName("num_results") val numResults: Int?,
    val results: List<NytBestsellerEntry>?
)

data class NytBestsellerEntry(
    val title: String?,
    val author: String?,
    val publisher: String?,
    val description: String?,
    @SerializedName("ranks_history") val ranksHistory: List<NytRank>?,
    val reviews: List<NytReviewLink>?
)

data class NytRank(
    @SerializedName("primary_isbn13") val isbn13: String?,
    val rank: Int?,
    @SerializedName("list_name") val listName: String?,
    @SerializedName("display_name") val displayName: String?,
    @SerializedName("published_date") val publishedDate: String?,
    @SerializedName("weeks_on_list") val weeksOnList: Int?
)

data class NytReviewLink(
    @SerializedName("book_review_link") val bookReviewLink: String?
)
