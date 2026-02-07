package com.lightwraith8268.libraryiq.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.lightwraith8268.libraryiq.data.local.dao.BookDao
import com.lightwraith8268.libraryiq.data.local.dao.CollectionDao
import com.lightwraith8268.libraryiq.data.local.entity.Book
import com.lightwraith8268.libraryiq.data.local.entity.BookCollectionCrossRef
import com.lightwraith8268.libraryiq.data.local.entity.Collection

@Database(
    entities = [
        Book::class,
        Collection::class,
        BookCollectionCrossRef::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun collectionDao(): CollectionDao
}
