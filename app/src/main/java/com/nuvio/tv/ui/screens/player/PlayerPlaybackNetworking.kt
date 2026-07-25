package com.nuvio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
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
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal object PlayerPlaybackNetworking {
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

    /**
     * Fallback OkHttpClient equipped with trust-all SSL configuration for self-signed
     * or untrusted local media servers (e.g. self-signed WebDAV / Plex / Jellyfin).
     */
    internal val trustAllPlaybackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
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

    /**
     * Primary OkHttpClient using standard system SSL certificates and full SNI support.
     * Includes an automatic fallback to [trustAllPlaybackHttpClient] if an [SSLException]
     * occurs on self-signed local media servers.
     */
    internal val playbackHttpClient: OkHttpClient by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        }
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .dns(IPv4FirstDns())
            // N6 V2: attached HERE because OkHttpClient.Builder(client) copies
            // eventListenerFactory, so every newBuilder() derivative inherits
            // it -- the prewarm client, createHttpDataSourceFactory's client
            // and PlayerMediaSourceFactory's chunk-session client -- and
            // applyNetworkOptimizations sets no listener, so nothing
            // overwrites it. One attachment covers all three startup opens.
            .eventListenerFactory(PlaybackConnectionEvents)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request()
                try {
                    chain.proceed(request)
                } catch (e: SSLException) {
                    // Fallback to trust-all client if standard system SSL fails (e.g. self-signed local server)
                    trustAllPlaybackHttpClient.newCall(request).execute()
                }
            }
            .build()
    }

    /**
     * S1g: warm the playback connection at press.
     *
     * Measured 23 Jul 2026 (OPEN_SPLIT, nt14): the probe inside
     * ParallelRangeDataSource.open() spends ~1,027 ms on TCP+TLS+headers to the
     * CDN, and it fires ~0.7 s after the stream URL is already known -- the
     * network sits idle in between while the player is built. This issues a
     * one-byte ranged GET as soon as the URL resolves. Its body completes, so
     * OkHttp returns the connection to the shared pool, and the probe reuses it
     * instead of handshaking again.
     *
     * Deliberately fire-and-forget: nothing awaits it, every failure is
     * swallowed, and a pool miss simply leaves the probe to handshake exactly as
     * before. It replaces the probe's handshake rather than adding a request, so
     * it is close to request-neutral against a rate-limiting source.
     */
    fun prewarmPlaybackConnection(url: String?, headers: Map<String, String>?) {
        val target = url?.trim().orEmpty()
        if (!target.startsWith("http://", ignoreCase = true) &&
            !target.startsWith("https://", ignoreCase = true)
        ) {
            return
        }
        val request = try {
            val builder = okhttp3.Request.Builder().url(target)
            headers?.forEach { (name, value) -> builder.header(name, value) }
            // Set last so a caller-supplied Range can never widen the warm-up.
            builder.header("Range", "bytes=0-0").build()
        } catch (_: Exception) {
            return
        }
        try {
            prewarmHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    // No-op by design: the probe will handshake as it does today.
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    // Draining the one-byte body is what makes the connection reusable.
                    response.use { it.body?.bytes() }
                }
            })
        } catch (_: Exception) {
            // Dispatcher rejection or any other failure: nothing to do.
        }
    }

    /**
     * S1g: shares [NuvioExoPlayerPerformanceHelper.sharedConnectionPool] with the
     * playback data sources, which is the whole point -- a connection warmed here
     * must be the one the probe later picks up.
     */
    private val prewarmHttpClient: OkHttpClient by lazy {
        playbackHttpClient.newBuilder()
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
            .also { logPoolIdentity("prewarm", it) }
    }

    /**
     * S1g's pool-sharing claim is only true if the prewarm client and the
     * client the probe uses hold the SAME ConnectionPool instance.
     * applyNetworkOptimizations overwrites the inherited pool with
     * NuvioExoPlayerPerformanceHelper.sharedConnectionPool, and that field is
     * reassigned when the pool size changes (2 connections gives 4, the
     * default is 8) -- so two lazily-built clients can capture different
     * instances depending on construction order. These two integers decide it.
     */
    private fun logPoolIdentity(label: String, client: OkHttpClient) {
        val poolId = System.identityHashCode(client.connectionPool)
        val protos = client.protocols.joinToString(",")
        android.util.Log.i(
            "NuvioNet",
            "POOL_ID client=$label pool=$poolId protocols=$protos"
        )
    }

    @UnstableApi
    fun createHttpDataSourceFactory(defaultHeaders: Map<String, String> = emptyMap()): DataSource.Factory {
        val builder = playbackHttpClient.newBuilder()
        if (defaultHeaders.any { it.key.equals("Authorization", ignoreCase = true) }) {
            // OkHttp strips the Authorization header on cross-host redirects.
            // WebDAV servers behind reverse proxies commonly redirect to a
            // different host/port, causing auth to be lost. A network
            // interceptor ensures the header is always present on every
            // outgoing request — same behavior as mpv/curl.
            val authValue = defaultHeaders.entries
                .first { it.key.equals("Authorization", ignoreCase = true) }
                .value
            builder.addNetworkInterceptor { chain ->
                val request = chain.request()
                if (request.header("Authorization") == null) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", authValue)
                            .build()
                    )
                } else {
                    chain.proceed(request)
                }
            }
        }
        val client = builder
            .let { NuvioExoPlayerPerformanceHelper.applyNetworkOptimizations(it) }
            .build()
            .also { logPoolIdentity("datasource", it) }
        return OkHttpDataSource.Factory(client).apply {
            setDefaultRequestProperties(defaultHeaders)
            if (defaultHeaders.none { it.key.equals("User-Agent", ignoreCase = true) }) {
                setUserAgent(PlayerMediaSourceFactory.DEFAULT_USER_AGENT)
            }
        }
    }

    @UnstableApi
    fun createDataSourceFactory(
        context: android.content.Context,
        defaultHeaders: Map<String, String> = emptyMap()
    ): DataSource.Factory {
        return DefaultDataSource.Factory(context, createHttpDataSourceFactory(defaultHeaders))
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