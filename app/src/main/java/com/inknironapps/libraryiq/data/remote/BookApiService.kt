package com.inknironapps.libraryiq.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface BookApiService {

    @GET("books/v1/volumes")
    suspend fun searchByIsbn(
        @Query("q") query: String,
        @Query("key") apiKey: String? = null
    ): GoogleBooksResponse

    /** Fetch full volume details by ID — returns more data than search (higher-res images, seriesInfo, full description). */
    @GET("books/v1/volumes/{volumeId}")
    suspend fun getVolume(
        @Path("volumeId") volumeId: String,
        @Query("key") apiKey: String? = null
    ): GoogleBookItem

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"

        fun buildIsbnQuery(isbn: String): String = "isbn:$isbn"
    }
}
