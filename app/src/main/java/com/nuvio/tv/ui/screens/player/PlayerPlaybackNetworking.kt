package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.datasource.cronet.CronetUtil
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.OkHttpClient
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.chromium.net.CronetEngine
import java.util.concurrent.Executors

internal object PlayerPlaybackNetworking {
    private const val TAG = "PlayerNetworking"

    private enum class PlaybackStack {
        CRONET,
        OKHTTP
    }

    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private val playbackHostnameVerifier = HostnameVerifier { _, _ -> true }

    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAllManager), SecureRandom())
        }
    }

    private val playbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(IPv4FirstDns())
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier(playbackHostnameVerifier)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile
    private var cachedCronetEngine: CronetEngine? = null

    private val cronetExecutor by lazy { Executors.newSingleThreadExecutor() }

    @OptIn(UnstableApi::class)
    fun createHttpDataSourceFactory(
        context: Context,
        url: String?,
        defaultHeaders: Map<String, String>
    ): DataSource.Factory {
        return when (resolvePlaybackStack(context, url)) {
            PlaybackStack.CRONET -> createCronetFactory(context, defaultHeaders)
            PlaybackStack.OKHTTP -> createOkHttpFactory(defaultHeaders)
        }
    }

    private fun resolvePlaybackStack(context: Context, url: String?): PlaybackStack {
        val normalizedUrl = url?.trim()?.lowercase()
        if (normalizedUrl?.startsWith("https://") == true && getCronetEngine(context) != null) {
            Log.d(TAG, "Using Cronet for playback url=$url")
            return PlaybackStack.CRONET
        }
        Log.d(TAG, "Using OkHttp for playback url=$url")
        return PlaybackStack.OKHTTP
    }

    private fun createOkHttpFactory(defaultHeaders: Map<String, String>): DataSource.Factory {
        return OkHttpDataSource.Factory(playbackHttpClient).apply {
            setDefaultRequestProperties(defaultHeaders)
            setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
        }
    }

    @OptIn(UnstableApi::class)
    private fun createCronetFactory(context: Context, defaultHeaders: Map<String, String>): DataSource.Factory {
        val cronetEngine = getCronetEngine(context) ?: return createOkHttpFactory(defaultHeaders)
        return CronetDataSource.Factory(cronetEngine, cronetExecutor).apply {
            setDefaultRequestProperties(defaultHeaders)
            setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            setConnectionTimeoutMs(15_000)
            setReadTimeoutMs(15_000)
            setResetTimeoutOnRedirects(true)
        }
    }

    @OptIn(UnstableApi::class)
    private fun getCronetEngine(context: Context): CronetEngine? {
        cachedCronetEngine?.let { return it }
        synchronized(this) {
            cachedCronetEngine?.let { return it }
            return CronetUtil.buildCronetEngine(
                context.applicationContext,
                PlayerMediaSourceFactory.DEFAULT_USER_AGENT,
                true
            )?.also {
                Log.d(TAG, "CronetEngine initialized for player playback")
                cachedCronetEngine = it
            }
        }
    }

    fun openConnection(
        url: String,
        headers: Map<String, String>,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        range: String? = null
    ): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            if (this is HttpsURLConnection) {
                sslSocketFactory = sslContext.socketFactory
                hostnameVerifier = playbackHostnameVerifier
            }
            instanceFollowRedirects = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            requestMethod = method
            setRequestProperty("User-Agent", headers["User-Agent"] ?: PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            headers.forEach { (key, value) ->
                if (key.equals("Range", ignoreCase = true)) return@forEach
                if (key.equals("User-Agent", ignoreCase = true)) return@forEach
                setRequestProperty(key, value)
            }
            range?.let { setRequestProperty("Range", it) }
        }
    }
}
