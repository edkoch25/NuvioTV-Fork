package com.nuvio.tv.data.trailer

import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * IMDb trailer discovery. Owns a windowless, application-context WebView (proven in the Phase 0 host
 * probe to clear IMDb's awsWaf JS challenge) and reads the title page's primaryVideos.edges[0] -- the
 * SAME video IMDb features in the title-page hero "Play trailer" slot -- returning its best progressive
 * mp4 as a [TrailerPlaybackSource].
 *
 * Design (mirror-IMDb, Phase 2): we do NOT rank, score or runtime-gate candidates. We take exactly the
 * one video IMDb chose for the hero (primaryVideos.edges[0]), so playback matches what a user sees on
 * the IMDb title page -- including the cases where IMDb itself features a short clip or a "featurette".
 * Resolution is capped at 1080p (throwaway hero backdrop) with a >=720p floor: a sub-720p hero returns
 * null so the caller falls back to YouTube.
 *
 * Fast path: the title page usually embeds the hero video's full playbackURLs (mp4 rungs + definition),
 * so we resolve with a single page load. Fallback: if only HLS/AUTO is inline (no acceptable mp4), we
 * load the hero's /video/vi... page and parse that. A fresh WebView is built and destroyed per resolve
 * attempt, always on the main thread; resolve() retries up to LOAD_ATTEMPTS, each bounded by
 * TITLE_BUDGET_MS. A main-frame net error, or a stalled (no-progress) load, fails fast rather than
 * polling to the ceiling.
 *
 * GPL-3.0: additive file; no upstream headers, licence text or attributions touched.
 */
@Singleton
class ImdbTrailerResolver @Inject constructor(
    @ApplicationContext private val appContext: Context
) {
    private companion object {
        const val TAG = "ImdbTrailerResolver"
        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        const val REFERER = "https://www.imdb.com/"
        const val POLL_MS = 600L
        const val POLL_MAX = 30
        const val DEAD_STALL_POLLS = 12   // abandon a load showing no progress for this many polls (dead/blocked page)
        const val MAX_EVAL_MS = 4_000L
        const val TITLE_BUDGET_MS = 45_000L
        const val MAX_NULL_STREAK = 5
        const val LOAD_ATTEMPTS = 3

        const val RES_FLOOR = 720         // never serve below this from IMDb
        const val RES_CAP = 1080          // cap picks at 1080p even when 4K exists (throwaway hero backdrop)

        val VI_ID = Regex("""^vi\d{6,}$""")
        val CDN_MP4 = Regex("""https://imdb-video\.media-imdb\.com/[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""")
    }

    private data class Encoding(val def: String, val height: Int, val mp4: Boolean, val url: String)
    private data class VideoInfo(val contentType: String?, val name: String?, val encodings: List<Encoding>)
    private data class Hero(val vi: String, val name: String?, val runtime: Int?, val encodings: List<Encoding>)
    private data class Page(val html: String, val nextData: String?)
    private class NetFlag { @Volatile var tripped = false }

    /**
     * Resolve the IMDb hero trailer for [imdbId] (a `tt...` id). Returns a playback source pointing at a
     * signed CloudFront mp4 (>=720p, <=1080p), or null if discovery failed / the hero video has no
     * acceptable mp4 (caller should fall back to YouTube on null). [type] is unused (kept for source
     * compatibility) -- mirror-IMDb selects the hero regardless of title type.
     */
    suspend fun resolve(imdbId: String, type: String? = null): TrailerPlaybackSource? {
        if (!imdbId.startsWith("tt")) {
            Log.w(TAG, "resolve called with non-imdb id: $imdbId")
            return null
        }
        repeat(LOAD_ATTEMPTS) { attempt ->
            val flag = NetFlag()
            val result = withTimeoutOrNull(TITLE_BUDGET_MS) {
                val tBuild0 = android.os.SystemClock.elapsedRealtime()
                val wv = buildWebView(flag) ?: return@withTimeoutOrNull null
                Log.i(TAG, "[$imdbId] IMDB_TIMING buildWebView=${android.os.SystemClock.elapsedRealtime() - tBuild0}ms")
                try {
                    resolveInternal(imdbId, wv, flag)
                } finally {
                    withContext(Dispatchers.Main) { runCatching { wv.stopLoading(); wv.destroy() } }
                }
            }
            if (result != null) return result
            if (attempt < LOAD_ATTEMPTS - 1) {
                Log.w(TAG, "[$imdbId] resolve attempt ${attempt + 1}/$LOAD_ATTEMPTS produced nothing; fresh WebView + retry")
                delay(500)
            }
        }
        Log.w(TAG, "[$imdbId] resolve exhausted $LOAD_ATTEMPTS attempts -> null (YouTube fallback)")
        return null
    }

    private suspend fun buildWebView(flag: NetFlag): WebView? = withContext(Dispatchers.Main) {
        try {
            val wv = WebView(appContext)
            configureWebView(wv, flag)
            val w = 1280
            val h = 720
            wv.layoutParams = ViewGroup.LayoutParams(w, h)
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, w, h)
            wv.visibility = View.VISIBLE
            runCatching { wv.resumeTimers() }
            wv
        } catch (e: Exception) {
            Log.e(TAG, "application-context WebView construction failed: ${e.message}")
            null
        }
    }

    private suspend fun resolveInternal(imdbId: String, wv: WebView, flag: NetFlag): TrailerPlaybackSource? {
        val titleUrl = "https://www.imdb.com/title/$imdbId/"
        val tTitle0 = android.os.SystemClock.elapsedRealtime()
        val titlePage = loadAndSettle(wv, titleUrl, imdbId, flag)
        Log.i(TAG, "[$imdbId] IMDB_TIMING titlePage=${android.os.SystemClock.elapsedRealtime() - tTitle0}ms htmlLen=${titlePage?.html?.length ?: -1}")
        if (titlePage == null) {
            Log.w(TAG, "[$imdbId] title page never committed")
            return null
        }

        // Mirror IMDb: the hero "Play trailer" video is primaryVideos.edges[0]. Take exactly that node
        // -- no ranking, scoring or runtime gate.
        val hero = withContext(Dispatchers.Default) { readHeroVideo(titlePage.nextData) }
        if (hero == null) {
            Log.w(TAG, "[$imdbId] no primaryVideos edge[0] hero node -> null (YouTube fallback)")
            return null
        }
        Log.i(TAG, "[$imdbId] hero video ${hero.vi} \"${hero.name ?: "?"}\" rt=${hero.runtime ?: "-"} inlineEncs=${hero.encodings.size}")

        // Fast path: the title page usually embeds the hero's mp4 rungs -> resolve with one page load.
        val inlineBest = pickBestMp4(hero.encodings)
        if (inlineBest != null && inlineBest.height >= RES_FLOOR) {
            Log.i(TAG, "[$imdbId] SELECTED ${hero.vi} (title-page) def=${inlineBest.def} h=${inlineBest.height}")
            return TrailerPlaybackSource(videoUrl = normalise(inlineBest.url))
        }

        // Fallback: load only the hero's video page and parse its playback data (mp4 rung not inline).
        val videoUrl = "https://www.imdb.com/video/${hero.vi}"
        val tVid0 = android.os.SystemClock.elapsedRealtime()
        val videoPage = loadAndSettle(wv, videoUrl, hero.vi, flag)
        Log.i(TAG, "[$imdbId] IMDB_TIMING videoPage[${hero.vi}]=${android.os.SystemClock.elapsedRealtime() - tVid0}ms htmlLen=${videoPage?.html?.length ?: -1}")
        if (videoPage == null) {
            Log.w(TAG, "[$imdbId] hero video page never committed -> null (YouTube fallback)")
            return null
        }
        val vinfo = withContext(Dispatchers.Default) { parseVideo(videoPage.nextData, videoPage.html) }
        val best = vinfo?.let { pickBestMp4(it.encodings) }
        if (best == null || best.height < RES_FLOOR) {
            val why = when {
                best == null -> "no mp4"
                best.height <= 0 -> "unverified-res(${best.def})"
                else -> "${best.height}p"
            }
            Log.i(TAG, "[$imdbId] hero ${hero.vi} best mp4 $why < ${RES_FLOOR}p floor -> null (YouTube fallback)")
            return null
        }
        Log.i(TAG, "[$imdbId] SELECTED ${hero.vi} (video-page) def=${best.def} h=${best.height}")
        return TrailerPlaybackSource(videoUrl = normalise(best.url))
    }

    // ---- hero extraction (mirror IMDb: primaryVideos.edges[0]) ----

    private fun readHeroVideo(nextData: String?): Hero? {
        if (nextData == null) return null
        val root = try { JSONObject(nextData) } catch (_: Exception) { return null }
        val node = findPrimaryVideosEdge0(root) ?: return null
        val vi = node.optString("id", "")
        if (!VI_ID.matches(vi)) return null
        val name = deep(node, "name", "value") ?: node.optString("name", null)
        val runtime = deepInt(node, "runtime", "value") ?: deepInt(node, "runtime", "seconds")
        val encs = parsePlaybackURLs(node.optJSONArray("playbackURLs"))
        return Hero(vi, name, runtime, encs)
    }

    /** Depth-first search for the first primaryVideos.edges[0].node in the __NEXT_DATA__ tree. */
    private fun findPrimaryVideosEdge0(any: Any?): JSONObject? {
        when (any) {
            is JSONObject -> {
                val pv = any.optJSONObject("primaryVideos")
                if (pv != null) {
                    val node = pv.optJSONArray("edges")?.optJSONObject(0)?.optJSONObject("node")
                    if (node != null && VI_ID.matches(node.optString("id", ""))) return node
                }
                val it = any.keys()
                while (it.hasNext()) {
                    val found = findPrimaryVideosEdge0(any.opt(it.next()))
                    if (found != null) return found
                }
            }
            is JSONArray -> for (i in 0 until any.length()) {
                val found = findPrimaryVideosEdge0(any.opt(i))
                if (found != null) return found
            }
        }
        return null
    }

    // ---- video-page parse (fallback path) ----

    private fun parseVideo(nextData: String?, html: String): VideoInfo? {
        if (nextData != null) {
            try {
                val root = JSONObject(nextData)
                val video = deepObj(root, "props", "pageProps", "videoPlaybackData", "video")
                if (video != null) {
                    val type = deep(video, "contentType", "displayName", "value") ?: deep(video, "contentType", "id")
                    val name = deep(video, "name", "value")
                    val encs = parsePlaybackURLs(video.optJSONArray("playbackURLs"))
                    if (encs.isNotEmpty()) return VideoInfo(type, name, encs)
                }
            } catch (_: Exception) {}
        }
        val u = CDN_MP4.find(normalise(html))?.value ?: return null
        return VideoInfo(null, null, listOf(Encoding("unknown", 0, true, u)))
    }

    /**
     * Parse an IMDb playbackURLs array into encodings. Resolution: prefer videoDefinition
     * (DEF_1080p/DEF_SD/DEF_AUTO...), then displayName.value ("1080p"/"SD"/"AUTO"), then legacy
     * "definition". mp4 vs HLS from videoMimeType ("MP4"/"M3U8"), url and legacy mimeType as fallback.
     */
    private fun parsePlaybackURLs(urls: JSONArray?): List<Encoding> {
        val encs = mutableListOf<Encoding>()
        if (urls != null) {
            for (i in 0 until urls.length()) {
                val e = urls.optJSONObject(i) ?: continue
                val u = normalise(e.optString("url", ""))
                if (u.isBlank()) continue
                val vdef = e.optString("videoDefinition", "")
                val ddef = deep(e, "displayName", "value") ?: ""
                val def = when {
                    vdef.isNotBlank() -> vdef
                    ddef.isNotBlank() -> ddef
                    else -> e.optString("definition", "")
                }
                val vmime = e.optString("videoMimeType", "").ifBlank { e.optString("mimeType", "") }
                val isHls = vmime.contains("m3u8", true) || def.contains("AUTO", true) || u.contains(".m3u8", true)
                val mp4 = !isHls && (vmime.contains("mp4", true) || u.contains(".mp4", true))
                encs.add(Encoding(def, defHeight(def), mp4, u))
            }
        }
        return encs
    }

    /** Best progressive mp4 <= RES_CAP; if only >cap rungs exist take the smallest of those; height 0 (unknown) last. */
    private fun pickBestMp4(encodings: List<Encoding>): Encoding? {
        val mp4s = encodings.filter { it.mp4 }
        if (mp4s.isEmpty()) return null
        val capped = mp4s.filter { it.height in 1..RES_CAP }
        if (capped.isNotEmpty()) return capped.maxByOrNull { it.height }
        val overCap = mp4s.filter { it.height > RES_CAP }
        if (overCap.isNotEmpty()) return overCap.minByOrNull { it.height }
        return mp4s.maxByOrNull { it.height } // all unknown height
    }

    // ---- webview + settle ----

    private fun configureWebView(wv: WebView, flag: NetFlag) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true); setAcceptThirdPartyCookies(wv, true)
        }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = UA
            loadsImagesAutomatically = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    when (error?.errorCode) {
                        ERROR_HOST_LOOKUP, ERROR_CONNECT, ERROR_TIMEOUT, ERROR_IO -> flag.tripped = true
                    }
                }
            }
        }
    }

    private suspend fun loadAndSettle(wv: WebView, url: String, expectKey: String, flag: NetFlag): Page? {
        flag.tripped = false
        withContext(Dispatchers.Main) { wv.loadUrl("about:blank") }
        delay(200)
        withContext(Dispatchers.Main) { wv.loadUrl(url, mapOf("Referer" to REFERER)) }
        var lastLen = -1
        var stable = 0
        var nullStreak = 0
        var lastProgressPoll = 0
        repeat(POLL_MAX) { pollIndex ->
            delay(POLL_MS)
            if (flag.tripped) {
                Log.w(TAG, "main-frame net error during load ($url) -> abandon (fail-fast)")
                return null
            }
            val href = readHref(wv)
            if (href == null) {
                if (++nullStreak >= MAX_NULL_STREAK) return null
                if (pollIndex - lastProgressPoll >= DEAD_STALL_POLLS) {
                    Log.w(TAG, "[$expectKey] load stalled (no navigation) after ${pollIndex + 1} polls -> abandon (dead-fast)")
                    return null
                }
                return@repeat
            }
            nullStreak = 0
            if (!href.contains(expectKey)) {
                if (pollIndex - lastProgressPoll >= DEAD_STALL_POLLS) {
                    Log.w(TAG, "[$expectKey] load stalled (no navigation) after ${pollIndex + 1} polls -> abandon (dead-fast)")
                    return null
                }
                return@repeat
            }
            val html = readOuterHtml(wv) ?: return@repeat
            val hasCdn = html.contains("imdb-video.media-imdb.com")
            val hasNext = html.contains("__NEXT_DATA__") || html.contains("videoPlaybackData") || html.contains("primaryVideos")
            if (hasCdn || (hasNext && html.length == lastLen)) {
                return Page(html, readNextData(wv))
            }
            // Progress = data markers present, or HTML still growing toward the real page.
            if (hasNext || html.length != lastLen) {
                lastProgressPoll = pollIndex
            }
            if (hasNext) {
                if (html.length == lastLen) stable++ else stable = 0
                lastLen = html.length
                if (stable >= 2) return Page(html, readNextData(wv))
            } else {
                lastLen = html.length
            }
            // Dead-load early bail: no progress toward real content for DEAD_STALL_POLLS polls.
            if (pollIndex - lastProgressPoll >= DEAD_STALL_POLLS) {
                Log.w(TAG, "[$expectKey] load stalled (no data progress) after ${pollIndex + 1} polls -> abandon (dead-fast)")
                return null
            }
        }
        val href = readHref(wv) ?: ""
        return if (href.contains(expectKey)) Page(readOuterHtml(wv) ?: "", readNextData(wv)) else null
    }

    private suspend fun readHref(wv: WebView): String? = evalJs(wv, "(function(){return location.href})()")
    private suspend fun readOuterHtml(wv: WebView): String? = evalJs(wv, "(function(){return document.documentElement.outerHTML})()")
    private suspend fun readNextData(wv: WebView): String? =
        evalJs(wv, "(function(){var e=document.getElementById('__NEXT_DATA__');return e?e.textContent:''})()")
            ?.takeIf { it.isNotBlank() }

    private suspend fun evalJs(wv: WebView, js: String): String? = withTimeoutOrNull(MAX_EVAL_MS) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont: CancellableContinuation<String?> ->
                try {
                    wv.evaluateJavascript(js) { raw -> if (cont.isActive) cont.resume(decodeJsString(raw)) }
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return try { JSONArray("[$raw]").getString(0) } catch (e: Exception) { raw }
    }

    // ---- json helpers ----

    private fun normalise(s: String): String =
        s.replace("\\u002F", "/").replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

    private fun deep(o: JSONObject?, vararg keys: String): String? {
        var cur: Any? = o
        for (k in keys) { cur = (cur as? JSONObject)?.opt(k) ?: return null }
        return (cur as? String) ?: cur?.toString()?.takeIf { it != "null" }
    }

    private fun deepInt(o: JSONObject?, vararg keys: String): Int? {
        var cur: Any? = o
        for (k in keys) { cur = (cur as? JSONObject)?.opt(k) ?: return null }
        return when (cur) {
            is Int -> cur
            is Number -> cur.toInt()
            is String -> cur.toIntOrNull()
            else -> null
        }
    }

    private fun deepObj(o: JSONObject?, vararg keys: String): JSONObject? {
        var cur: Any? = o
        for (k in keys) { cur = (cur as? JSONObject)?.opt(k) ?: return null }
        return cur as? JSONObject
    }

    /** Height from an IMDb definition label, hardened against non-`NNNNp` variants (4K/UHD/HD/SD...). */
    private fun defHeight(def: String): Int {
        val d = def.trim().uppercase().removePrefix("DEF_").uppercase()
        when {
            d.contains("2160") || d == "4K" || d == "UHD" -> return 2160
            d.contains("1440") || d == "2K" || d == "QHD" -> return 1440
            d.contains("1080") || d == "FHD" || d == "FULLHD" || d == "FULL HD" -> return 1080
            d.contains("720") || d == "HD" -> return 720
            d.contains("480") -> return 480
            d == "SD" -> return 480
            d.contains("360") -> return 360
            d.contains("240") -> return 240
            d == "AUTO" || d.isBlank() -> return 0 // adaptive/HLS or unknown -> unverified
        }
        return Regex("""(\d{3,4})""").find(d)?.value?.toIntOrNull() ?: 0
    }
}
