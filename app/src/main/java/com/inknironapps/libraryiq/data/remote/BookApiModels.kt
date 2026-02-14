package com.inknironapps.libraryiq.data.remote

import com.google.gson.annotations.SerializedName

data class GoogleBooksResponse(
    @SerializedName("totalItems") val totalItems: Int,
    @SerializedName("items") val items: List<GoogleBookItem>?
)

data class GoogleBookItem(
    @SerializedName("id") val id: String,
    @SerializedName("volumeInfo") val volumeInfo: VolumeInfo
)

data class VolumeInfo(
    @SerializedName("title") val title: String?,
    @SerializedName("authors") val authors: List<String>?,
    @SerializedName("publisher") val publisher: String?,
    @SerializedName("publishedDate") val publishedDate: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("pageCount") val pageCount: Int?,
    @SerializedName("categories") val categories: List<String>?,
    @SerializedName("language") val language: String?,
    @SerializedName("imageLinks") val imageLinks: ImageLinks?,
    @SerializedName("industryIdentifiers") val industryIdentifiers: List<IndustryIdentifier>?
)

data class ImageLinks(
    @SerializedName("smallThumbnail") val smallThumbnail: String?,
    @SerializedName("thumbnail") val thumbnail: String?
) {
    fun getBestUrl(): String? {
        // Google Books returns http URLs; upgrade to https
        return (thumbnail ?: smallThumbnail)?.replace("http://", "https://")
    }
}

data class IndustryIdentifier(
    @SerializedName("type") val type: String,
    @SerializedName("identifier") val identifier: String
)
