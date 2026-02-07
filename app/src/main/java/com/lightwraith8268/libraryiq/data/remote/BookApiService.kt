package com.lightwraith8268.libraryiq.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface BookApiService {

    @GET("books/v1/volumes")
    suspend fun searchByIsbn(
        @Query("q") query: String
    ): GoogleBooksResponse

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"

        fun buildIsbnQuery(isbn: String): String = "isbn:$isbn"
    }
}
