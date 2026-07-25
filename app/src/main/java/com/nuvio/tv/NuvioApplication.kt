package com.nuvio.tv

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.gif.GifDecoder
import coil3.gif.AnimatedImageDecoder
import coil3.svg.SvgDecoder
import coil3.request.crossfade
import coil3.request.allowHardware
import coil3.bitmapFactoryMaxParallelism

import okio.Path.Companion.toOkioPath
import com.nuvio.tv.core.runtime.PluginRuntimeHooks
import com.nuvio.tv.core.sync.RealtimeSyncInvalidationService
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var realtimeSyncInvalidationService: RealtimeSyncInvalidationService
    @Inject lateinit var playerSettingsDataStore: PlayerSettingsDataStore

    companion object {
        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return store[url.host]?.filter { cookie ->
                    cookie.expiresAt > System.currentTimeMillis()
                } ?: emptyList()
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                cookies.forEach { newCookie ->
                    hostCookies.removeAll { it.name == newCookie.name }
                    hostCookies.add(newCookie)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PluginRuntimeHooks.onApplicationCreate(this)
        if (BuildConfig.REALTIME_SYNC_ENABLED) {
            realtimeSyncInvalidationService.start()
        }
        // Load locale synchronously so it's available before Activity.attachBaseContext.
        // SharedPreferences reads are fast (cached in memory after first access).
        val tag = getSharedPreferences("app_locale", Context.MODE_PRIVATE)
            .getString("locale_tag", null)
        LocaleCache.localeTag = tag ?: ""

        // §9.5 Gate 0: one-shot IEC61937 capability probe, GATED on the MAT toggle so nothing
        // MAT-related touches the audio output at startup when the feature is off. The flag is
        // read off the main thread; the (background, once-per-process) probe runs only if enabled.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            if (playerSettingsDataStore.playerSettings.first().matPassthroughEnabled) {
                com.nuvio.tv.diagnostics.Gate0Probe.runOnce(this@NuvioApplication)
            }
        }
    }

    override fun newImageLoader(context: android.content.Context): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                if (Build.VERSION.SDK_INT >= 28) {
                    add(AnimatedImageDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
                add(SvgDecoder.Factory())
                // Use a lean OkHttpClient for image fetching — no HTTP cache (Coil's own
                // DiskCache handles caching), no cookie jar, no logging interceptors.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .dns(IPv4FirstDns())
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                        }
                    )
                )
            }
            .memoryCache {
                // Passing an explicit percent disables Coil's own low-RAM safeguard
                // (its default drops from 20% to 15% on isLowRamDevice, and largeHeap
                // makes the base the *large* memory class). Gate it ourselves so 2 GB
                // boxes don't pin an oversized image cache alongside 4K video decode.
                val lowRam = context.getSystemService(android.app.ActivityManager::class.java)
                    ?.isLowRamDevice == true
                MemoryCache.Builder()
                    .maxSizePercent(context, if (lowRam) 0.15 else 0.33)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    // nt21 (task 2.3/2.5): 500 MB still cycles — a post-wipe audit showed
                    // ~179 MB refilled in under a day against a ~208k-title shared Emby
                    // library, so steady-state eviction was certain. The cap is an LRU
                    // ceiling, not a reservation: it only occupies what is actually
                    // fetched. 8 GB targets fetched-content-never-evicts within the
                    // approved on-box headroom (~23 GB free at audit time). Watch item:
                    // disk-cache journal replay at high entry counts is a possible
                    // cold-start contributor (task 2.13).
                    .maxSizeBytes(8L * 1024 * 1024 * 1024)
                    .build()
            }
            .crossfade(false)
            .precision(coil3.size.Precision.INEXACT)
            .allowHardware(true)
            // allowRgb565 removed: inert on the hardware decode path (API 26+), and
            // actively harmful on the software paths (blur inputs decode at 16-bit
            // before processing, baking in quantisation).
            .bitmapFactoryMaxParallelism(2)
            .build()
    }
}
