package com.lightwraith8268.libraryiq.data.local

import androidx.room.TypeConverter
import com.lightwraith8268.libraryiq.data.local.entity.ReadingStatus

class Converters {
    @TypeConverter
    fun fromReadingStatus(status: ReadingStatus): String = status.name

    @TypeConverter
    fun toReadingStatus(value: String): ReadingStatus = ReadingStatus.valueOf(value)
}
