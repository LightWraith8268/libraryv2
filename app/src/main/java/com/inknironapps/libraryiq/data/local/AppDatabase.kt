package com.inknironapps.libraryiq.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.inknironapps.libraryiq.data.local.dao.BookDao
import com.inknironapps.libraryiq.data.local.dao.CollectionDao
import com.inknironapps.libraryiq.data.local.entity.Book
import com.inknironapps.libraryiq.data.local.entity.BookCollectionCrossRef
import com.inknironapps.libraryiq.data.local.entity.Collection

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Calibre-like extended metadata
        db.execSQL("ALTER TABLE books ADD COLUMN asin TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN goodreadsId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN openLibraryId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN hardcoverId TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN tags TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN edition TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN originalTitle TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN originalLanguage TEXT DEFAULT NULL")
        // Per-user reading tracking
        db.execSQL("ALTER TABLE books ADD COLUMN dateStarted INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN dateFinished INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE books ADD COLUMN currentPage INTEGER DEFAULT NULL")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Track whether user manually chose a cover (should survive metadata refresh)
        db.execSQL("ALTER TABLE books ADD COLUMN coverManuallySet INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(
    entities = [
        Book::class,
        Collection::class,
        BookCollectionCrossRef::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun collectionDao(): CollectionDao
}
