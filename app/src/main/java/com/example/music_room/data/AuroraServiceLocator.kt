package com.example.music_room.data

import com.example.music_room.BuildConfig
import com.example.music_room.data.remote.AuroraApi
import com.example.music_room.data.remote.ITunesApi
import com.example.music_room.data.repository.AuroraRepository
import com.example.music_room.data.socket.PlaybackSocketClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

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

    fun createPlaybackSocket(roomId: String): PlaybackSocketClient {
        val url = ensureTrailingSlash(BuildConfig.BACKEND_WS_URL) + "api/playback/stream?roomId=$roomId"
        return PlaybackSocketClient(okHttpClient, moshi, url)
    }

    private val unsafeOkHttpClient: OkHttpClient by lazy {
        try {
            // Create a trust manager that does not validate certificate chains
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })

            // Install the all-trusting trust manager
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            // Create an ssl socket factory with our all-trusting manager
            val sslSocketFactory = sslContext.socketFactory

            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .build()
                    chain.proceed(request)
                }
                .build()
        } catch (e: Exception) {
            throw RuntimeException(e)
        }
    }

    private val itunesRetrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl("https://itunes.apple.com/")
            .client(unsafeOkHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    val itunesApi: ITunesApi by lazy {
        itunesRetrofit.create(ITunesApi::class.java)
    }

    private fun ensureTrailingSlash(url: String): String = if (url.endsWith('/')) url else "$url/"
}
