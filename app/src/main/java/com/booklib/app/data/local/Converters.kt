package com.booklib.app.data.local

import androidx.room.TypeConverter
import com.booklib.app.data.local.entity.ReadingStatus

class Converters {
    @TypeConverter
    fun fromReadingStatus(status: ReadingStatus): String = status.name

    @TypeConverter
    fun toReadingStatus(value: String): ReadingStatus = ReadingStatus.valueOf(value)
}
