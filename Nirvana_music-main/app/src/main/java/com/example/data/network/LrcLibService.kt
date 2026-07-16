package com.example.data.network

import com.example.data.model.LrcLibResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * LRCLIB (https://lrclib.net) - free, crowd-sourced lyrics API, no API key or
 * registration required. Coverage isn't 100% (community-contributed); when a
 * track isn't found we show "Lyrics not available" rather than any placeholder.
 */
interface LrcLibService {

    @GET("api/search")
    suspend fun search(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Header("User-Agent") userAgent: String = "Nirvana/1.0 (Android music app)"
    ): List<LrcLibResult>

    companion object {
        private const val BASE_URL = "https://lrclib.net/"

        fun create(): LrcLibService {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

            return retrofit.create(LrcLibService::class.java)
        }
    }
}
