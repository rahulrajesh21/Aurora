package com.example.music_room.data

import com.example.music_room.BuildConfig
import com.example.music_room.data.remote.AuroraApi
import com.example.music_room.data.repository.AuroraRepository
import com.example.music_room.data.socket.PlaybackSocketClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

object AuroraServiceLocator {

    private val moshi: Moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
    }

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(ensureTrailingSlash(BuildConfig.BACKEND_BASE_URL))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .client(okHttpClient)
            .build()
    }

    private val api: AuroraApi by lazy {
        retrofit.create(AuroraApi::class.java)
    }

    val repository: AuroraRepository by lazy {
        AuroraRepository(api, moshi)
    }

    fun createPlaybackSocket(): PlaybackSocketClient {
        val url = ensureTrailingSlash(BuildConfig.BACKEND_WS_URL) + "api/playback/stream"
        return PlaybackSocketClient(okHttpClient, moshi, url)
    }

    private fun ensureTrailingSlash(url: String): String = if (url.endsWith('/')) url else "$url/"
}
