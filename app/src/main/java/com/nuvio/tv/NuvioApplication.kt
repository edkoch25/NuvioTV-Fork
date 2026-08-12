package com.nuvio.tv

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StrictMode
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.imageLoader
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
import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.data.simkl.SimklAnimeIdPreferenceHolder
import coil3.network.cachecontrol.CacheControlCacheStrategy
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NuvioApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var startupSyncService: StartupSyncService
    @Inject lateinit var playerSettingsDataStore: PlayerSettingsDataStore
    @Inject lateinit var simklAnimeIdPreferenceHolder: SimklAnimeIdPreferenceHolder

    companion object {
        private const val CACHE_TAG = "NuvioCache"
        private const val IMAGE_CACHE_DIR = "image_cache"
        private const val HTTP_CACHE_DIR = "http_cache"
        private const val HTTP_CACHE_VALIDATED_DIR = "http_cache_validated"

        /** Long enough that the walk cannot contend with cold start. */
        private const val CACHE_READOUT_DELAY_MS = 15_000L

        /**
         * Shared cookie jar for CloudStream extension HTTP requests.
         * Accessible so the player's OkHttpClient can share cookies
         * obtained during scraping (e.g., session tokens needed for playback).
         */
        val extensionCookieJar: CookieJar = object : CookieJar {
            private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val hostCookies = store[url.host] ?: return emptyList()
                synchronized(hostCookies) {
                    return hostCookies.filter { cookie ->
                        cookie.expiresAt > System.currentTimeMillis()
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val hostCookies = store.getOrPut(url.host) { mutableListOf() }
                synchronized(hostCookies) {
                    cookies.forEach { newCookie ->
                        hostCookies.removeAll { it.name == newCookie.name }
                        hostCookies.add(newCookie)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        PluginRuntimeHooks.onApplicationCreate(this)
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

        // Build 1 instrument: the only way to answer whether OkHttp's 50 MB
        // http_cache holds anything, and the only way to see Coil's COMPUTED
        // disk cap (a percent-based cap is otherwise invisible). Deliberately
        // delayed and on IO: walking thousands of cache entries must not land
        // on the cold-start path this instrument exists to help optimise.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            delay(CACHE_READOUT_DELAY_MS)
            logCacheSizes()
        }
    }

    private fun dirBytes(name: String): Long {
        val d = java.io.File(cacheDir, name)
        if (!d.isDirectory) return -1L
        return try {
            d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            -2L
        }
    }

    private fun logCacheSizes() {
        try {
            val mib = 1024L * 1024L
            val am = getSystemService(android.app.ActivityManager::class.java)
            val loader = imageLoader
            val disk = loader.diskCache
            val mem = loader.memoryCache
            // Locals, not nested literals inside a string template: an escaped
            // quote inside a template is a syntax error, and hoisting removes
            // the whole class of hazard.
            val imageMiB = dirBytes(IMAGE_CACHE_DIR) / mib
            val httpMiB = dirBytes(HTTP_CACHE_DIR) / mib
            val httpValidatedMiB = dirBytes(HTTP_CACHE_VALIDATED_DIR) / mib
            val diskSizeMiB = (disk?.size ?: -1L) / mib
            val diskMaxMiB = (disk?.maxSize ?: -1L) / mib
            val memSizeMiB = (mem?.size ?: -1L) / mib
            val memMaxMiB = (mem?.maxSize ?: -1L) / mib
            val freeMiB = cacheDir.usableSpace / mib
            android.util.Log.i(
                CACHE_TAG,
                "CACHE_SIZES image=${imageMiB}MiB http=${httpMiB}MiB " +
                    "httpValidated=${httpValidatedMiB}MiB " +
                    "coilDisk=${diskSizeMiB}MiB coilDiskMax=${diskMaxMiB}MiB " +
                    "coilMem=${memSizeMiB}MiB coilMemMax=${memMaxMiB}MiB " +
                    "lowRam=${am?.isLowRamDevice} memClass=${am?.memoryClass}MiB " +
                    "largeMemClass=${am?.largeMemoryClass}MiB freeMiB=$freeMiB"
            )
        } catch (e: Exception) {
            android.util.Log.w(CACHE_TAG, "CACHE_SIZES failed: ${e.message}")
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
                // CacheControlCacheStrategy respects server Cache-Control headers,
                // so dynamic images (e.g. BetterPosters with max-age) revalidate.
                add(
                    coil3.network.okhttp.OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .dns(IPv4FirstDns())
                                .followRedirects(true)
                                .followSslRedirects(true)
                                .build()
                        },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
            }
            .memoryCache {
                val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val totalRamMb = memoryInfo.totalMem / (1024 * 1024)
                // Low-RAM devices (≤2GB): use 0.10 — larger cache reduces GC pressure
                // from rapid bitmap eviction during scrolling.
                // Mid-range devices (≤3GB): use 0.15 for decent image caching.
                // Normal devices (>3GB): use 0.20 for snappy image loading.
                val cachePercent = when {
                    totalRamMb <= 2048 -> 0.10
                    totalRamMb <= 3072 -> 0.25
                    else -> 0.20
                }
                MemoryCache.Builder()
                    .maxSizePercent(context, cachePercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    // D13. nt21 raised this cap because 500 MB was cycling (a post-wipe
                    // audit refilled ~179 MB in under a day against a ~208k-title shared
                    // Emby library). Raising it was right; using an ABSOLUTE value was
                    // not: maxSizeBytes and maxSizePercent are mutually exclusive in
                    // Coil's DiskCache.Builder -- setting one zeroes the other -- so the
                    // 8 GiB constant silently disabled the library's own free-space
                    // proportional sizing and replaced it with a device-blind number.
                    //
                    // Percent plus clamp restores it. On a 22 GiB-free box this computes
                    // to the 2 GiB ceiling; 1 GiB at 10 GiB free; the floor binds below
                    // ~2.5 GiB free. Two properties worth knowing: Coil reads FREE space,
                    // not total, so the cap recomputes at every app start and drifts down
                    // as the disk fills; and the floor is unconditional, applied by
                    // coerceIn even when the space is not there. 256 MB was chosen over
                    // 512 for that reason -- it sits below nt21's measured 500 MB cycling
                    // threshold deliberately, trading re-downloads against consuming a
                    // quarter of a nearly-full disk.
                    //
                    // The 2 GiB ceiling is inferred, not measured: with one test device
                    // there is no steady-state working-set reference. Build 1's
                    // CACHE_SIZES readout is what converts it. Watch item unchanged:
                    // disk-cache journal replay at high entry counts is a possible
                    // cold-start contributor (task 2.13).
                    .maxSizePercent(0.10)
                    .minimumMaxSizeBytes(256L * 1024 * 1024)
                    .maximumMaxSizeBytes(2L * 1024 * 1024 * 1024)
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
