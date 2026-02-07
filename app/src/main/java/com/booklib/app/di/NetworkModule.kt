package com.booklib.app.di

import com.booklib.app.data.remote.BookApiService
import com.booklib.app.data.remote.OpenLibraryApiService
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    @Named("googleBooks")
    fun provideGoogleBooksRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BookApiService.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("openLibrary")
    fun provideOpenLibraryRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(OpenLibraryApiService.BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideBookApiService(@Named("googleBooks") retrofit: Retrofit): BookApiService {
        return retrofit.create(BookApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideOpenLibraryApiService(@Named("openLibrary") retrofit: Retrofit): OpenLibraryApiService {
        return retrofit.create(OpenLibraryApiService::class.java)
    }
}
