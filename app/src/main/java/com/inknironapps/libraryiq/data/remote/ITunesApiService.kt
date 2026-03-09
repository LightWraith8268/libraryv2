package com.inknironapps.libraryiq.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * iTunes Search API for ebook cover images.
 * Free, no API key required. Rate limit: ~20 req/min.
 * https://developer.apple.com/library/archive/documentation/AudioVideo/Conceptual/iTuneSearchAPI/
 */
interface ITunesApiService {
    companion object {
        const val BASE_URL = "https://itunes.apple.com/"
    }

    /** Direct ISBN lookup — returns exact match, no search ambiguity. */
    @GET("lookup")
    suspend fun lookupByIsbn(
        @Query("isbn") isbn: String
    ): ITunesSearchResponse

    @GET("search")
    suspend fun searchEbooks(
        @Query("term") term: String,
        @Query("media") media: String = "ebook",
        @Query("entity") entity: String = "ebook",
        @Query("limit") limit: Int = 5
    ): ITunesSearchResponse
}

data class ITunesSearchResponse(
    val resultCount: Int?,
    val results: List<ITunesResult>?
)

data class ITunesResult(
    val trackName: String?,
    val artistName: String?,
    val artworkUrl60: String?,
    val artworkUrl100: String?,
    val primaryGenreName: String?,
    val genres: List<String>?,
    val releaseDate: String?,
    val description: String?,
    val trackViewUrl: String?
) {
    /** Upscales the 100x100 artwork URL to 600x600 for better quality. */
    fun getHighResCoverUrl(): String? {
        return artworkUrl100?.replace("100x100bb", "600x600bb")
    }
}
