package com.inknironapps.libraryiq.di

import com.inknironapps.libraryiq.BuildConfig
import com.inknironapps.libraryiq.data.remote.BookApiService
import com.inknironapps.libraryiq.data.remote.HardcoverApiService
import com.inknironapps.libraryiq.data.remote.ITunesApiService
import com.inknironapps.libraryiq.data.remote.OpenLibraryApiService
import com.inknironapps.libraryiq.util.DebugLog
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
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
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (!response.isSuccessful) {
                    val body = response.peekBody(1024).string()
                    DebugLog.e("GoogleBooksAPI", "HTTP ${response.code}: $body")
                }
                response
            }
            .build()
        return Retrofit.Builder()
            .baseUrl(BookApiService.BASE_URL)
            .client(client)
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
    @Named("hardcover")
    fun provideHardcoverRetrofit(): Retrofit {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val token = BuildConfig.HARDCOVER_API_TOKEN
                val request = if (token.isNotEmpty()) {
                    chain.request().newBuilder()
                        .addHeader("Authorization", "Bearer $token")
                        .addHeader("Content-Type", "application/json")
                        .build()
                } else {
                    chain.request()
                }
                val response = chain.proceed(request)
                if (!response.isSuccessful) {
                    val body = response.peekBody(1024).string()
                    DebugLog.e("HardcoverAPI", "HTTP ${response.code}: $body")
                }
                response
            }
            .build()

        return Retrofit.Builder()
            .baseUrl(HardcoverApiService.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("itunes")
    fun provideITunesRetrofit(): Retrofit {
        return Retrofit.Builder()
            .baseUrl(ITunesApiService.BASE_URL)
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

    @Provides
    @Singleton
    fun provideHardcoverApiService(@Named("hardcover") retrofit: Retrofit): HardcoverApiService {
        return retrofit.create(HardcoverApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideITunesApiService(@Named("itunes") retrofit: Retrofit): ITunesApiService {
        return retrofit.create(ITunesApiService::class.java)
    }
}
