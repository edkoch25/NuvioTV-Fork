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
 * Phase 1a: productionised IMDb trailer discovery. Owns a windowless, application-context WebView
 * (proven in the Phase 0 host probe to clear IMDb's awsWaf JS challenge on the AM9 Pro) and scrapes
 * the title page -> candidate trailer -> video page -> playbackURLs, returning the best progressive
 * mp4 as a [TrailerPlaybackSource]. No token harvest, no OkHttp discovery (Tier 0 proved AWS WAF
 * fingerprint-blocks non-browser clients); the WebView does the whole scrape and hands off only the
 * signed CloudFront mp4 URL, which is NOT WAF-walled and streams over plain HTTP in ExoPlayer.
 *
 * Selection here is deliberately naive (first candidate, best mp4 by height) -- marquee selection
 * (Official > real-trailer, >=720p floor, prefer 1080p, else YouTube fallback) is Phase 1b.
 *
 * A fresh WebView is built and destroyed per resolve, always on the main thread. resolve() retries
 * up to LOAD_ATTEMPTS with a fresh WebView; each attempt is bounded by TITLE_BUDGET_MS.
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
        const val MAX_CANDIDATES = 3
        const val MAX_EVAL_MS = 4_000L
        const val TITLE_BUDGET_MS = 45_000L
        const val MAX_NULL_STREAK = 5
        const val LOAD_ATTEMPTS = 3

        val VI_ID = Regex("""^vi\d{6,}$""")
        val TRAILER_NEG = listOf("clip", "featurette", "interview", "behind", "scene", "spot", "promo", "recap")
        val CDN_MP4 = Regex("""https://imdb-video\.media-imdb\.com/[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""")
    }

    private data class Candidate(val vi: String, val type: String?, val name: String?, val score: Int)
    private data class Encoding(val def: String, val height: Int, val mp4: Boolean, val url: String)
    private data class VideoInfo(val contentType: String?, val name: String?, val encodings: List<Encoding>)
    private data class Page(val html: String, val nextData: String?)

    /**
     * Resolve an IMDb trailer for [imdbId] (a `tt...` id). Returns a playback source pointing at a
     * signed CloudFront mp4, or null if discovery failed / no playable trailer was found.
     */
    suspend fun resolve(imdbId: String, type: String? = null): TrailerPlaybackSource? {
        if (!imdbId.startsWith("tt")) {
            Log.w(TAG, "resolve called with non-imdb id: $imdbId")
            return null
        }
        repeat(LOAD_ATTEMPTS) { attempt ->
            val result = withTimeoutOrNull(TITLE_BUDGET_MS) {
                val wv = buildWebView() ?: return@withTimeoutOrNull null
                try {
                    resolveInternal(imdbId, wv)
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
        Log.w(TAG, "[$imdbId] resolve exhausted $LOAD_ATTEMPTS attempts")
        return null
    }

    private suspend fun buildWebView(): WebView? = withContext(Dispatchers.Main) {
        try {
            val wv = WebView(appContext)
            configureWebView(wv)
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

    private suspend fun resolveInternal(imdbId: String, wv: WebView): TrailerPlaybackSource? {
        val titleUrl = "https://www.imdb.com/title/$imdbId/"
        val titlePage = loadAndSettle(wv, titleUrl, imdbId) ?: run {
            Log.w(TAG, "[$imdbId] title page never committed")
            return null
        }
        val candidates = withContext(Dispatchers.Default) { selectCandidates(titlePage.nextData, titlePage.html) }
        if (candidates.isEmpty()) {
            Log.w(TAG, "[$imdbId] no candidate video nodes on title page")
            return null
        }
        for (cand in candidates.take(MAX_CANDIDATES)) {
            val videoUrl = "https://www.imdb.com/video/${cand.vi}"
            val videoPage = loadAndSettle(wv, videoUrl, cand.vi) ?: continue
            val vinfo = withContext(Dispatchers.Default) { parseVideo(videoPage.nextData, videoPage.html) } ?: continue
            val best = vinfo.encodings.filter { it.mp4 }.maxByOrNull { it.height } ?: continue
            Log.i(TAG, "[$imdbId] resolved -> ${cand.vi} def=${best.def} h=${best.height} type=${vinfo.contentType ?: cand.type ?: "?"}")
            return TrailerPlaybackSource(videoUrl = normalise(best.url))
        }
        Log.w(TAG, "[$imdbId] no playable mp4 among ${minOf(MAX_CANDIDATES, candidates.size)} candidates")
        return null
    }

    private fun configureWebView(wv: WebView) {
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
        wv.webViewClient = WebViewClient()
    }

    private suspend fun loadAndSettle(wv: WebView, url: String, expectKey: String): Page? {
        withContext(Dispatchers.Main) { wv.loadUrl("about:blank") }
        delay(200)
        withContext(Dispatchers.Main) { wv.loadUrl(url, mapOf("Referer" to REFERER)) }
        var lastLen = -1
        var stable = 0
        var nullStreak = 0
        repeat(POLL_MAX) {
            delay(POLL_MS)
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

    private fun selectCandidates(nextData: String?, html: String): List<Candidate> {
        val nodes = LinkedHashMap<String, Pair<String?, String?>>()
        if (nextData != null) {
            try { collectVideoNodes(JSONObject(nextData), nodes) } catch (_: Exception) {}
        }
        if (nodes.isEmpty()) {
            for (m in Regex("""vi\d{6,}""").findAll(html)) nodes.putIfAbsent(m.value, null to null)
        }
        return nodes.entries.map { (vi, tn) ->
            val (type, name) = tn
            Candidate(vi, type, name, scoreCandidate(type, name))
        }.sortedByDescending { it.score }
    }

    private fun collectVideoNodes(any: Any?, out: LinkedHashMap<String, Pair<String?, String?>>) {
        when (any) {
            is JSONObject -> {
                val id = any.optString("id", "")
                if (VI_ID.matches(id)) {
                    val type = deep(any, "contentType", "displayName", "value")
                        ?: deep(any, "contentType", "id") ?: any.optString("videoType", null)
                    val name = deep(any, "name", "value") ?: any.optString("name", null)
                    out.putIfAbsent(id, type to name)
                }
                val it = any.keys()
                while (it.hasNext()) collectVideoNodes(any.opt(it.next()), out)
            }
            is JSONArray -> for (i in 0 until any.length()) collectVideoNodes(any.opt(i), out)
        }
    }

    private fun scoreCandidate(type: String?, name: String?): Int {
        val hay = ((type ?: "") + " " + (name ?: "")).lowercase()
        var s = 0
        if (hay.isBlank()) return 0
        if (hay.contains("official trailer")) s += 120
        else if (hay.contains("trailer")) s += 100
        else if (hay.contains("teaser")) s += 60
        if (hay.contains("official")) s += 20
        for (neg in TRAILER_NEG) if (hay.contains(neg)) s -= 100
        return s
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

    private fun deepObj(o: JSONObject?, vararg keys: String): JSONObject? {
        var cur: Any? = o
        for (k in keys) { cur = (cur as? JSONObject)?.opt(k) ?: return null }
        return cur as? JSONObject
    }

    private fun defHeight(def: String): Int =
        Regex("""(\d{3,4})""").find(def)?.value?.toIntOrNull()
            ?: when (def.uppercase()) { "SD" -> 480; "HD" -> 720; else -> 0 }
}
