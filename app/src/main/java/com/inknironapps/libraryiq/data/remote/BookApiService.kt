package com.inknironapps.libraryiq.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

interface BookApiService {

    @GET("books/v1/volumes")
    suspend fun searchByIsbn(
        @Query("q") query: String,
        @Query("key") apiKey: String = API_KEY
    ): GoogleBooksResponse

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"
        const val API_KEY = "AIzaSyCo2ZrJ0VVm0CeqCJEP5jjrXa7m1EoqK5Q"

        fun buildIsbnQuery(isbn: String): String = "isbn:$isbn"
    }
}
