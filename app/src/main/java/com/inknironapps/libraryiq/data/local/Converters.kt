package com.inknironapps.libraryiq.data.local

import androidx.room.TypeConverter
import com.inknironapps.libraryiq.data.local.entity.ReadingStatus

class Converters {
    @TypeConverter
    fun fromReadingStatus(status: ReadingStatus): String = status.name

    @TypeConverter
    fun toReadingStatus(value: String): ReadingStatus = ReadingStatus.valueOf(value)
}
