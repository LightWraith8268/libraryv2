package com.booklib.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String,
    val isbn: String? = null,
    val isbn10: String? = null,
    val description: String? = null,
    val coverUrl: String? = null,
    val pageCount: Int? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val readingStatus: ReadingStatus = ReadingStatus.UNREAD,
    val rating: Float? = null,
    val notes: String? = null,
    val series: String? = null,
    val seriesNumber: String? = null,
    val genre: String? = null,
    val language: String? = null,
    val format: String? = null,
    val subjects: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val dateModified: Long = System.currentTimeMillis()
)
