package com.inknironapps.libraryiq.data.remote

import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Penguin Random House REST API — free, no API key required for basic access.
 * Returns XML by default. Provides excellent descriptions (flapcopy),
 * format info, BISAC genre codes, and imprint data.
 * Coverage: ~25% of English-language books (PRH is the world's largest publisher).
 */
interface PrhApiService {

    @GET("resources/titles/{isbn}")
    @Headers("Accept: application/json")
    suspend fun getByIsbn(
        @Path("isbn") isbn: String,
        @Query("expandLevel") expandLevel: Int = 1
    ): PrhTitle

    companion object {
        const val BASE_URL = "https://reststop.randomhouse.com/"
    }
}

data class PrhTitle(
    val isbn: String?,
    val isbn10: String?,
    val isbn13: String?,
    val titleweb: String?,
    val titleshort: String?,
    val author: String?,
    val authorweb: String?,
    val division: String?,
    val imprint: String?,
    val formatname: String?,
    val flapcopy: String?,
    val excerpt: String?,
    val authorbio: String?,
    val themes: String?,
    val onsaledate: String?,
    val pages: String?,
    val subjectcategory: String?,
    val subjectcategorydescription: String?,
    val agerange: String?,
    val priceusa: String?,
    val workid: String?
)
