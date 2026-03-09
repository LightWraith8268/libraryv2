package com.inknironapps.libraryiq.data.remote

import com.google.gson.JsonArray
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * OpenBD API — free, no API key required.
 * Returns publisher-curated metadata for Japanese commercial books via JPRO.
 * Coverage: Japanese commercial publications (all books with Japanese ISBNs).
 * Response is a JSON array; null entries indicate ISBN not found.
 */
interface OpenBdApiService {

    @GET("v1/get")
    suspend fun getByIsbn(@Query("isbn") isbn: String): JsonArray

    companion object {
        const val BASE_URL = "https://api.openbd.jp/"
    }
}
