package com.lightwraith8268.libraryiq.di

import android.content.Context
import androidx.room.Room
import com.lightwraith8268.libraryiq.data.local.AppDatabase
import com.lightwraith8268.libraryiq.data.local.dao.BookDao
import com.lightwraith8268.libraryiq.data.local.dao.CollectionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "libraryiq_database"
        ).build()
    }

    @Provides
    fun provideBookDao(database: AppDatabase): BookDao = database.bookDao()

    @Provides
    fun provideCollectionDao(database: AppDatabase): CollectionDao = database.collectionDao()
}
