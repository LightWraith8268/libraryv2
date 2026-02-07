package com.lightwraith8268.libraryiq.data.remote

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface OpenLibraryApiService {

    @GET("isbn/{isbn}.json")
    suspend fun getByIsbn(@Path("isbn") isbn: String): OpenLibraryEdition

    @GET("{workKey}.json")
    suspend fun getWork(@Path("workKey", encoded = true) workKey: String): OpenLibraryWork

    @GET("{authorKey}.json")
    suspend fun getAuthor(@Path("authorKey", encoded = true) authorKey: String): OpenLibraryAuthor

    @GET("search.json")
    suspend fun search(
        @Query("isbn") isbn: String,
        @Query("fields") fields: String = "key,title,author_name,publisher,publish_date,number_of_pages_median,cover_i,subject,language,edition_key"
    ): OpenLibrarySearchResponse

    companion object {
        const val BASE_URL = "https://openlibrary.org/"
        const val COVERS_BASE = "https://covers.openlibrary.org/b/id/"

        fun coverUrl(coverId: Long, size: String = "M"): String =
            "${COVERS_BASE}${coverId}-${size}.jpg"
    }
}

data class OpenLibraryEdition(
    @SerializedName("title") val title: String?,
    @SerializedName("authors") val authors: List<OpenLibraryRef>?,
    @SerializedName("publishers") val publishers: List<String>?,
    @SerializedName("publish_date") val publishDate: String?,
    @SerializedName("number_of_pages") val numberOfPages: Int?,
    @SerializedName("isbn_13") val isbn13: List<String>?,
    @SerializedName("isbn_10") val isbn10: List<String>?,
    @SerializedName("covers") val covers: List<Long>?,
    @SerializedName("description") val description: Any?,
    @SerializedName("subjects") val subjects: List<String>?,
    @SerializedName("series") val series: List<String>?,
    @SerializedName("languages") val languages: List<OpenLibraryRef>?,
    @SerializedName("physical_format") val physicalFormat: String?,
    @SerializedName("works") val works: List<OpenLibraryRef>?
)

data class OpenLibraryRef(
    @SerializedName("key") val key: String?
)

data class OpenLibraryWork(
    @SerializedName("title") val title: String?,
    @SerializedName("description") val description: Any?,
    @SerializedName("subjects") val subjects: List<String>?
)

data class OpenLibraryAuthor(
    @SerializedName("name") val name: String?,
    @SerializedName("personal_name") val personalName: String?
)

data class OpenLibrarySearchResponse(
    @SerializedName("numFound") val numFound: Int,
    @SerializedName("docs") val docs: List<OpenLibrarySearchDoc>?
)

data class OpenLibrarySearchDoc(
    @SerializedName("key") val key: String?,
    @SerializedName("title") val title: String?,
    @SerializedName("author_name") val authorNames: List<String>?,
    @SerializedName("publisher") val publishers: List<String>?,
    @SerializedName("publish_date") val publishDates: List<String>?,
    @SerializedName("number_of_pages_median") val pageCount: Int?,
    @SerializedName("cover_i") val coverId: Long?,
    @SerializedName("subject") val subjects: List<String>?,
    @SerializedName("language") val languages: List<String>?
)
