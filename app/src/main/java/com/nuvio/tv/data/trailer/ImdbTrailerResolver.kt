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
 * probe to clear IMDb's awsWaf JS challenge) and scrapes the title page's primaryVideos, picks the
 * marquee trailer, resolves its video page and returns the best progressive mp4 as a
 * [TrailerPlaybackSource]. The WebView does the whole scrape; only the signed CloudFront mp4 URL
 * (NOT WAF-walled) is handed off, streaming over plain HTTP in ExoPlayer.
 *
 * Phase 1b selection, derived from cross-title sampling (1968..2023, film/series/anime/foreign,
 * SD..4K):
 *   - Tiered runtime gate: reject <=35s (scene/clip), 36-74s keep only if corroborated as a trailer,
 *     75-240s normal window, reject >240s (long-form). Missing runtime never rejects.
 *   - Rank: exact "Official Trailer" > official+trailer > Trailer(type)+name > Trailer(type) >
 *     Teaser > name-only "trailer"; +bonus if description says "trailer"; clip/scene name penalties;
 *     tie-break newest createdDate. Candidate must score > 0 to be selectable.
 *   - Resolution: best mp4 capped at 1080p (trailers are throwaway hero backdrop, 4K isn't worth the
 *     file size); >=720p floor -- sub-720p with no >=720p option returns null -> YouTube fallback.
 *   - Empty/missing primaryVideos returns null -> YouTube.
 *
 * A fresh WebView is built and destroyed per resolve attempt, always on the main thread; resolve()
 * retries up to LOAD_ATTEMPTS, each bounded by TITLE_BUDGET_MS. A main-frame net error trips a
 * fail-fast flag so a dead load abandons early instead of polling to the ceiling.
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
        const val MAX_CANDIDATES = 4
        const val MAX_EVAL_MS = 4_000L
        const val TITLE_BUDGET_MS = 45_000L
        const val MAX_NULL_STREAK = 5
        const val LOAD_ATTEMPTS = 3

        // Runtime gate thresholds (seconds).
        const val RT_CLIP_MAX = 35        // <= this is a scene/clip (Breaking Bad "Say My Name" 31s)
        const val RT_CORROBORATE_MAX = 74 // 36..74 keep only if corroborated (One Piece 62s official)
        const val RT_LONGFORM_MIN = 241   // >= this is long-form (Lion King 597s quiz)

        const val RES_FLOOR = 720         // never serve below this from IMDb
        const val RES_CAP = 1080          // cap picks at 1080p even when 4K exists (The Last of Us)

        val VI_ID = Regex("""^vi\d{6,}$""")
        val TRAILER_NEG = listOf("clip", "featurette", "interview", "behind", "scene", "spot", "promo", "recap", "quiz", "moment")
        val CDN_MP4 = Regex("""https://imdb-video\.media-imdb\.com/[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""")
    }

    private data class RawNode(
        val type: String?, val name: String?, val runtime: Int?,
        val description: String?, val createdDate: String?
    )
    private data class Candidate(
        val vi: String, val type: String?, val name: String?, val runtime: Int?,
        val description: String?, val createdDate: String?, val score: Int
    )
    private data class Encoding(val def: String, val height: Int, val mp4: Boolean, val url: String)
    private data class VideoInfo(val contentType: String?, val name: String?, val encodings: List<Encoding>)
    private data class Page(val html: String, val nextData: String?)
    private class NetFlag { @Volatile var tripped = false }

    /**
     * Resolve an IMDb trailer for [imdbId] (a `tt...` id). Returns a playback source pointing at a
     * signed CloudFront mp4 (>=720p, <=1080p), or null if discovery failed / no acceptable trailer
     * was found (caller should fall back to YouTube on null).
     */
    suspend fun resolve(imdbId: String, type: String? = null): TrailerPlaybackSource? {
        if (!imdbId.startsWith("tt")) {
            Log.w(TAG, "resolve called with non-imdb id: $imdbId")
            return null
        }
        repeat(LOAD_ATTEMPTS) { attempt ->
            val flag = NetFlag()
            val result = withTimeoutOrNull(TITLE_BUDGET_MS) {
                val wv = buildWebView(flag) ?: return@withTimeoutOrNull null
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
        val titlePage = loadAndSettle(wv, titleUrl, imdbId, flag) ?: run {
            Log.w(TAG, "[$imdbId] title page never committed")
            return null
        }
        val candidates = withContext(Dispatchers.Default) { selectCandidates(titlePage.nextData, titlePage.html) }
        if (candidates.isEmpty()) {
            Log.w(TAG, "[$imdbId] no eligible trailer candidates in primaryVideos -> null")
            return null
        }
        Log.i(TAG, "[$imdbId] ranked candidates: " +
            candidates.take(MAX_CANDIDATES).joinToString(" | ") { "${it.vi}(\"${it.name ?: "?"}\" rt=${it.runtime ?: "-"} s=${it.score})" })

        for (cand in candidates.take(MAX_CANDIDATES)) {
            val videoUrl = "https://www.imdb.com/video/${cand.vi}"
            val videoPage = loadAndSettle(wv, videoUrl, cand.vi, flag) ?: continue
            val vinfo = withContext(Dispatchers.Default) { parseVideo(videoPage.nextData, videoPage.html) } ?: continue
            val best = pickBestMp4(vinfo.encodings) ?: continue
            if (best.height in 1 until RES_FLOOR) {
                Log.i(TAG, "[$imdbId] ${cand.vi} best mp4 ${best.height}p < ${RES_FLOOR}p floor -> skip candidate")
                continue
            }
            Log.i(TAG, "[$imdbId] SELECTED ${cand.vi} \"${cand.name ?: "?"}\" rt=${cand.runtime ?: "-"} score=${cand.score} def=${best.def} h=${best.height}")
            return TrailerPlaybackSource(videoUrl = normalise(best.url))
        }
        Log.i(TAG, "[$imdbId] no candidate cleared the ${RES_FLOOR}p floor -> null (YouTube fallback)")
        return null
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
        repeat(POLL_MAX) {
            delay(POLL_MS)
            if (flag.tripped) {
                Log.w(TAG, "main-frame net error during load ($url) -> abandon (fail-fast)")
                return null
            }
            val href = readHref(wv)
            if (href == null) {
                if (++nullStreak >= MAX_NULL_STREAK) return null
                return@repeat
            }
            nullStreak = 0
            if (!href.contains(expectKey)) return@repeat
            val html = readOuterHtml(wv) ?: return@repeat
            val hasCdn = html.contains("imdb-video.media-imdb.com")
            val hasNext = html.contains("__NEXT_DATA__") || html.contains("videoPlaybackData") || html.contains("primaryVideos")
            if (hasCdn || (hasNext && html.length == lastLen)) {
                return Page(html, readNextData(wv))
            }
            if (hasNext) {
                if (html.length == lastLen) stable++ else stable = 0
                lastLen = html.length
                if (stable >= 2) return Page(html, readNextData(wv))
            } else {
                lastLen = html.length
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

    // ---- selection ----

    private fun selectCandidates(nextData: String?, html: String): List<Candidate> {
        val nodes = LinkedHashMap<String, RawNode>()
        if (nextData != null) {
            try { collectVideoNodes(JSONObject(nextData), nodes) } catch (_: Exception) {}
        }
        if (nodes.isEmpty()) {
            // Fallback: bare id scrape when JSON parse fails. No metadata, so score neutrally by type-less rule.
            for (m in Regex("""vi\d{6,}""").findAll(html)) nodes.putIfAbsent(m.value, RawNode(null, null, null, null, null))
        }
        val scored = nodes.entries.mapNotNull { (vi, n) ->
            val score = classify(n) ?: return@mapNotNull null
            Candidate(vi, n.type, n.name, n.runtime, n.description, n.createdDate, score)
        }
        return scored.sortedWith(
            compareByDescending<Candidate> { it.score }.thenByDescending { it.createdDate ?: "" }
        )
    }

    /** Apply the tiered runtime gate + trailer ranking. Returns null = ineligible (gated out / non-trailer). */
    private fun classify(n: RawNode): Int? {
        val type = (n.type ?: "")
        val name = (n.name ?: "").lowercase()
        val desc = (n.description ?: "").lowercase()
        val rt = n.runtime

        // Runtime gate (only when runtime is present; missing runtime never rejects).
        if (rt != null) {
            if (rt <= RT_CLIP_MAX) return null
            if (rt >= RT_LONGFORM_MIN) return null
            if (rt in (RT_CLIP_MAX + 1)..RT_CORROBORATE_MAX) {
                val corroborated = type.equals("Trailer", true) &&
                    (name.contains("trailer") || name.contains("official") || desc.contains("trailer"))
                if (!corroborated) return null
            }
        }

        var s = 0
        when {
            name == "official trailer" || name.endsWith("official trailer") -> s += 200
            name.contains("official") && name.contains("trailer") -> s += 180
            type.equals("Trailer", true) && name.contains("trailer") -> s += 140
            type.equals("Trailer", true) -> s += 120
            type.equals("Teaser", true) || name.contains("teaser") -> s += 80
            name.contains("trailer") -> s += 100
        }
        if (desc.contains("trailer")) s += 20
        for (neg in TRAILER_NEG) if (name.contains(neg)) s -= 100

        // Must retain a positive trailer signal to be selectable; pure clips (<=0) defer to YouTube.
        return if (s > 0) s else null
    }

    private fun collectVideoNodes(any: Any?, out: LinkedHashMap<String, RawNode>) {
        when (any) {
            is JSONObject -> {
                val id = any.optString("id", "")
                if (VI_ID.matches(id)) {
                    val type = deep(any, "contentType", "displayName", "value")
                        ?: deep(any, "contentType", "id") ?: any.optString("videoType", null)
                    val name = deep(any, "name", "value") ?: any.optString("name", null)
                    val runtime = deepInt(any, "runtime", "value") ?: deepInt(any, "runtime", "seconds")
                    val description = deep(any, "description", "value")
                    val created = any.optString("createdDate", null)?.takeIf { it.isNotBlank() && it != "null" }
                    out.putIfAbsent(id, RawNode(type, name, runtime, description, created))
                }
                val it = any.keys()
                while (it.hasNext()) collectVideoNodes(any.opt(it.next()), out)
            }
            is JSONArray -> for (i in 0 until any.length()) collectVideoNodes(any.opt(i), out)
        }
    }

    private fun parseVideo(nextData: String?, html: String): VideoInfo? {
        if (nextData != null) {
            try {
                val root = JSONObject(nextData)
                val video = deepObj(root, "props", "pageProps", "videoPlaybackData", "video")
                if (video != null) {
                    val type = deep(video, "contentType", "displayName", "value") ?: deep(video, "contentType", "id")
                    val name = deep(video, "name", "value")
                    val urls = video.optJSONArray("playbackURLs")
                    val encs = mutableListOf<Encoding>()
                    if (urls != null) {
                        for (i in 0 until urls.length()) {
                            val e = urls.optJSONObject(i) ?: continue
                            val u = normalise(e.optString("url", ""))
                            if (u.isBlank()) continue
                            val def = deep(e, "displayName", "value") ?: e.optString("definition", "") ?: ""
                            val mime = e.optString("mimeType", "")
                            val mp4 = mime.contains("mp4", true) || u.contains(".mp4", true)
                            encs.add(Encoding(def, defHeight(def), mp4, u))
                        }
                    }
                    if (encs.isNotEmpty()) return VideoInfo(type, name, encs)
                }
            } catch (_: Exception) {}
        }
        val u = CDN_MP4.find(normalise(html))?.value ?: return null
        return VideoInfo(null, null, listOf(Encoding("unknown", 0, true, u)))
    }

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
        val d = def.trim().uppercase()
        when {
            d.contains("2160") || d == "4K" || d == "UHD" -> return 2160
            d.contains("1440") || d == "2K" || d == "QHD" -> return 1440
            d.contains("1080") || d == "FHD" || d == "FULLHD" || d == "FULL HD" -> return 1080
            d.contains("720") || d == "HD" -> return 720
            d.contains("480") || d == "SD" -> return 480
            d.contains("360") -> return 360
            d.contains("240") -> return 240
        }
        return Regex("""(\d{3,4})""").find(d)?.value?.toIntOrNull() ?: 0
    }
}
