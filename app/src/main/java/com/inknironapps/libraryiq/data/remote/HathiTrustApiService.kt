package com.inknironapps.libraryiq.data.remote

import com.google.gson.JsonObject
import retrofit2.http.GET
import retrofit2.http.Path

/**
 * HathiTrust Bibliographic API — free, no API key required.
 * Returns library-quality MARC bibliographic data for 17.8M+ volumes.
 * Coverage: Major US research libraries, strong for academic and older titles.
 */
interface HathiTrustApiService {

    @GET("api/volumes/full/isbn/{isbn}.json")
    suspend fun getByIsbn(@Path("isbn") isbn: String): JsonObject

    companion object {
        const val BASE_URL = "https://catalog.hathitrust.org/"
    }
}
