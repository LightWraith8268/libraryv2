package com.booklib.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.booklib.app.data.local.dao.BookDao
import com.booklib.app.data.local.dao.CollectionDao
import com.booklib.app.data.local.entity.Book
import com.booklib.app.data.local.entity.BookCollectionCrossRef
import com.booklib.app.data.local.entity.Collection

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
