package com.nuvio.tv.debug

import android.app.Activity
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.SurfaceView
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import com.nuvio.tv.data.trailer.ImdbTrailerResolver
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * DEBUG / TEST-ONLY probe, v6. Not referenced anywhere in the app; reachable only via `am start`.
 * Nothing here touches the real trailer pipeline.
 *
 * v6 hardens the WebView's fragile networking seen across the v3–v5 runs (dual-homing, stale DNS
 * after sleep/wake, and a mid-session about:blank drop — all surfacing as ERR_NAME_NOT_RESOLVED
 * or an uncommitted page). Two additions:
 *   - Retry-with-fresh-WebView: each page load is attempted up to LOAD_ATTEMPTS times; a main-frame
 *     DNS/connect error (or a failure to commit) recreates the WebView — re-initialising its network
 *     context, the same thing the manual reboot did — and retries. This is the load-bearing fix.
 *   - Best-effort bindProcessToNetwork() to a validated Ethernet network at startup. NOTE: WebView
 *     runs its network service out-of-process, so whether this reaches the WebView's own DNS is
 *     UNVERIFIED; it reliably helps the in-process OkHttp range probes, and does no harm.
 * Measurement/selection/dump logic is unchanged from v2–v5.
 *
 * v2 changes over v1 (all driven by the v1 on-device run, 19 Aug):
 *  - Discovery always resolves through the dedicated VIDEO PAGE and parses the confirmed
 *    __NEXT_DATA__ path props.pageProps.videoPlaybackData.video.playbackURLs (schema per
 *    yt-dlp's imdb extractor) with org.json — not a blind first-mp4 regex. This exposes the
 *    real encoding menu, so resolution is chosen by picking the best mp4 encoding rather than
 *    a filename rewrite (v1 URLs carried no resolution token; the rewrite ladder never applied).
 *  - Trailer SELECTION: the title page __NEXT_DATA__ is scanned recursively for video nodes
 *    ({id: "vi...", contentType, name}); candidates are scored (prefer Trailer/Official, reject
 *    Clip/Featurette/Interview/Scene) and tried in order until one resolves to a playable trailer.
 *  - Stale-DOM guard: after each navigation the page's location.href must contain the expected
 *    tt/vi before its DOM is trusted (v1 misread the previous title's page on a fast reload).
 *  - Playback lifecycle: a FRESH ExoPlayer per title, listener removed and player released in a
 *    finally on every path. v1 leaked one shared listener across titles, which is why v1's TTFF
 *    appeared to grow and old titles "reported" later videos' sizes.
 *  - Self-auditing logs: candidate list w/ scores, full encoding menu, per-title PROBE-SUMMARY,
 *    a duplicate-URL SUSPECT_STALE flag, and an END tally.
 *
 * Launch (installed as com.nuvio.tv.test):
 *   adb shell am start -n com.nuvio.tv.test/com.nuvio.tv.debug.ImdbTrailerProbeActivity
 *   adb shell am start -n com.nuvio.tv.test/com.nuvio.tv.debug.ImdbTrailerProbeActivity \
 *       --es tts "tt1375666,tt0903747"   # pass your soft/real titles here
 *
 * GPL-3.0: additive test file; no upstream headers, licence text or attributions touched.
 */
@OptIn(UnstableApi::class)
class ImdbTrailerProbeActivity : Activity() {

    private companion object {
        const val TAG = "ImdbProbe"

        const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"
        const val REFERER = "https://www.imdb.com/"

        const val POLL_MS = 600L
        const val POLL_MAX = 30
        const val MAX_CANDIDATES = 3   // video-page resolutions attempted per title
        const val MAX_EVAL_MS = 4_000L     // hard cap on any single evaluateJavascript call
        const val TITLE_BUDGET_MS = 90_000L // backstop: no single title may stall the sweep
        const val MAX_NULL_STREAK = 5      // consecutive evalJs timeouts => WebView wedged
        const val LOAD_ATTEMPTS = 3        // per-page retries; each retry gets a fresh WebView

        // Default set spans coverage classes: mainstream film, franchise, foreign-language,
        // TV series. Override with --es tts "tt..,tt.." for your own soft/real titles.
        val DEFAULT_TTS = listOf(
            "tt1375666",   // Inception (v1 gave a 480p asset — selection regression check)
            "tt15398776",  // Oppenheimer (v1 misread — stale-DOM check)
            "tt6751668",   // Parasite (foreign-language)
            "tt0903747"    // Breaking Bad (TV series)
        )

        val CDN_MP4 = Regex("""https://imdb-video\.media-imdb\.com/[^\s"'<>\\]+?\.mp4[^\s"'<>\\]*""")
        val VI_ID = Regex("""^vi\d{6,}$""")
        val TRAILER_POS = listOf("official trailer", "trailer", "teaser")
        val TRAILER_NEG = listOf("clip", "featurette", "interview", "behind", "scene", "spot", "promo", "recap")
        val WAF_MARKERS = listOf("awswaf", "token.awswaf.com", "challenge-container", "captcha", "Just a moment")
    }

    private lateinit var webView: WebView
    private lateinit var surface: SurfaceView
    @Volatile private var webViewDead = false
    @Volatile private var netErrorSeen = false

    private var currentHost: String = "attached"
    private var rootView: ViewGroup? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    // ---- v7: on-box OkHttp-direct A/B (Tier 0) ---------------------------------------------
    // Same UA + Referer as the WebView path; the ONLY intended difference is the client stack
    // (OkHttp/Conscrypt TLS vs Chrome/BoringSSL). OkHttp-first / WebView-second, same run, same
    // IP, isolates a client-fingerprint block (P2: OkHttp challenged, WebView passes) from an
    // IP-reputation block (P3: both challenged). Additive; nothing here touches the v6 path.

    private val abCookies = mutableListOf<Cookie>()
    private val abCookieJar = object : CookieJar {
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                abCookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                abCookies.add(c)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            abCookies.filter { it.matches(url) }
    }

    // Separate from `http` (the validated CDN range-probe client) so that instrument is unperturbed.
    private val okhttpAb: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(abCookieJar)
            .build()
    }

    private data class OkResp(
        val code: Int, val proto: String, val wafAction: String?, val contentEncoding: String?,
        val finalUrl: String, val body: String, val error: String?
    )
    private data class OkResult(
        val id: String, val phase: String, val code: Int, val wafAction: String?,
        val parsed: Boolean, val encodings: String, val note: String
    )
    private val okResults = mutableListOf<OkResult>()

    private fun okhttpGet(url: String, referer: String): OkResp = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build()
        okhttpAb.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            OkResp(r.code, r.protocol.toString(), r.header("x-amzn-waf-action"),
                r.header("content-encoding"), r.request.url.toString(), body, null)
        }
    } catch (e: Exception) {
        OkResp(-1, "?", null, null, url, "", e.message ?: "error")
    }

    // Pull the server-rendered __NEXT_DATA__ JSON straight out of raw HTML (no DOM needed).
    private fun extractNextData(html: String): String? {
        val idx = html.indexOf("id=\"__NEXT_DATA__\"")
        if (idx < 0) return null
        val gt = html.indexOf('>', idx)
        if (gt < 0) return null
        val end = html.indexOf("</script>", gt)
        if (end < 0) return null
        return html.substring(gt + 1, end).trim().takeIf { it.isNotBlank() }
    }

    private fun logOk(label: String, url: String, r: OkResp, nd: String?, parseState: String) {
        Log.i(TAG, "[$label] OKHTTP url=$url status=${r.code} proto=${r.proto} " +
            "waf-action=${r.wafAction ?: "-"} enc=${r.contentEncoding ?: "-"} redirected=${r.finalUrl != url} " +
            "finalUrl=${r.finalUrl} bytes=${r.body.length} nextdata=${if (nd != null) "y" else "n"} parse=$parseState")
        if (r.code != 200 || nd == null) {
            val head = r.body.replace("\n", " ").replace("\r", " ").take(300)
            Log.w(TAG, "[$label] OKHTTP bodyHead=$head")
        }
        if (r.error != null) Log.w(TAG, "[$label] OKHTTP error=${r.error}")
    }

    private suspend fun okhttpProbeOne(tt: String) {
        Log.i(TAG, "[$tt] ---- OKHTTP begin ----")
        val titleUrl = "https://www.imdb.com/title/$tt/"
        val resp = okhttpGet(titleUrl, REFERER)
        val nd = extractNextData(resp.body)
        val wafSeen = WAF_MARKERS.any { resp.body.contains(it, ignoreCase = true) }
        logOk("$tt title", titleUrl, resp, nd, if (nd != null) "ok" else "fail")
        if (resp.code != 200) {
            okResults.add(OkResult(tt, "title", resp.code, resp.wafAction, false, "", "non-200 wafSeen=$wafSeen"))
            Log.w(TAG, "[$tt] OKHTTP title non-200 (${resp.code}) waf=${resp.wafAction ?: "-"} - abort")
            return
        }
        val candidates = selectCandidates(nd, resp.body)
        if (candidates.isEmpty()) {
            okResults.add(OkResult(tt, "title", resp.code, resp.wafAction, nd != null, "", "no-video-nodes wafSeen=$wafSeen"))
            Log.w(TAG, "[$tt] OKHTTP no video nodes on title page (wafSeen=$wafSeen)")
            return
        }
        Log.i(TAG, "[$tt] OKHTTP wafSeen=$wafSeen candidates=" +
            candidates.take(MAX_CANDIDATES).joinToString(" | ") { "${it.vi}(${it.type ?: "?"}/${it.name ?: "?"}:${it.score})" })
        okResults.add(OkResult(tt, "title", resp.code, resp.wafAction, nd != null, "", "cands=${candidates.size} wafSeen=$wafSeen"))

        for ((idx, cand) in candidates.take(MAX_CANDIDATES).withIndex()) {
            val videoUrl = "https://www.imdb.com/video/${cand.vi}"
            val vresp = okhttpGet(videoUrl, REFERER)
            val vnd = extractNextData(vresp.body)
            val vinfo = parseVideo(vnd, vresp.body)
            val encStr = vinfo?.encodings?.joinToString(",") { "${it.def}${if (it.mp4) "" else "/hls"}" } ?: ""
            logOk("$tt/${cand.vi}", videoUrl, vresp, vnd, if (vinfo != null && vinfo.encodings.isNotEmpty()) "ok" else "fail")
            if (vresp.code != 200) {
                okResults.add(OkResult(tt, "video/${cand.vi}", vresp.code, vresp.wafAction, false, "", "non-200"))
                Log.w(TAG, "[$tt]   OKHTTP cand#$idx ${cand.vi} non-200 (${vresp.code}) - next")
                continue
            }
            if (vinfo == null || vinfo.encodings.isEmpty()) {
                okResults.add(OkResult(tt, "video/${cand.vi}", vresp.code, vresp.wafAction, false, "", "no-encodings"))
                Log.w(TAG, "[$tt]   OKHTTP cand#$idx ${cand.vi} no playbackURLs parsed - next")
                continue
            }
            Log.i(TAG, "[$tt]   OKHTTP cand#$idx ${cand.vi} type=${vinfo.contentType ?: "?"} " +
                "name=\"${vinfo.name ?: "?"}\" menu=[$encStr]")
            dumpEncodings("OK:$tt/${cand.vi}", vinfo.encodings)
            val best = vinfo.encodings.filter { it.mp4 }.maxByOrNull { it.height }
            if (best == null) {
                okResults.add(OkResult(tt, "video/${cand.vi}", vresp.code, vresp.wafAction, true, encStr, "hls-only"))
                Log.w(TAG, "[$tt]   OKHTTP cand#$idx ${cand.vi} HLS-only - next")
                continue
            }
            val range = rangeProbe(best.url)
            Log.i(TAG, "[$tt] OKHTTP CHOSE ${cand.vi} def=${best.def} httpRange=${range.first} ${range.second} url=${trimUrl(best.url)}")
            okResults.add(OkResult(tt, "video/${cand.vi}", vresp.code, vresp.wafAction, true, encStr, "chose=${best.def} range=${range.first}"))
            return
        }
        Log.w(TAG, "[$tt] OKHTTP exhausted candidates without a playable trailer")
    }

    private suspend fun okhttpDumpVideo(vi: String) {
        Log.i(TAG, "[$vi] ---- OKHTTP dump begin ----")
        val url = "https://www.imdb.com/video/$vi"
        val resp = okhttpGet(url, REFERER)
        val nd = extractNextData(resp.body)
        val info = parseVideo(nd, resp.body)
        val encStr = info?.encodings?.joinToString(",") { "${it.def}${if (it.mp4) "" else "/hls"}" } ?: ""
        logOk("$vi", url, resp, nd, if (info != null && info.encodings.isNotEmpty()) "ok" else "fail")
        if (resp.code != 200) {
            okResults.add(OkResult(vi, "video", resp.code, resp.wafAction, false, "", "non-200"))
            Log.w(TAG, "[$vi] OKHTTP non-200 (${resp.code}) waf=${resp.wafAction ?: "-"}")
            return
        }
        if (info == null || info.encodings.isEmpty()) {
            okResults.add(OkResult(vi, "video", resp.code, resp.wafAction, false, "", "no-encodings"))
            Log.w(TAG, "[$vi] OKHTTP no playbackURLs parsed")
            return
        }
        Log.i(TAG, "[$vi] OKHTTP type=${info.contentType ?: "?"} name=\"${info.name ?: "?"}\"")
        dumpEncodings("OK:$vi", info.encodings)
        okResults.add(OkResult(vi, "video", resp.code, resp.wafAction, true, encStr, "encodings=${info.encodings.size}"))
    }

    // v8: block until the box can actually reach IMDb (any HTTP code > 0), or give up after maxMs.
    // Warms the shared OS resolver + connection pool for BOTH arms before any measured fetch, and
    // the WARMUP line records how long the cold-start resolver race lasts.
    private suspend fun warmUpNetwork(maxMs: Long = 30_000L) {
        val start = System.currentTimeMillis()
        var attempt = 0
        while (System.currentTimeMillis() - start < maxMs) {
            attempt++
            val r = okhttpGet("https://www.imdb.com/", REFERER)
            if (r.code > 0) {
                Log.i(TAG, "WARMUP ok after $attempt attempt(s) in ${System.currentTimeMillis() - start}ms code=${r.code} waf=${r.wafAction ?: "-"}")
                return
            }
            Log.w(TAG, "WARMUP attempt $attempt failed (${r.error}) - backoff 1500ms")
            delay(1500)
        }
        Log.w(TAG, "WARMUP gave up after ${maxMs}ms - proceeding anyway")
    }

    private fun okSummarise() {
        val ok200 = okResults.count { it.code == 200 }
        val challenged = okResults.count { it.wafAction != null || it.code == 202 || it.code == 403 }
        val parsed = okResults.count { it.parsed }
        Log.i(TAG, "=== OKHTTP SUMMARY: ${okResults.size} fetches | 200 $ok200 | challenged $challenged | parsed $parsed ===")
        for (r in okResults) {
            Log.i(TAG, "  OK ${r.id}/${r.phase} code=${r.code} waf=${r.wafAction ?: "-"} " +
                "parsed=${r.parsed} enc=[${r.encodings}] note=${r.note}")
        }
    }

    private suspend fun runAb(tts: List<String>) {
        Log.i(TAG, "=== v7 A/B START (OkHttp-first, WebView-second) - ${tts.size} titles ===")
        withContext(Dispatchers.IO) { warmUpNetwork() }
        for (tt in tts) {
            try { withContext(Dispatchers.IO) { okhttpProbeOne(tt) } }
            catch (e: Exception) { Log.e(TAG, "[$tt] OKHTTP threw: ${e.message}") }
            delay(500)
            try { probeOne(tt) }
            catch (e: Exception) {
                Log.e(TAG, "[$tt] WebView probe threw: ${e.message}")
                results.add(Result(tt, false, null, null, null, 0, 0, false, "exception:${e.message}"))
            }
            delay(500)
        }
        okSummarise()
        summarise()
        Log.i(TAG, "=== v7 A/B DONE ===")
    }

    private suspend fun runAbVis(vis: List<String>) {
        Log.i(TAG, "=== v7 A/B vis-dump START - ${vis.size} video ids ===")
        withContext(Dispatchers.IO) { warmUpNetwork() }
        for (vi in vis) {
            try { withContext(Dispatchers.IO) { okhttpDumpVideo(vi) } }
            catch (e: Exception) { Log.e(TAG, "[$vi] OKHTTP dump threw: ${e.message}") }
            delay(500)
            try {
                if (webViewDead) recreateWebView()
                withTimeoutOrNull(TITLE_BUDGET_MS) { dumpVideo(vi); true } ?: Log.w(TAG, "[$vi] TITLE-TIMEOUT")
            } catch (e: Exception) { Log.e(TAG, "[$vi] dump threw: ${e.message}") }
            delay(500)
        }
        okSummarise()
        Log.i(TAG, "=== v7 A/B vis-dump DONE ===")
    }

    private suspend fun runOkhttpTts(tts: List<String>) {
        Log.i(TAG, "=== v7 OkHttp-only START - ${tts.size} titles ===")
        withContext(Dispatchers.IO) { warmUpNetwork() }
        for (tt in tts) {
            try { withContext(Dispatchers.IO) { okhttpProbeOne(tt) } }
            catch (e: Exception) { Log.e(TAG, "[$tt] OKHTTP threw: ${e.message}") }
            delay(500)
        }
        okSummarise()
        Log.i(TAG, "=== v7 OkHttp-only DONE ===")
    }

    private suspend fun runOkhttpVis(vis: List<String>) {
        Log.i(TAG, "=== v7 OkHttp-only vis-dump START - ${vis.size} video ids ===")
        withContext(Dispatchers.IO) { warmUpNetwork() }
        for (vi in vis) {
            try { withContext(Dispatchers.IO) { okhttpDumpVideo(vi) } }
            catch (e: Exception) { Log.e(TAG, "[$vi] OKHTTP dump threw: ${e.message}") }
            delay(500)
        }
        okSummarise()
        Log.i(TAG, "=== v7 OkHttp-only vis-dump DONE ===")
    }

    private data class Candidate(val vi: String, val type: String?, val name: String?, val score: Int)
    private data class Encoding(val def: String, val height: Int, val mp4: Boolean, val url: String)
    private data class Result(
        val tt: String, val waf: Boolean, val vi: String?, val type: String?,
        val chosenDef: String?, val decodeH: Int, val ttff: Long, val ok: Boolean, val note: String
    )

    private val results = mutableListOf<Result>()
    private var lastChosenUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val doBind = intent?.getStringExtra("bind")?.trim()?.lowercase() == "on"
        if (doBind) bindToEthernet() else Log.i(TAG, "=== bind=off (process on system-default resolver) ===")
        val root = FrameLayout(this)
        setContentView(root)
        surface = SurfaceView(this)
        root.addView(surface, ViewGroup.LayoutParams(MATCH, MATCH))
        rootView = root
        currentHost = intent?.getStringExtra("host")?.trim()?.lowercase()?.takeIf { it == "detached" } ?: "attached"
        Log.i(TAG, "=== v10 host=$currentHost ===")
        webView = buildHostedWebView(currentHost, root)
        configureWebView(webView)

        val resolveTt = intent?.getStringExtra("resolve")?.trim()?.takeIf { it.startsWith("tt") }
        if (resolveTt != null) {
            Log.i(TAG, "=== Phase1a RESOLVE test: $resolveTt ===")
            scope.launch { runResolveTest(resolveTt) }
            return
        }

        val mode = intent?.getStringExtra("mode")?.trim()?.lowercase()
            ?.takeIf { it == "ab" || it == "okhttp" || it == "webview" } ?: "ab"
        Log.i(TAG, "=== v7 probe mode=$mode ===")
        val vis = intent?.getStringExtra("vis")
            ?.split(",")?.map { it.trim() }?.filter { it.startsWith("vi") }?.takeIf { it.isNotEmpty() }
        if (vis != null) {
            Log.i(TAG, "=== IMDb trailer probe v6 START (vis-dump) — ${vis.size} video ids — UA=$UA")
            scope.launch { when (mode) { "webview" -> runVis(vis); "okhttp" -> runOkhttpVis(vis); else -> runAbVis(vis) } }
            return
        }

        val tts = intent?.getStringExtra("tts")
            ?.split(",")?.map { it.trim() }?.filter { it.startsWith("tt") }?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_TTS

        Log.i(TAG, "=== IMDb trailer probe v6 START — ${tts.size} titles — UA=$UA")
        scope.launch { when (mode) { "webview" -> runAll(tts); "okhttp" -> runOkhttpTts(tts); else -> runAb(tts) } }
    }

    // v10 (Phase 0): build the WebView per host config. attached = activity-window WebView
    // (v6 baseline, proven to clear the awsWaf JS challenge). detached = app-context, windowless,
    // measured WebView -- the config a context-less TrailerService singleton must use in production
    // (no Activity, no overlay permission). Phase 0 measures whether detached still passes the WAF.
    private fun buildHostedWebView(host: String, root: ViewGroup): WebView {
        if (host == "detached") {
            val wv = try {
                WebView(applicationContext)
            } catch (e: Exception) {
                Log.e(TAG, "DETACHED app-context WebView construction threw: ${e.message} - fallback to activity ctx")
                WebView(this)
            }
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
            Log.i(TAG, "built DETACHED app-context WebView ${w}x$h (windowless, not attached to any window)")
            return wv
        }
        val wv = WebView(this)
        root.addView(wv, FrameLayout.LayoutParams(1, 1).apply { gravity = Gravity.TOP or Gravity.START })
        Log.i(TAG, "built ATTACHED activity WebView 1x1 (added to content view)")
        return wv
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
        wv.webViewClient = object : WebViewClient() {
            @RequiresApi(26)
            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                Log.e(TAG, "WebView render process gone (crash=${detail?.didCrash()}) — flagging for recreate")
                webViewDead = true
                return true // handled; do not let the system kill the app
            }
            override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                if (request?.isForMainFrame == true)
                    Log.w(TAG, "mainframe HTTP ${errorResponse?.statusCode} ${errorResponse?.reasonPhrase} url=${request.url}")
            }
            override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "mainframe NET err=${error?.errorCode} \"${error?.description}\" url=${request.url}")
                    when (error?.errorCode) {
                        WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT,
                        WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO -> netErrorSeen = true
                    }
                }
            }
        }
    }

    private suspend fun recreateWebView() = withContext(Dispatchers.Main) {
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            runCatching { webView.destroy() }
            webView = buildHostedWebView(currentHost, rootView ?: FrameLayout(this@ImdbTrailerProbeActivity))
            configureWebView(webView)
            webViewDead = false
            Log.i(TAG, "WebView recreated (host=$currentHost) after wedge/render-gone")
        }
        Unit
    }

    /** Best-effort: pin the process to a validated Ethernet network. Helps OkHttp probes for sure;
     *  whether it reaches the WebView's out-of-process network service is unverified. */
    private fun bindToEthernet() {
        runCatching {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val eth = cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
            if (eth != null) {
                val ok = cm.bindProcessToNetwork(eth)
                Log.i(TAG, "bound process to Ethernet network=$eth ok=$ok")
            } else {
                Log.w(TAG, "no validated Ethernet network found — leaving process on default")
            }
        }.onFailure { Log.w(TAG, "bindToEthernet failed: ${it.message}") }
    }

    private suspend fun runAll(tts: List<String>) {
        for (tt in tts) {
            try {
                probeOne(tt)
            } catch (e: Exception) {
                Log.e(TAG, "[$tt] probe threw: ${e.message}")
                results.add(Result(tt, false, null, null, null, 0, 0, false, "exception:${e.message}"))
            }
            delay(500)
        }
        summarise()
        Log.i(TAG, "=== IMDb trailer probe v6 DONE ===")
    }

    private suspend fun runVis(vis: List<String>) {
        for (vi in vis) {
            try {
                if (webViewDead) recreateWebView()
                withTimeoutOrNull(TITLE_BUDGET_MS) { dumpVideo(vi); true } ?: Log.w(TAG, "[$vi] TITLE-TIMEOUT")
            } catch (e: Exception) {
                Log.e(TAG, "[$vi] dump threw: ${e.message}")
            }
            delay(500)
        }
        Log.i(TAG, "=== IMDb trailer probe v6 DONE ===")
    }

    private suspend fun dumpVideo(vi: String) {
        Log.i(TAG, "[$vi] ---- dump begin ----")
        val page = loadAndSettle(vi, "https://www.imdb.com/video/$vi", expectKey = vi)
        if (page == null) { diagnoseFailure(vi, "video", vi); Log.w(TAG, "[$vi] video page never committed"); return }
        val info = withContext(Dispatchers.Default) { parseVideo(page.nextData, page.html) }
        if (info == null || info.encodings.isEmpty()) { Log.w(TAG, "[$vi] no playbackURLs parsed"); return }
        Log.i(TAG, "[$vi] type=${info.contentType ?: "?"} name=\"${info.name ?: "?"}\"")
        dumpEncodings(vi, info.encodings)
        val best = info.encodings.filter { it.mp4 }.maxByOrNull { it.height }
        if (best != null) { Log.i(TAG, "[$vi] playing best mp4 def=${best.def}"); playAndMeasure(vi, best.url, best.def) }
    }

    /** Log every encoding with size/reachability + the full de-escaped signed URL (copy-ready). */
    private suspend fun dumpEncodings(label: String, encodings: List<Encoding>) = withContext(Dispatchers.IO) {
        for (e in encodings) {
            val url = normalise(e.url)
            if (e.mp4) {
                val (code, info) = rangeProbe(url)
                Log.i(TAG, "[$label] DUMP def=${e.def} h=${e.height} mp4 http=$code size=$info")
            } else {
                Log.i(TAG, "[$label] DUMP def=${e.def} h=${e.height} hls (not size-probed)")
            }
            Log.i(TAG, "[$label] DUMP url=$url")
        }
    }

    private suspend fun probeOne(tt: String) {
        if (webViewDead) recreateWebView()
        val completed = withTimeoutOrNull(TITLE_BUDGET_MS) { probeOneInner(tt); true }
        if (completed == null) {
            Log.w(TAG, "[$tt] TITLE-TIMEOUT (exceeded ${TITLE_BUDGET_MS / 1000}s budget) — moving on")
            results.add(Result(tt, false, null, null, null, 0, 0, false, "title-budget-timeout"))
            webViewDead = true // force a fresh WebView for the next title
        }
    }

    private suspend fun probeOneInner(tt: String) {
        Log.i(TAG, "[$tt] ---- begin ----")

        // Step 1: title page → candidate trailer vi list.
        val titleUrl = "https://www.imdb.com/title/$tt/"
        val t0 = System.currentTimeMillis()
        val titlePage = loadAndSettle(tt, titleUrl, expectKey = tt) ?: run {
            diagnoseFailure(tt, "title", tt)
            Log.w(TAG, "[$tt] WAF=FAIL discovery: title page never committed (timeout)")
            results.add(Result(tt, false, null, null, null, 0, 0, false, "title-timeout"))
            return
        }
        val wafSeen = WAF_MARKERS.any { titlePage.html.contains(it, ignoreCase = true) }

        val candidates = withContext(Dispatchers.Default) { selectCandidates(titlePage.nextData, titlePage.html) }
        if (candidates.isEmpty()) {
            Log.w(TAG, "[$tt] WAF=PASS but NO_VIDEO_NODES on title page (coverage miss) discoveryMs=${System.currentTimeMillis() - t0}")
            results.add(Result(tt, true, null, null, null, 0, 0, false, "no-video-nodes"))
            return
        }
        Log.i(TAG, "[$tt] WAF=PASS wafChallengeSeen=$wafSeen candidates=" +
            candidates.take(MAX_CANDIDATES).joinToString(" | ") { "${it.vi}(${it.type ?: "?"}/${it.name ?: "?"}:${it.score})" })

        // Step 2: resolve candidates in score order until one yields a playable trailer.
        for ((idx, cand) in candidates.take(MAX_CANDIDATES).withIndex()) {
            val videoUrl = "https://www.imdb.com/video/${cand.vi}"
            val videoPage = loadAndSettle(tt, videoUrl, expectKey = cand.vi)
            if (videoPage == null) {
                diagnoseFailure(tt, "video/${cand.vi}", cand.vi)
                Log.w(TAG, "[$tt]   cand#$idx ${cand.vi} video page never committed — next")
                continue
            }
            val vinfo = withContext(Dispatchers.Default) { parseVideo(videoPage.nextData, videoPage.html) }
            if (vinfo == null || vinfo.encodings.isEmpty()) {
                Log.w(TAG, "[$tt]   cand#$idx ${cand.vi} no playbackURLs parsed — next")
                continue
            }
            val realType = vinfo.contentType ?: cand.type
            Log.i(TAG, "[$tt]   cand#$idx ${cand.vi} type=${realType ?: "?"} name=\"${vinfo.name ?: cand.name ?: "?"}\" " +
                "menu=[${vinfo.encodings.joinToString(",") { "${it.def}${if (it.mp4) "" else "/hls"}" }}]")
            dumpEncodings("$tt/${cand.vi}", vinfo.encodings)

            val mp4s = vinfo.encodings.filter { it.mp4 }
            if (mp4s.isEmpty()) {
                Log.w(TAG, "[$tt]   cand#$idx ${cand.vi} HLS-only, no progressive mp4 — next")
                continue
            }
            val best = mp4s.maxByOrNull { it.height } ?: mp4s.first()

            // Duplicate-URL guard: identical chosen URL as the previous title => suspect stale/shared asset.
            val suspect = if (best.url == lastChosenUrl) " SUSPECT_STALE" else ""
            lastChosenUrl = best.url

            val code = withContext(Dispatchers.IO) { rangeProbe(best.url) }
            Log.i(TAG, "[$tt] CHOSE ${cand.vi} def=${best.def} httpRange=${code.first} ${code.second}$suspect url=${trimUrl(best.url)}")
            if (code.first != 200 && code.first != 206) {
                Log.w(TAG, "[$tt]   chosen url not reachable (${code.first}) — next candidate")
                continue
            }

            val play = playAndMeasure(tt, best.url, best.def)
            results.add(Result(tt, true, cand.vi, realType, best.def, play.first, play.second, play.first > 0, if (suspect.isBlank()) "ok" else "suspect-stale"))
            return
        }
        Log.w(TAG, "[$tt] exhausted ${minOf(MAX_CANDIDATES, candidates.size)} candidates without a playable trailer")
        results.add(Result(tt, true, candidates.first().vi, candidates.first().type, null, 0, 0, false, "no-playable-candidate"))
    }

    // ---- title-page selection -----------------------------------------------------------------

    private fun selectCandidates(nextData: String?, html: String): List<Candidate> {
        val nodes = LinkedHashMap<String, Pair<String?, String?>>() // vi -> (type, name)
        if (nextData != null) {
            try { collectVideoNodes(JSONObject(nextData), nodes) } catch (_: Exception) {}
        }
        // Fallback: regex over html in DOM order if the structured scan found nothing.
        if (nodes.isEmpty()) {
            for (m in Regex("""vi\d{6,}""").findAll(html)) nodes.putIfAbsent(m.value, null to null)
        }
        return nodes.entries.map { (vi, tn) ->
            val (type, name) = tn
            Candidate(vi, type, name, scoreCandidate(type, name))
        }.sortedByDescending { it.score }
    }

    /** Recursively walk the __NEXT_DATA__ tree; record objects whose "id" is a vi-id, with type/name if present. */
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

    // ---- video-page parse (confirmed schema) --------------------------------------------------

    private data class VideoInfo(val contentType: String?, val name: String?, val encodings: List<Encoding>)

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
        // Fallback: scrape any signed mp4 from the raw page (definition unknown).
        val u = CDN_MP4.find(normalise(html))?.value ?: return null
        return VideoInfo(null, null, listOf(Encoding("unknown", 0, true, u)))
    }

    // ---- WebView load + settle (stale-DOM guarded) --------------------------------------------

    private data class Page(val html: String, val nextData: String?)

    private suspend fun loadAndSettle(tt: String, url: String, expectKey: String): Page? {
        repeat(LOAD_ATTEMPTS) { attempt ->
            netErrorSeen = false
            val page = loadAttempt(tt, url, expectKey)
            if (page != null) return page
            if (attempt < LOAD_ATTEMPTS - 1) {
                Log.w(TAG, "[$tt] load attempt ${attempt + 1}/$LOAD_ATTEMPTS failed (netErr=$netErrorSeen) — fresh WebView + retry")
                recreateWebView()
                delay(500)
            }
        }
        return null
    }

    private suspend fun loadAttempt(tt: String, url: String, expectKey: String): Page? {
        withContext(Dispatchers.Main) {
            webView.loadUrl("about:blank")
        }
        delay(200)
        withContext(Dispatchers.Main) {
            webView.loadUrl(url, mapOf("Referer" to REFERER))
        }
        var lastLen = -1
        var stable = 0
        var nullStreak = 0
        repeat(POLL_MAX) {
            delay(POLL_MS)
            if (netErrorSeen) {   // main-frame DNS/connect error — fail this attempt fast, retry gets a fresh WebView
                Log.w(TAG, "[$tt] main-frame net error during load — aborting attempt")
                return null
            }
            val href = readHref()
            if (href == null) {   // evalJs timed out — WebView may be wedged
                if (++nullStreak >= MAX_NULL_STREAK) {
                    Log.w(TAG, "[$tt] WebView unresponsive (evalJs timed out ${MAX_NULL_STREAK}x) — abandoning page")
                    webViewDead = true
                    return null
                }
                return@repeat
            }
            nullStreak = 0
            if (!href.contains(expectKey)) return@repeat   // navigation not committed to target yet
            val html = readOuterHtml() ?: return@repeat
            val hasCdn = html.contains("imdb-video.media-imdb.com")
            val hasNext = html.contains("__NEXT_DATA__") || html.contains("videoPlaybackData") || html.contains("primaryVideos")
            if (hasCdn || (hasNext && html.length == lastLen)) {
                return Page(html, readNextData())
            }
            if (hasNext) { if (html.length == lastLen) stable++ else stable = 0; lastLen = html.length; if (stable >= 2) return Page(html, readNextData()) }
            else lastLen = html.length
        }
        // Committed but never fully settled — return best-effort if href reached the target.
        val href = readHref() ?: ""
        return if (href.contains(expectKey)) Page(readOuterHtml() ?: "", readNextData()) else null
    }

    private suspend fun readHref(): String? = evalJs("(function(){return location.href})()")
    private suspend fun readOuterHtml(): String? = evalJs("(function(){return document.documentElement.outerHTML})()")
    private suspend fun readNextData(): String? =
        evalJs("(function(){var e=document.getElementById('__NEXT_DATA__');return e?e.textContent:''})()")
            ?.takeIf { it.isNotBlank() }

    /** On a discovery failure, capture what the WebView is actually sitting on. */
    private suspend fun diagnoseFailure(tt: String, phase: String, expectKey: String) {
        val href = readHref() ?: "(null/eval-timeout)"
        val title = evalJs("(function(){return document.title||''})()") ?: "(null)"
        val body = (evalJs("(function(){return document.body?document.body.innerText:''})()") ?: "")
            .replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
        val hay = "$href $title $body".lowercase()
        val markers = WAF_MARKERS.filter { hay.contains(it.lowercase()) }
        Log.w(TAG, "[$tt] DIAG $phase expected=$expectKey href=$href title=\"$title\" markers=$markers")
        Log.w(TAG, "[$tt] DIAG $phase bodyHead=${body.take(300)}")
    }

    private suspend fun evalJs(js: String): String? = withTimeoutOrNull(MAX_EVAL_MS) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont: CancellableContinuation<String?> ->
                try {
                    webView.evaluateJavascript(js) { raw -> if (cont.isActive) cont.resume(decodeJsString(raw)) }
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

    private fun normalise(s: String): String =
        s.replace("\\u002F", "/").replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")

    // ---- helpers ------------------------------------------------------------------------------

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

    private fun rangeProbe(url: String): Pair<Int, String> = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", UA).header("Referer", REFERER).header("Range", "bytes=0-1").get().build()
        http.newCall(req).execute().use { r ->
            r.code to (r.header("Content-Range") ?: r.header("Content-Length") ?: "?")
        }
    } catch (e: Exception) { -1 to (e.message ?: "error") }

    /** Returns (decodeHeight, ttffMs). Fresh player per title; listener + player torn down in finally. */
    private suspend fun playAndMeasure(tt: String, url: String, def: String): Pair<Int, Long> = withContext(Dispatchers.Main) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(UA).setDefaultRequestProperties(mapOf("Referer" to REFERER))
        val source = ProgressiveMediaSource.Factory(httpFactory).createMediaSource(MediaItem.fromUri(url))
        val p = ExoPlayer.Builder(this@ImdbTrailerProbeActivity).build()
        p.setVideoSurfaceView(surface)
        var decodeH = 0
        var ttff = 0L
        val start = System.currentTimeMillis()
        try {
            withTimeoutOrNull(20_000L) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val l = object : Player.Listener {
                        override fun onVideoSizeChanged(v: VideoSize) {
                            if (v.height > 0) decodeH = v.height
                            Log.i(TAG, "[$tt] PLAY decodeSize=${v.width}x${v.height} (def=$def)")
                        }
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY && cont.isActive) {
                                ttff = System.currentTimeMillis() - start
                                if (decodeH == 0) decodeH = p.videoFormat?.height ?: 0
                                Log.i(TAG, "[$tt] PLAY=OK ttffMs=$ttff formatSize=${p.videoFormat?.width ?: -1}x${p.videoFormat?.height ?: -1} (def=$def)")
                                cont.resume(true)
                            }
                        }
                        override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                            Log.e(TAG, "[$tt] PLAY=FAIL ${e.errorCodeName}: ${e.message} (def=$def)")
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    p.addListener(l)
                    cont.invokeOnCancellation { p.removeListener(l) }
                    p.setMediaSource(source); p.prepare(); p.playWhenReady = true
                } ?: false
            } ?: Log.w(TAG, "[$tt] PLAY=TIMEOUT (no READY/error within 20s) (def=$def)")
            delay(2500) // let real frames decode
        } finally {
            p.release()
        }
        decodeH to ttff
    }

    private fun trimUrl(u: String): String {
        val q = u.indexOf('?'); return if (q > 0) u.substring(0, q) + "?<signed>" else u
    }

    private fun summarise() {
        val waf = results.count { it.waf }
        val played = results.count { it.ok }
        val hd = results.count { it.decodeH >= 1080 }
        Log.i(TAG, "=== SUMMARY: ${results.size} titles | WAF pass $waf | played $played | >=1080p $hd ===")
        for (r in results) {
            Log.i(TAG, "  ${r.tt} waf=${r.waf} vi=${r.vi ?: "-"} type=${r.type ?: "-"} def=${r.chosenDef ?: "-"} " +
                "decodeH=${r.decodeH} ttffMs=${r.ttff} ok=${r.ok} note=${r.note}")
        }
    }

    private suspend fun runResolveTest(tt: String) {
        val resolver = try {
            EntryPointAccessors.fromApplication(applicationContext, ImdbResolverEntryPoint::class.java).imdbTrailerResolver()
        } catch (e: Exception) {
            Log.e(TAG, "RESOLVE could not obtain resolver singleton: ${e.message}")
            return
        }
        val t0 = System.currentTimeMillis()
        val src = resolver.resolve(tt, null)
        val ms = System.currentTimeMillis() - t0
        if (src == null) {
            Log.w(TAG, "RESOLVE $tt -> null (${ms}ms)")
            Log.i(TAG, "=== Phase1a RESOLVE DONE ===")
            return
        }
        Log.i(TAG, "RESOLVE $tt -> ${trimUrl(src.videoUrl)} (${ms}ms)")
        val code = withContext(Dispatchers.IO) { rangeProbe(src.videoUrl) }
        Log.i(TAG, "RESOLVE $tt range=${code.first} ${code.second}")
        Log.i(TAG, "=== Phase1a RESOLVE DONE ===")
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        runCatching { getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null) }
        runCatching { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImdbResolverEntryPoint {
    fun imdbTrailerResolver(): ImdbTrailerResolver
}

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
