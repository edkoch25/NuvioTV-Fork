package com.nuvio.tv.ui.screens.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.InterruptedIOException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import com.nuvio.tv.data.local.PlayerSettings
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import android.os.SystemClock

import java.nio.ByteBuffer

/**
 * A DataSource that downloads progressive files using multiple parallel HTTP range requests.
 *
 * Each individual TCP connection may be limited to ~100 Mbps (due to CDN per-connection limits
 * or Java/Okio networking overhead). By downloading different byte ranges in parallel across
 * multiple connections, we can multiply the effective throughput (e.g., 3 connections ≈ 300 Mbps).
 *
 * Uses a buffer pool to reuse ByteArrays or native ByteBuffers and avoid GC churn from large object allocations.
 *
 * Only used for progressive downloads (MKV, MP4). HLS/DASH already handle chunked parallel downloads.
 */
@UnstableApi
internal class ParallelRangeDataSource(
    private val upstreamFactory: OkHttpDataSource.Factory,
    private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
    private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
    private val useNativeMemory: Boolean = false,
    // nt-tier3: how many chunks may be in flight ahead of the read cursor. Computed from
    // the device-RAM native budget in PlayerMediaSourceFactory. Default preserves the
    // historical connections+1 behaviour for any caller that does not set it.
    private val prefetchDepthChunks: Int = parallelConnections + 1,
    private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
    private val onResolvedUri: (Uri?) -> Unit = {},
    private val consumeBootstrapCache: (DataSpec) -> BootstrapCacheEntry? = { null },
    private val updateBootstrapCache: (BootstrapCacheEntry?) -> Unit = {},
    // S1: allow a warm reopen into an un-fetched region to be served by a bounded
    // single-connection GET instead of a whole aligned chunk. Gated OFF for MP4
    // session mode, whose scatter-read cursors rely on retained whole chunks to
    // make repeat visits free -- that path has no measurement yet. Default true
    // preserves the new behaviour for any caller that does not set it.
    private val allowContinuationReopen: Boolean = true
) : DataSource, androidx.media3.common.ByteBufferDataReader {

    companion object {
        private const val TAG = "ParallelRangeDS"
        private const val READ_BUFFER_SIZE = 64 * 1024 // 64KB read buffer for chunk downloads
        // S1b (measured 23 Jul 2026): this window is read SYNCHRONOUSLY inside
        // open() before it returns, on a cold connection still in TCP slow-start.
        // The Matroska head sniff consumed 11,209 bytes before seeking to the
        // tail, and the full-open path then discards the window -- the same bytes
        // arrive again in chunk 0, which is already downloading in the background.
        // 256 KB keeps ~23x headroom over the observed sniff; over-run is safe by
        // construction (an exhausted window falls through to the chunk path).
        private const val BOOTSTRAP_READ_BYTES = 256L * 1024L

        // nt9: backstop for the in-flight wait, not a schedule. Cold TTFB
        // topped out at 985 ms and response headers at ~1,300 ms across the
        // 26-27 Jul captures, so a download that has produced nothing after
        // three seconds is not merely slow to start; exceeding the cap just
        // restores the pre-nt7 blocking path, which has its own 60 s bound.
        private const val IN_FLIGHT_WAIT_CAP_MS = 3_000L
        private const val IN_FLIGHT_POLL_MS = 2L

        // 3a: body-stall watchdog. A chunk whose delivery RATE over
        // HEDGE_WINDOW_MS falls below HEDGE_STALL_RATE_BPS, after
        // HEDGE_MIN_OPEN_MS since open, is abandoned and re-fetched on a
        // fresh connection (same slot -> connection budget unchanged), up
        // to HEDGE_MAX_RESTARTS, then one final watchdog-disabled attempt.
        private const val HEDGE_MIN_OPEN_MS = 1_500L
        private const val HEDGE_WINDOW_MS = 2_000L
        private const val HEDGE_STALL_RATE_BPS = 256L * 1024L
        private const val HEDGE_MAX_RESTARTS = 3
        // Source-aware hedge (calibrated 2026-08-25). A single-window trip at
        // 256 KB/s false-fired ~28x over 5 min on a clean AIOStreams Usenet play:
        // the NNTP engine delivers in bursts (a ~1 MB rush then a fetch-the-next-
        // articles pause), so a lone sub-256 window is normal cadence, not a stall.
        // Measured: every false fire sat 211-261 KB/s (none below), positionally
        // locked at watermark ~1.05 MB -> a cadence artefact. So Usenet uses a
        // lower rate floor (below the 211 KB/s observed floor) AND requires two
        // consecutive stalled windows before restarting. CDN/debrid keeps the
        // proven single-window 256 KB/s behaviour unchanged (zero regression).
        private const val HEDGE_STALL_RATE_CDN = 256L * 1024L
        private const val HEDGE_STALL_RATE_USENET = 128L * 1024L
        private const val HEDGE_WINDOWS_CDN = 1
        private const val HEDGE_WINDOWS_USENET = 2

        private val readBufferLocal = object : ThreadLocal<ByteArray>() {
            override fun initialValue(): ByteArray = ByteArray(READ_BUFFER_SIZE)
        }

        // A single, shared, lazy cached thread pool with bounded max threads to prevent OOM/pthread_create failure
        private val sharedExecutor: ExecutorService by lazy {
            val threadFactory = ThreadFactory { runnable ->
                Thread(runnable, "parallel-ds-worker").apply {
                    priority = Thread.NORM_PRIORITY
                    isDaemon = true
                }
            }
            ThreadPoolExecutor(
                32, 64, 60L, TimeUnit.SECONDS,
                java.util.concurrent.LinkedBlockingQueue<Runnable>(),
                threadFactory,
                ThreadPoolExecutor.DiscardPolicy()
            ).apply {
                allowCoreThreadTimeOut(true)
            }
        }

        private val activeInstances = java.util.concurrent.atomic.AtomicInteger(0)
        private val globalBufferPool = ConcurrentHashMap<Long, ConcurrentLinkedDeque<PooledBuffer>>()

        private fun freeDirectBuffer(buffer: ByteBuffer) {
            if (!buffer.isDirect) return
            try {
                val cleanerMethod = buffer.javaClass.getMethod("cleaner")
                cleanerMethod.isAccessible = true
                val cleaner = cleanerMethod.invoke(buffer)
                if (cleaner != null) {
                    val cleanMethod = cleaner.javaClass.getMethod("clean")
                    cleanMethod.isAccessible = true
                    cleanMethod.invoke(cleaner)
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to explicitly free direct buffer: ${e.message}")
            }
        }

        // ── nt7: session-owned chunk downloads ─────────────────────────────
        // ExoPlayer creates a new instance of this class for every seek. nt6
        // retained COMPLETED chunks across that boundary, but in-flight
        // downloads still died with the instance (the download loop checked
        // the instance's closed flag) — measured on a scatter-read MP4 as the
        // same 32 MB chunks restarting up to 30 times (192 starts / 66
        // completions in ~2 min). nt7 moves download ownership to a
        // companion-level session: futures (done AND in-flight) belong to the
        // session, downloads run to completion regardless of instance
        // lifetime, and instances are thin readers over the shared session.
        // Eviction is touch-LRU under a memory-tiered cap; teardown happens on
        // stream change, idle TTL, or player shutdown.
        private const val RETAINED_SESSION_TTL_MS = 45_000L
        // nt7 earned prefetch: sequential bytes an open must serve before
        // lookahead prefetch is granted.
        private const val EARNED_PREFETCH_BYTES = 1L * 1024L * 1024L
        // Never evict a chunk touched in the last 2 s: closes the narrow race
        // where an overlapping old instance is still copying from the buffer.
        private const val EVICTION_TOUCH_GUARD_MS = 2_000L
        // pre-nt3 (re-derived review fix): a conforming DataSource blocks
        // rather than returning 0 for a positive-length read; tolerate a few
        // zero-progress reads, then fail the chunk instead of spinning forever.
        private const val MAX_CONSECUTIVE_ZERO_READS = 3
        // nt5: reader-blocked chunk escalation (spec 2026-08-26). Fire once the
        // reader has waited this long on a chunk whose in-flight watermark has
        // not moved (hung request -- invisible to the 3a body-rate watchdog);
        // after firing, extend the in-flight poll so the duplicate's first
        // bytes can serve the reader progressively.
        private const val ESCALATE_AFTER_MS = 2_000L
        private const val ESCALATE_POLL_EXTENSION_MS = 3_000L

        // nt-tier2: HTTP 429/503 is server-side rate-limiting, not a stalled
        // socket. Back off before retrying (immediate retry just re-hits the
        // limit). nt6(0.8.5): the response is now shaped like congestion
        // control — waits honour a server-stated Retry-After and escalate
        // across episodes, and prefetch depth adapts multiplicatively down /
        // additively up (AIMD) so a session converges just under the
        // provider's actual request budget. Reachable from any chunk-session
        // path (parallel-on and MP4 session mode).
        private const val RATE_LIMIT_MAX_BACKOFF_RETRIES = 3
        private const val RATE_LIMIT_BACKOFF_BASE_MS = 500L
        // Cap for GUESSED waits in a first episode. Deliberately short:
        // playback is real-time, so long speculative sleeps on a download
        // thread trade a maybe-429 for a certain stall.
        private const val RATE_LIMIT_BACKOFF_CYCLE_CAP_MS = 3_000L
        // Absolute ceiling for any single wait, including a server-stated
        // Retry-After — a broken or hostile header must never camp a
        // real-time pipeline.
        private const val RATE_LIMIT_WAIT_HARD_CAP_MS = 15_000L
        private const val RATE_LIMIT_BACKOFF_JITTER_MS = 250L
        private const val RATE_LIMIT_SLEEP_SLICE_MS = 100L

        // nt6(0.8.5): additive-recovery probe cadence — +1 depth per quiet
        // interval; the interval doubles on every re-trip during the climb
        // (gentler probing of a strict provider) and resets once the cap
        // fully clears. Replaces the nt3 45s/360s boolean-clamp cooldown.
        private const val RATE_LIMIT_DEPTH_STEP_BASE_MS = 10_000L
        private const val RATE_LIMIT_DEPTH_STEP_MAX_MS = 60_000L
        private const val RATE_LIMIT_ESCALATION_MAX = 5

        // nt6: HUD mirror of the rate-limit clamp. Written ONLY by the
        // download/read paths below; read by the stats overlay
        // (PlayerViewModel.samplePlaybackStats). Companion scope on purpose:
        // playback runs one chunk session at a time, and the overlay must
        // see state that survives ExoPlayer recreating DataSource instances
        // across seeks. Reset where a fresh session is created so a stale
        // clamp never carries across titles.
        @Volatile var hudClampLatched: Boolean = false
        @Volatile var hudClampTrips: Int = 0
        @Volatile var hudClampLastHitAtMs: Long = 0L
        // nt6(0.8.5): AIMD state for the HUD row — current depth cap vs the
        // configured depth ("depth 2/5"), and the uptime when the next +1
        // step becomes eligible. Written only by ChunkSession's rate-limit
        // methods; zeroed with the other mirrors on fresh-session creation.
        @Volatile var hudDepthCap: Int = 0
        @Volatile var hudDepthConfigured: Int = 0
        @Volatile var hudNextStepAtMs: Long = 0L
        // nt19: HUD mirror of the 3a body-stall hedge. hudHedgeRestarts counts
        // fresh-connection restart attempts this session; hudHedgeExhausted latches if
        // any chunk hit the restart cap. Written only by downloadChunkWithStallRestart;
        // zeroed on fresh-session creation with the other mirrors.
        @Volatile var hudHedgeRestarts: Int = 0
        @Volatile var hudHedgeExhausted: Boolean = false

        // 3a: once-ever announce flag so a capture can confirm this build
        // is live even when nothing stalls.
        private val obsAnnounced = AtomicBoolean(false)

        /** Time until the next +1 depth step; 0 when not capped. HUD read only. */
        fun hudClampCooldownRemainingMs(nowUptimeMs: Long): Long {
            if (!hudClampLatched) return 0L
            return (hudNextStepAtMs - nowUptimeMs).coerceAtLeast(0L)
        }

        private class ChunkSession(
            val requestUri: Uri,
            // nt15: identity only, never used to build a request. Settable so a
            // session adopted from the pre-start can take the open-time headers;
            // otherwise every subsequent open would see a key mismatch.
            @Volatile var requestHeaders: Map<String, String>,
            val chunkSize: Long,
            val chunkCap: Int,
            // nt2: size of the live prefetch window (effectivePrefetchDepth).
            // Chunks in reader+1..reader+prefetchWindow are imminent and are
            // excluded from eviction so the reader never re-downloads them.
            val prefetchWindow: Int
        ) {
            @Volatile var resolvedUri: Uri? = null
            @Volatile var totalLength: Long = -1L
            val futures = ConcurrentHashMap<Long, CompletableFuture<DownloadedChunk>>()
            val lastTouch = ConcurrentHashMap<Long, Long>()
            val abandoned = AtomicBoolean(false)
            // nt5: chunks already escalated by the reader-blocked path (once per chunk).
            val escalatedChunks: MutableSet<Long> = ConcurrentHashMap.newKeySet()
            // nt6(0.8.5): AIMD rate-limit state. rateLimitDepthCap is the
            // multiplicative-decrease ceiling on prefetch depth
            // (Int.MAX_VALUE = uncapped): halved (never below 1) once per
            // rate-limited episode, stepped +1 per quiet probe interval, and
            // cleared once it climbs past the configured depth again.
            // lastRateLimitAtMs slides on EVERY observed 429/503 so quiet
            // time is measured from the last hit. rateLimitEscalation
            // persists across backoff cycles — the per-attempt ladder alone
            // resets every time the outer retry machinery re-enters it,
            // which is exactly how a hard limiter produces an indefinite
            // fixed-period hammer.
            val rateLimitDepthCap = AtomicInteger(Int.MAX_VALUE)
            @Volatile var lastRateLimitAtMs: Long = 0L
            @Volatile var lastDepthHalveAtMs: Long = 0L
            @Volatile var lastDepthStepAtMs: Long = 0L
            @Volatile var depthStepIntervalMs: Long = RATE_LIMIT_DEPTH_STEP_BASE_MS
            val rateLimitEscalation = AtomicInteger(0)
            val activeSources: MutableSet<DataSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()
            // nt7 (progressive reads): live views of downloads in
            // flight, keyed like futures. Entries are owned by the
            // download attempt that registered them (two-arg remove).
            val inFlight = ConcurrentHashMap<Long, InFlightChunk>()
            @Volatile var lastUsedAtMs: Long = SystemClock.uptimeMillis()

            fun touch(chunkIndex: Long) {
                val now = SystemClock.uptimeMillis()
                lastTouch[chunkIndex] = now
                lastUsedAtMs = now
            }

            // nt54: chunk index most recently SERVED to a reader. Creation-time
            // touches in ensureChunkScheduled deliberately do not update this;
            // only the read paths do, so eviction can distinguish "behind the
            // cursor" from "prefetched ahead". Last-write-wins on purpose: a
            // transient side-cursor read may move it for one read and the main
            // cursor restores it immediately after; the 2 s touch guard covers
            // that window.
            @Volatile var lastReadChunkIndex: Long = -1L

            fun noteRead(chunkIndex: Long) {
                touch(chunkIndex)
                lastReadChunkIndex = chunkIndex
            }

            /** Stamp an observed 429/503 so quiet time restarts. */
            fun noteRateLimitHit() {
                val now = SystemClock.uptimeMillis()
                lastRateLimitAtMs = now
                hudClampLastHitAtMs = now
                if (hudClampLatched) hudNextStepAtMs = now + depthStepIntervalMs
            }

            /**
             * nt6(0.8.5): record the start of one rate-limited episode:
             * stamp, bump the wait escalation, and apply ONE multiplicative
             * depth decrease (guarded so a burst of concurrent 429s across
             * download threads counts as a single congestion event, not a
             * cascade to 1). Returns the escalation level this episode's
             * waits should use (the pre-bump value, so a first-ever episode
             * still starts from the short ladder).
             */
            fun beginRateLimitEpisode(configuredDepth: Int): Int {
                val now = SystemClock.uptimeMillis()
                lastRateLimitAtMs = now
                hudClampLastHitAtMs = now
                val escalation = rateLimitEscalation.getAndUpdate {
                    (it + 1).coerceAtMost(RATE_LIMIT_ESCALATION_MAX)
                }
                if (now - lastDepthHalveAtMs >= 1_000L) {
                    val alreadyCapped = rateLimitDepthCap.get() < configuredDepth
                    val effective = rateLimitDepthCap.get().coerceAtMost(configuredDepth)
                    val halved = (effective / 2).coerceAtLeast(1)
                    if (halved < effective) {
                        rateLimitDepthCap.set(halved)
                        lastDepthHalveAtMs = now
                        // Gentler probing only when the provider pushes back
                        // AGAIN during the climb: the first trip keeps the base
                        // cadence, a re-trip doubles the interval.
                        if (alreadyCapped) {
                            depthStepIntervalMs =
                                (depthStepIntervalMs * 2).coerceAtMost(RATE_LIMIT_DEPTH_STEP_MAX_MS)
                        }
                        hudClampLatched = true
                        hudClampTrips += 1
                        hudDepthCap = halved
                        hudDepthConfigured = configuredDepth
                        hudNextStepAtMs = now + depthStepIntervalMs
                        Log.w(TAG, "Rate-limited; prefetch depth halved to " +
                            "$halved/$configuredDepth (probe interval ${depthStepIntervalMs}ms)")
                    }
                }
                return escalation
            }

            /**
             * nt6(0.8.5): current allowed prefetch depth. Uncapped sessions
             * pay nothing. A capped session steps +1 after each probe
             * interval of quiet (no 429/503 observed) and clears the cap —
             * resetting the probe interval and decaying the wait escalation —
             * once it climbs past the configured depth again.
             */
            fun currentAllowedDepth(configuredDepth: Int): Int {
                val cap = rateLimitDepthCap.get()
                if (cap >= configuredDepth) return configuredDepth
                val now = SystemClock.uptimeMillis()
                if (now - lastRateLimitAtMs >= depthStepIntervalMs &&
                    now - lastDepthStepAtMs >= depthStepIntervalMs) {
                    val stepped = cap + 1
                    if (rateLimitDepthCap.compareAndSet(cap, stepped)) {
                        lastDepthStepAtMs = now
                        rateLimitEscalation.updateAndGet { (it - 1).coerceAtLeast(0) }
                        if (stepped >= configuredDepth) {
                            rateLimitDepthCap.set(Int.MAX_VALUE)
                            depthStepIntervalMs = RATE_LIMIT_DEPTH_STEP_BASE_MS
                            hudClampLatched = false
                            hudDepthCap = 0
                            hudNextStepAtMs = 0L
                            Log.i(TAG, "Rate-limit depth cap cleared; parallel prefetch fully restored")
                        } else {
                            hudDepthCap = stepped
                            hudNextStepAtMs = now + depthStepIntervalMs
                            Log.i(TAG, "Rate-limit quiet; prefetch depth stepped to $stepped/$configuredDepth")
                        }
                    }
                }
                return rateLimitDepthCap.get().coerceAtMost(configuredDepth)
            }
        }

        private val sessionLock = Any()
        private var currentChunkSession: ChunkSession? = null
        // nt13: a session created speculatively at stream-resolve time, holding a
        // chunk-0 download that is already in flight before the player exists.
        // Kept in its own slot on purpose: on a transition the OUTGOING stream is
        // still playing and still owns currentChunkSession, so a pre-start must
        // never tear that down. The old session dies at adoption instead -- the
        // moment the player commits to the new source.
        private var pendingChunkSession: ChunkSession? = null

        /** Release one session buffer: recycle to the pool, or free directly on teardown. */
        private fun releaseSessionBuffer(buffer: PooledBuffer, chunkSz: Long, poolCap: Int) {
            if (poolCap > 0) {
                val pool = globalBufferPool.computeIfAbsent(chunkSz) { ConcurrentLinkedDeque() }
                if (pool.size < poolCap) {
                    pool.offerLast(buffer)
                    return
                }
            }
            if (buffer.allocation != null) {
                androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buffer.allocation)
            } else if (buffer.byteBuffer.isDirect) {
                freeDirectBuffer(buffer.byteBuffer)
            }
        }

        /**
         * Evict one future from a session. Handles the complete-vs-cancel race:
         * if cancel() loses because the download just completed, the buffer is
         * released via the completed value; if cancel() wins, the download
         * loop's cancellation checks release the buffer on its own thread.
         */
        private fun evictFuture(
            session: ChunkSession,
            chunkIndex: Long,
            poolCap: Int
        ) {
            val future = session.futures.remove(chunkIndex) ?: return
            session.lastTouch.remove(chunkIndex)
            if (!future.cancel(true) && future.isDone && !future.isCancelled) {
                try {
                    releaseSessionBuffer(future.get().buffer, session.chunkSize, poolCap)
                } catch (_: Exception) {
                }
            }
        }

        private fun teardownSessionLocked(session: ChunkSession, poolCap: Int) {
            session.abandoned.set(true)
            session.activeSources.forEach { ds ->
                try { ds.close() } catch (_: Exception) {}
            }
            session.activeSources.clear()
            val indices = session.futures.keys.toList()
            for (index in indices) {
                evictFuture(session, index, poolCap)
            }
            session.futures.clear()
            session.lastTouch.clear()
            session.inFlight.clear()
        }

        /**
         * Get the shared session for this request URI, creating (and tearing
         * down any stale/mismatched predecessor) as needed.
         */
        private fun obtainSession(
            requestUri: Uri,
            requestHeaders: Map<String, String>,
            chunkSz: Long,
            chunkCap: Int,
            poolCap: Int,
            prefetchWindow: Int
        ): ChunkSession {
            synchronized(sessionLock) {
                val existing = currentChunkSession
                if (existing != null) {
                    val fresh = SystemClock.uptimeMillis() - existing.lastUsedAtMs <= RETAINED_SESSION_TTL_MS
                    if (fresh && !existing.abandoned.get() &&
                        existing.requestUri == requestUri && existing.chunkSize == chunkSz &&
                        existing.requestHeaders == requestHeaders
                    ) {
                        existing.lastUsedAtMs = SystemClock.uptimeMillis()
                        return existing
                    }
                    teardownSessionLocked(existing, poolCap)
                    currentChunkSession = null
                }
                // nt13: adopt a pre-started session when the player opens the very
                // URI it was created for. Chunk 0 is already downloading (or done),
                // so this open skips the wait that would otherwise start here.
                // Geometry is part of the key: a shape mismatch means the pre-start
                // derived differently from createMediaSource, and adopting would be
                // worse than starting clean.
                val pending = pendingChunkSession
                if (pending != null) {
                    val pendingFresh = SystemClock.uptimeMillis() - pending.lastUsedAtMs <= RETAINED_SESSION_TTL_MS
                    // nt15: the DataSpec header map is deliberately NOT part of this
                    // test. media3 adds Icy-MetaData when ProgressiveMediaSource
                    // builds the spec, so the pre-start -- which has no DataSpec --
                    // can never predict it; that one header rejected every adoption
                    // in the 27 Jul captures. Excluding it is safe because the field
                    // is identity only: chunk downloads build their own DataSpec from
                    // the URI and a byte range, and the real request headers come from
                    // the OkHttp factory both sides share. URI and chunk geometry
                    // still gate adoption, and both were already matching.
                    val pendingMatches = pendingFresh && !pending.abandoned.get() &&
                        pending.requestUri == requestUri && pending.chunkSize == chunkSz
                    pendingChunkSession = null
                    if (pendingMatches) {
                        Log.i(
                            TAG,
                            "PRESTART: adopted pre-started session, chunk(s) held=${pending.futures.size} " +
                                "headerRekey=${pending.requestHeaders.keys.sorted()}->${requestHeaders.keys.sorted()}"
                        )
                        // Take the open-time headers so the ordinary session-identity
                        // check keeps matching on every later open (seeks included).
                        pending.requestHeaders = requestHeaders
                        pending.lastUsedAtMs = SystemClock.uptimeMillis()
                        currentChunkSession = pending
                        return pending
                    }
                    Log.i(
                        TAG,
                        "PRESTART: pre-started session discarded (no match at open) " +
                            "uriMatch=${pending.requestUri == requestUri} " +
                            "chunkMatch=${pending.chunkSize == chunkSz} " +
                            "headerMatch=${pending.requestHeaders == requestHeaders} " +
                            "fresh=$pendingFresh abandoned=${pending.abandoned.get()} " +
                            "pendingChunk=${pending.chunkSize} openChunk=$chunkSz " +
                            "pendingHeaderKeys=${pending.requestHeaders.keys.sorted()} " +
                            "openHeaderKeys=${requestHeaders.keys.sorted()} " +
                            "pendingHost=${pending.requestUri.host} openHost=${requestUri.host} " +
                            "pendingScheme=${pending.requestUri.scheme} openScheme=${requestUri.scheme} " +
                            "pendingPathLen=${pending.requestUri.path?.length ?: -1} " +
                            "openPathLen=${requestUri.path?.length ?: -1} " +
                            "pendingQueryLen=${pending.requestUri.query?.length ?: -1} " +
                            "openQueryLen=${requestUri.query?.length ?: -1} " +
                            "pendingUriLen=${pending.requestUri.toString().length} " +
                            "openUriLen=${requestUri.toString().length}"
                    )
                    teardownSessionLocked(pending, poolCap)
                }
                // nt6: fresh session, fresh clamp story for the HUD.
                hudClampLatched = false
                hudClampTrips = 0
                hudClampLastHitAtMs = 0L
                hudDepthCap = 0
                hudDepthConfigured = 0
                hudNextStepAtMs = 0L
                hudHedgeRestarts = 0
                hudHedgeExhausted = false
                val created = ChunkSession(requestUri, requestHeaders, chunkSz, chunkCap, prefetchWindow)
                currentChunkSession = created
                return created
            }
        }

        /**
         * Explicit teardown, wired into PlayerMediaSourceFactory.shutdown() so
         * chunk buffers and downloads never outlive the player. Buffers are
         * freed directly (poolCap = 0) — playback is over.
         */
        internal fun releaseRetainedSession() {
            synchronized(sessionLock) {
                currentChunkSession?.let { teardownSessionLocked(it, poolCap = 0) }
                currentChunkSession = null
                // nt13: a pre-start that was never adopted must not outlive the player.
                pendingChunkSession?.let { teardownSessionLocked(it, poolCap = 0) }
                pendingChunkSession = null
            }
        }

        /**
         * nt13: create the pending session for [requestUri] if one is not already
         * usable. Returns null when a pre-start would be pointless (the live session
         * already serves this URI) or unsafe (a pending session for this URI exists).
         */
        private fun obtainPendingSession(
            requestUri: Uri,
            requestHeaders: Map<String, String>,
            chunkSz: Long,
            chunkCap: Int,
            poolCap: Int,
            prefetchWindow: Int
        ): ChunkSession? {
            synchronized(sessionLock) {
                val live = currentChunkSession
                if (live != null && !live.abandoned.get() && live.requestUri == requestUri &&
                    live.chunkSize == chunkSz && live.requestHeaders == requestHeaders
                ) {
                    return null
                }
                val existingPending = pendingChunkSession
                if (existingPending != null) {
                    if (!existingPending.abandoned.get() && existingPending.requestUri == requestUri &&
                        existingPending.chunkSize == chunkSz && existingPending.requestHeaders == requestHeaders
                    ) {
                        return null
                    }
                    teardownSessionLocked(existingPending, poolCap)
                }
                val created = ChunkSession(requestUri, requestHeaders, chunkSz, chunkCap, prefetchWindow)
                pendingChunkSession = created
                return created
            }
        }

        /**
         * Sweep crash-hardening leg 1 (19 Jul 2026 incident): free every IDLE
         * recycled buffer pooled for [chunkSize]. Called when a chunk-buffer
         * allocation OOMs (relieve native pressure so the process survives) and
         * when a sweep cell fails (so a dead cell's recycled buffers never
         * carry into the next cell). Idle buffers only — in-flight buffers are
         * owned by their session's futures and are torn down by the session.
         */
        internal fun drainIdleBuffers(chunkSize: Long) {
            val pool = globalBufferPool[chunkSize] ?: return
            while (true) {
                val buf = pool.pollLast() ?: break
                if (buf.allocation != null) {
                    androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buf.allocation)
                } else if (buf.byteBuffer.isDirect) {
                    freeDirectBuffer(buf.byteBuffer)
                }
            }
        }

        /**
         * Enforce the session's chunk cap with touch-LRU eviction. Never
         * evicts [protectIndex] (the chunk being read) or anything touched in
         * the last EVICTION_TOUCH_GUARD_MS.
         */
        private fun enforceSessionCap(session: ChunkSession, protectIndex: Long, poolCap: Int) {
            if (session.futures.size <= session.chunkCap) return
            synchronized(session) {
                while (session.futures.size > session.chunkCap) {
                    val now = SystemClock.uptimeMillis()
                    // P-F2: the 2 s touch guard makes the cap soft — when every
                    // candidate is recently touched the loop bails and an active
                    // file holds ~cap+2–3 chunks (~50% overshoot on low-RAM
                    // tiers). Beyond cap+2 the ceiling is hard: evict the
                    // oldest-touched candidate regardless of the guard.
                    val hardOver = session.futures.size > session.chunkCap + 2
                    // nt54: position-aware victim selection. Touch-LRU alone
                    // systematically evicted PREFETCHED chunks: ahead-of-reader
                    // entries are touched only at creation, so once the reader
                    // is a few chunks past that moment they are always the
                    // oldest-touched entries - evicted seconds before the
                    // reader arrives, then re-downloaded in full (measured
                    // 2026-07-16: 118 of 133 chunks fetched twice, ~47% of
                    // session bandwidth). Prefer chunks BEHIND the read cursor
                    // (touch-LRU among them); only when none are eligible fall
                    // back to the FARTHEST-ahead chunk, which is needed latest.
                    // Soft-cap bail, the 2 s guard and the cap+2 hard ceiling
                    // are unchanged.
                    val readerIdx = session.lastReadChunkIndex
                    val eligible = session.futures.keys
                        .filter { it != protectIndex }
                        .filter { hardOver || now - (session.lastTouch[it] ?: 0L) >= EVICTION_TOUCH_GUARD_MS }
                    // nt2 (re-fetch fix, evidenced by evict-diag2 2026-07-17): the
                    // ENTIRE in-flight prefetch window reader+1..reader+prefetchWindow
                    // is about to be read within seconds, so it is excluded from
                    // eviction. The prior code protected only reader+1..reader+2 and,
                    // worse, its last-ditch fallback (eligible.maxOrNull, which ignores
                    // the exclusion) could evict a protected chunk under the cap+2
                    // hardOver path. Measured on that build: 190 re-fetches, all
                    // reader+1..reader+4 - 94 breaches of the reader+1..reader+2 window
                    // via the fallback, 96 from reader+3/+4 sitting outside it. Widening
                    // the window to the full prefetch depth and dropping the breaching
                    // fallback closes both: when only in-window chunks remain, bail (as
                    // the soft-cap does) and let the pool sit transiently at cap+2 rather
                    // than evict-then-refetch. Behind and beyond-window chunks stay
                    // evictable (hardOver drops the touch guard for them), so the pool
                    // stays bounded; beyond-window chunks (stale prefetch after a
                    // backward seek) are the farthest-ahead evictable and go first.
                    val nearAheadFloor = if (readerIdx >= 0L) readerIdx else Long.MIN_VALUE
                    val nearAheadCeil = if (readerIdx >= 0L) readerIdx + session.prefetchWindow else Long.MIN_VALUE
                    val evictable = eligible.filter { it < nearAheadFloor || it > nearAheadCeil }
                    val victim = evictable
                        .filter { readerIdx >= 0L && it < readerIdx }
                        .minByOrNull { session.lastTouch[it] ?: 0L }
                        ?: evictable.maxOrNull()
                        ?: return
                    evictFuture(session, victim, poolCap)
                }
            }
        }
        // ── end nt7 session ─────────────────────────────────────────────────

        private fun clearGlobalPool() {
            globalBufferPool.values.forEach { pool ->
                while (true) {
                    val buf = pool.pollFirst() ?: break
                    if (buf.allocation != null) {
                        androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buf.allocation)
                    } else if (buf.byteBuffer.isDirect) {
                        freeDirectBuffer(buf.byteBuffer)
                    }
                }
            }
            globalBufferPool.clear()
            Log.d(TAG, "Cleared global buffer pool as all ParallelRangeDataSource instances are closed")
        }
    }

    init {
        activeInstances.incrementAndGet()
    }

    /**
     * A downloaded chunk: a pooled byte array plus the actual number of bytes written.
     * The array may be larger than [size] (it's from the pool).
     */
    private class PooledBuffer(
        val allocation: androidx.media3.exoplayer.upstream.Allocation?,
        val byteBuffer: ByteBuffer
    )

    private class DownloadedChunk(val buffer: PooledBuffer, val size: Int)

    /**
     * nt7 (progressive reads): live view of a chunk download in flight.
     * [watermark] is volatile and written AFTER the bytes below it have
     * landed, so a reader that loads it may safely read [0, watermark)
     * through a duplicate() view. [lock] guards buffer release only:
     * the failure path nulls and frees [buffer] under it, and readers
     * copy out under it, so a freed (native) buffer is never touched.
     * Success never releases here -- the buffer graduates into the
     * completed DownloadedChunk and follows the session lifecycle.
     * Bytes below the watermark are identical across retry attempts
     * (same HTTP range of the same file), so a reader that consumed
     * from a failed attempt has still served correct data.
     */
    private class InFlightChunk(buffer: PooledBuffer) {
        val lock = Any()
        var buffer: PooledBuffer? = buffer
        @Volatile var watermark: Int = 0
    }

    internal data class BootstrapCacheEntry(
        val requestUri: Uri,
        val startPosition: Long,
        val resolvedUri: Uri?,
        val openLength: Long,
        val totalFileLength: Long,
        val bootstrapData: ByteArray,
        val bootstrapSize: Int,
        val createdAtUptimeMs: Long
    )

    private var resolvedUri: Uri? = null
    private var originalDataSpec: DataSpec? = null
    private var totalFileLength: Long = C.LENGTH_UNSET.toLong()
    private var position: Long = 0
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private val closed = AtomicBoolean(false)

    // nt-tier3: the in-flight window, floored so it is never below the old ceiling and
    // never below what is needed to actually saturate the connections. All three caps
    // below derive from this single value so they cannot drift out of step.
    private val effectivePrefetchDepth: Int =
        prefetchDepthChunks.coerceAtLeast(parallelConnections + 1)

    // Buffer pool limit. Idle-buffer recycling headroom above the in-flight window.
    private val maxPoolSize = effectivePrefetchDepth + 2

    // Current chunk being served to ExoPlayer
    private var currentChunk: DownloadedChunk? = null
    private var currentChunkIndex: Long = -1
    private var currentChunkReadOffset: Int = 0
    private var bootstrapPrefetchDeferred: Boolean = false
    private var bootstrapChunk: DownloadedChunk? = null
    private var bootstrapStartPosition: Long = C.TIME_UNSET
    private var continuationSource: OkHttpDataSource? = null
    private var continuationEndPositionExclusive: Long = C.TIME_UNSET
    // S1: set on a warm reopen whose target chunk is NOT already held, so the
    // first read serves from a bounded single-connection GET rather than blocking
    // on a whole aligned chunk. Instance-local; never shared across instances.
    private var pendingContinuationOpen: Boolean = false

    private val transferListeners = mutableListOf<TransferListener>()

    // Fallback: if parallel mode fails, use a single upstream DataSource
    private var fallbackSource: OkHttpDataSource? = null

    // nt7: shared download session (null on subtitle/fallback paths).
    private var session: ChunkSession? = null
    // nt7 earned prefetch: lookahead is granted only after this open has
    // demonstrated sequential consumption, so side-cursor opens (tiny reads,
    // then reopen) never trigger the connections+1 chunk prefetch fan-out.
    private var bytesServedThisOpen: Long = 0L
    private var inFlightServeLogged: Boolean = false
    // nt7 memory-tiered chunk cap: low-RAM devices keep nt6's ceiling
    // (connections + 2); high-RAM gets two extra chunks of LRU headroom.
    private val sessionChunkCap: Int = effectivePrefetchDepth +
        if (com.nuvio.tv.ui.screens.settings.MemoryBudget.isLowRamTier) 2 else 4

    override fun open(dataSpec: DataSpec): Long {
        val isSubtitle = dataSpec.uri.getQueryParameter("nuvio_type") == "subtitle"
        if (isSubtitle) {
            closed.set(false)
            resetLocalReadState()
            
            // Clean the custom query parameter from the subtitle URL before requesting
            val cleanedUri = dataSpec.uri.buildUpon().clearQuery().let { builder ->
                dataSpec.uri.queryParameterNames.forEach { name ->
                    if (name != "nuvio_type") {
                        dataSpec.uri.getQueryParameters(name).forEach { value ->
                            builder.appendQueryParameter(name, value)
                        }
                    }
                }
                builder.build()
            }
            val cleanedDataSpec = dataSpec.withUri(cleanedUri)
            
            val probeSource = upstreamFactory.createDataSource()
            transferListeners.forEach { probeSource.addTransferListener(it) }
            fallbackSource = probeSource
            val openLength = probeSource.open(cleanedDataSpec)
            
            totalFileLength = openLength
            bytesRemaining = openLength
            position = dataSpec.position
            
            Log.d(TAG, "Subtitle request detected. Bypassing parallel mode for single-connection download: ${cleanedUri.host}")
            return openLength
        }

        val wasClosed = closed.get()
        val isReopen = !wasClosed && 
                       fallbackSource == null &&
                       originalDataSpec != null && 
                       originalDataSpec?.uri == dataSpec.uri && 
                       position == dataSpec.position &&
                       totalFileLength != C.LENGTH_UNSET.toLong()

        closed.set(false)

        if (isReopen) {
            position = dataSpec.position
            bytesRemaining = (totalFileLength - position).coerceAtLeast(0L)
            bootstrapPrefetchDeferred = true
            Log.d(TAG, "Reusing active ParallelRangeDataSource for reopen at $position, file=${totalFileLength / 1024 / 1024}MB")
            return bytesRemaining
        }

        originalDataSpec = dataSpec
        position = dataSpec.position
        bootstrapPrefetchDeferred = false
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET
        continuationSource?.close()
        continuationSource = null
        continuationEndPositionExclusive = C.TIME_UNSET
        pendingContinuationOpen = false
        // A fresh open must not inherit fallback/length state from a previous
        // open on this instance; every path below re-establishes both fields.
        fallbackSource?.close()
        fallbackSource = null
        totalFileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()

        resetLocalReadState()
        bytesServedThisOpen = 0L

        // nt7: attach to the shared download session for this URI. Downloads
        // (done AND in-flight) belong to the session and survive the
        // close→open cycle ExoPlayer performs on every seek. When the session
        // is warm (length + resolved URI known) the probe request is skipped.
        // If an adopted CDN URL has expired, chunk downloads fail, ExoPlayer
        // re-opens, the failed futures are gone, and downloads retry against
        // the session's URI — with the full probe as the eventual fallback via
        // session teardown on TTL.
        val attachedSession = obtainSession(dataSpec.uri, dataSpec.httpRequestHeaders, chunkSize, sessionChunkCap, maxPoolSize, effectivePrefetchDepth)
        session = attachedSession
        val warmLength = attachedSession.totalLength
        if (warmLength > 0L && dataSpec.position in 0 until warmLength) {
            resolvedUri = attachedSession.resolvedUri
            onResolvedUri(resolvedUri)
            totalFileLength = warmLength
            val remaining = (totalFileLength - position).coerceAtLeast(0L)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                minOf(dataSpec.length, remaining)
            } else {
                remaining
            }
            bootstrapPrefetchDeferred = true
            // nt8: the tail seek may land inside the prefetched Cues
            // window. Serving it through the existing bootstrap-window
            // machinery (bootstrapChunk at an arbitrary start position)
            // replaces the bounded continuation GET -- the 700-1,050 ms
            // tail leg measured across the 26-27 Jul captures -- with a
            // heap read. A miss keeps today's path unchanged.
            val cachedTail = PrefetchWindowStore.peekTail(dataSpec.uri, position)
            if (cachedTail != null) {
                bootstrapChunk = DownloadedChunk(
                    PooledBuffer(null, ByteBuffer.wrap(cachedTail.bootstrapData)),
                    cachedTail.bootstrapSize
                )
                bootstrapStartPosition = cachedTail.startPosition
                pendingContinuationOpen = false
            } else {
                // S1: only when the target chunk is not already held. A reopen INTO a
                // held chunk (the fill reopen after the tail seek) keeps today's
                // instant path, which is what preserves its ~600 ms buffered head
                // start at first frame.
                pendingContinuationOpen = allowContinuationReopen &&
                    attachedSession.futures[position / chunkSize] == null
            }
            Log.d(
                TAG,
                "Attached to warm session for reopen at $position, " +
                    "file=${totalFileLength / 1024 / 1024}MB, held=${attachedSession.futures.size} chunk(s) (probe skipped)"
            )
            return bytesRemaining
        }

        // nt8: the prefetch-time prewarm may have captured this exact
        // window (same URI, position 0, 256 KiB) plus the total length --
        // in which case the probe round trip is skipped entirely.
        (consumeBootstrapCache(dataSpec) ?: PrefetchWindowStore.consumeHead(dataSpec))?.let { cached ->
            resolvedUri = cached.resolvedUri
            onResolvedUri(resolvedUri)
            totalFileLength = cached.totalFileLength
            bytesRemaining = cached.openLength
            bootstrapChunk = DownloadedChunk(PooledBuffer(null, ByteBuffer.wrap(cached.bootstrapData)), cached.bootstrapSize)
            bootstrapStartPosition = cached.startPosition
            bootstrapPrefetchDeferred = true
            // nt7: publish to the session so the next reopen is warm.
            attachedSession.resolvedUri = resolvedUri
            attachedSession.totalLength = totalFileLength
            Log.d(
                TAG,
                "Reusing bootstrap window for immediate reopen at ${cached.startPosition}, " +
                    "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}"
            )
            return cached.openLength
        }

        // Open first connection to determine total length and capture the resolved (redirected) URL
        val probeSource: OkHttpDataSource = upstreamFactory.createDataSource()
        transferListeners.forEach { probeSource.addTransferListener(it) }

        // S1f: attribute the cost of this open. The 23 Jul A/B leaves ~890 ms of
        // parallel-over-plain at pos=0 unexplained; S1b blamed the bootstrap read
        // and was falsified (1 MB -> 256 KB bought ~40 ms). Logging only.
        val diagOpenStartMs = SystemClock.uptimeMillis()
        var diagProbeOpenMs = -1L
        var diagBootstrapMs = -1L

        // S1m (bounded probe -- closes the 26 Jul capture's "unranged GET"):
        // ExoPlayer's initial spec is position=0/length=UNSET, which
        // OkHttpDataSource sends with NO Range header -- a full-file 200.
        // Closing that with ~1 GB unread DISCARDS the socket, so the tail
        // continuation and chunk 0 each paid a fresh cold connect (median
        // 849 ms of non-transport residual per open, 26 Jul capture).
        // Requesting exactly the bootstrap window instead makes the body
        // fully consumable, so close() returns the connection to the shared
        // pool; the total length comes from the 206's Content-Range. A server
        // that ignores Range (200: no Content-Range) gets one fresh unbounded
        // reopen and then the pre-existing sniff + single-connection
        // fallback, unchanged.
        var openLength: Long
        val boundedProbeLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, BOOTSTRAP_READ_BYTES)
        } else {
            BOOTSTRAP_READ_BYTES
        }
        try {
            probeSource.open(dataSpec.buildUpon().setLength(boundedProbeLength).build())
            diagProbeOpenMs = SystemClock.uptimeMillis() - diagOpenStartMs
            resolvedUri = probeSource.uri // Final URL after redirects (CDN URL)
            onResolvedUri(resolvedUri)
            val probeTotal = parseContentRangeTotal(probeSource.responseHeaders)
            if (probeTotal != C.LENGTH_UNSET.toLong()) {
                val remaining = (probeTotal - dataSpec.position).coerceAtLeast(0L)
                openLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    minOf(dataSpec.length, remaining)
                } else {
                    remaining
                }
            } else {
                // Range not honoured: revert to the pre-S1m single unbounded
                // open so the sniff below sees exactly what it always saw.
                Log.w(TAG, "Bounded probe got no Content-Range; reopening unbounded")
                try { probeSource.close() } catch (_: Exception) {}
                openLength = probeSource.open(dataSpec)
                diagProbeOpenMs = SystemClock.uptimeMillis() - diagOpenStartMs
            }
        } catch (e: Exception) {
            probeSource.close()
            throw e
        }

        // Check if we can do parallel range requests
        val responseHeaders = probeSource.responseHeaders
        val acceptRangesHeader = responseHeaders.entries.firstOrNull { it.key.equals("Accept-Ranges", ignoreCase = true) }?.value
        val contentRangeHeader = responseHeaders.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value
        val acceptsRanges = acceptRangesHeader?.any { it.contains("bytes") } == true ||
                contentRangeHeader?.isNotEmpty() == true

        if (openLength == C.LENGTH_UNSET.toLong() || !acceptsRanges) {
            // Can't determine length or server doesn't support ranges — reuse probe as single connection
            Log.w(TAG, "Falling back to single connection (length=${openLength}, acceptsRanges=$acceptsRanges)")
            fallbackSource = probeSource
            // Keep state consistent with the subtitle fallback path (position is
            // already set above): known length gives a real total, unknown stays unset.
            totalFileLength = if (openLength != C.LENGTH_UNSET.toLong()) {
                position + openLength
            } else {
                C.LENGTH_UNSET.toLong()
            }
            bytesRemaining = openLength
            return openLength
        }

        totalFileLength = position + openLength
        bytesRemaining = openLength

        // nt7: publish to the session so every subsequent reopen is warm.
        attachedSession.resolvedUri = resolvedUri
        attachedSession.totalLength = totalFileLength

        Log.d(TAG, "Parallel mode: ${parallelConnections} connections, ${chunkSize / 1024 / 1024}MB chunks, " +
                "file=${totalFileLength / 1024 / 1024}MB, resolved=${resolvedUri?.host}")

        // Reuse a small probe window immediately for both startup and large seek reopens.
        val firstChunkIndex = position / chunkSize
        if (openLength > 0L) {
            val bootstrapBytes = minOf(minOf(chunkSize, BOOTSTRAP_READ_BYTES), openLength).toInt()
            val diagReadStartMs = SystemClock.uptimeMillis()
            val chunk = readBootstrapChunk(probeSource, bootstrapBytes)
            diagBootstrapMs = SystemClock.uptimeMillis() - diagReadStartMs
            bootstrapChunk = chunk
            bootstrapStartPosition = position
            // Avoid startup churn from immediate background fetches during repeated startup opens,
            // but do not redownload the active seek chunk from its start.
            bootstrapPrefetchDeferred = true
            if (position == 0L) {
                updateBootstrapCache(
                    BootstrapCacheEntry(
                        requestUri = dataSpec.uri,
                        startPosition = dataSpec.position,
                        resolvedUri = resolvedUri,
                        openLength = openLength,
                        totalFileLength = totalFileLength,
                        bootstrapData = chunk.buffer.byteBuffer.array(),
                        bootstrapSize = chunk.size,
                        createdAtUptimeMs = SystemClock.uptimeMillis()
                    )
                )
            }
            val diagCloseStartMs = SystemClock.uptimeMillis()
            probeSource.close()
            Log.i(
                TAG,
                "OPEN_SPLIT pos=$position probeOpen=${diagProbeOpenMs}ms " +
                    "bootstrapRead=${diagBootstrapMs}ms bootstrapBytes=${chunk.size} " +
                    "close=${SystemClock.uptimeMillis() - diagCloseStartMs}ms " +
                    "total=${SystemClock.uptimeMillis() - diagOpenStartMs}ms"
            )
        } else {
            val diagCloseStartMs = SystemClock.uptimeMillis()
            probeSource.close()
            Log.i(
                TAG,
                "OPEN_SPLIT pos=$position probeOpen=${diagProbeOpenMs}ms bootstrapRead=n/a " +
                    "close=${SystemClock.uptimeMillis() - diagCloseStartMs}ms " +
                    "total=${SystemClock.uptimeMillis() - diagOpenStartMs}ms"
            )
        }

        return openLength
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        // Fallback mode: delegate to single upstream
        fallbackSource?.let { source ->
            val read = source.read(buffer, offset, length)
            if (read > 0) {
                position += read
                bytesRemaining = (bytesRemaining - read).coerceAtLeast(0L)
            }
            return read
        }

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val chunkIndex = position / chunkSize
        val bootstrap = bootstrapChunk
        if (currentChunk == null &&
            bootstrap != null &&
            position >= bootstrapStartPosition &&
            position < bootstrapStartPosition + bootstrap.size
        ) {
            currentChunk = bootstrap
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position - bootstrapStartPosition).toInt()
        }

        // S1: materialise BEFORE the deferred schedule below. Placed after it,
        // scheduleChunks() would still see a null continuation and schedule the
        // whole aligned chunk -- paying the amplified fetch while merely hiding
        // it from the reader.
        if (pendingContinuationOpen && currentChunk == null && continuationSource == null) {
            materialisePendingContinuation()
        }

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleChunks()
        }

        continuationSource?.let { source ->
            if (position < continuationEndPositionExclusive &&
                bytesRemaining > 0L &&
                (bootstrap == null || position >= bootstrapStartPosition + bootstrap.size)
            ) {
                val read = source.read(buffer, offset, toRead)
                if (read > 0) {
                    position += read
                    bytesRemaining -= read
                    if (position >= continuationEndPositionExclusive) {
                        source.close()
                        continuationSource = null
                        continuationEndPositionExclusive = C.TIME_UNSET
                        scheduleChunks()
                    }
                    return read
                }
                if (read == C.RESULT_END_OF_INPUT || position >= continuationEndPositionExclusive) {
                    source.close()
                    continuationSource = null
                    continuationEndPositionExclusive = C.TIME_UNSET
                    scheduleChunks()
                }
            } else if (position >= continuationEndPositionExclusive || bytesRemaining <= 0L) {
                source.close()
                continuationSource = null
                continuationEndPositionExclusive = C.TIME_UNSET
            }
        }

        // Load the chunk for the current position
        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            val activeSession = session ?: return C.RESULT_END_OF_INPUT
            ensureChunkScheduled(chunkIndex)
            val future = activeSession.futures[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            activeSession.noteRead(chunkIndex)
            // nt7 (progressive reads): the 27 Jul capture shows read()
            // blocked 525/648 ms on chunk 0's LAST byte while the ~2.5 MB
            // it needed (bufferedMs=992 at 20.5 Mbps) had been on the
            // device for hundreds of ms (~4.8 MB landed by arrival).
            // Serve below the in-flight watermark instead; when nothing
            // is available yet, fall through to the blocking path
            // unchanged (RS_CHUNK_WAIT still prices it).
            if (!future.isDone) {
                val served = awaitServeFromInFlight(activeSession, chunkIndex, future, buffer, offset, toRead)
                if (served > 0) return served
            }
            try {
                // RS_CHUNK_WAIT: read() blocks on the WHOLE chunk future, so
                // ExoPlayer sees nothing of an 8 MB chunk until all 8 MB have
                // landed -- even though the first 500 KB arrived in ~60 ms.
                // Whether that gates the FIRST FRAME has never been measured,
                // and three different fixes depend on the answer. If ExoPlayer
                // renders before reading past BOOTSTRAP_READ_BYTES, chunk 0 is
                // irrelevant to TTFF. If it reads 1-3 MB, raising the bootstrap
                // constant is a one-line win. If it reads past a whole chunk,
                // only progressive in-flight reads help.
                //
                // Correlate these against first_frame_rendered in the capture:
                // the count before it, and the highest pos, is the answer.
                // preDone separates a real stall from an already-complete chunk.
                // site= distinguishes the two read() overloads; ExoPlayer's
                // progressive path uses the ByteArray one.
                val blockT0 = SystemClock.elapsedRealtime()
                val preDone = future.isDone
                currentChunk = future.get(60, TimeUnit.SECONDS)
                Log.i(
                    TAG,
                    "RS_CHUNK_WAIT site=bytearray pos=$position chunk=$chunkIndex " +
                        "waitMs=${SystemClock.elapsedRealtime() - blockT0} preDone=$preDone"
                )
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                // nt7: a failed download is not retryable by waiting — drop
                // the future so the next attempt schedules a fresh one.
                // P-F1: cancel before dropping — an orphaned in-flight
                // download otherwise completes into a pooled native buffer
                // nothing will ever release. Ownership-gated on the two-arg
                // remove so a future already evicted/replaced by another
                // thread is never double-released (evictFuture's pattern).
                if (activeSession.futures.remove(chunkIndex, future)) {
                    activeSession.lastTouch.remove(chunkIndex)
                    if (!future.cancel(true) && future.isDone && !future.isCancelled) {
                        try {
                            releaseSessionBuffer(future.get().buffer, activeSession.chunkSize, maxPoolSize)
                        } catch (_: Exception) {
                        }
                    }
                }
                throw IOException("Failed to download chunk $chunkIndex", e)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()

            // nt7: LRU cap enforcement lives in ensureChunkScheduled; behind-
            // chunks are no longer eagerly released (they serve the backward
            // cursors on scatter-read files).
            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            // Current chunk exhausted, move to next
            if (chunk === bootstrapChunk) {
                bootstrapChunk = null
                bootstrapStartPosition = C.TIME_UNSET
            }
            currentChunk = null
            return read(buffer, offset, length)
        }

        val readSize = minOf(toRead, available)
        // P-F0b (PR 2544 review): session chunks are shared across instances —
        // mutating the shared buffer's position races concurrent readers of
        // the same chunk. Read through a duplicate, as the ByteBuffer path does.
        val readBuf = chunk.buffer.byteBuffer.duplicate()
        readBuf.position(currentChunkReadOffset)
        readBuf.get(buffer, offset, readSize)
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize
        bytesServedThisOpen += readSize
        session?.noteRead(chunkIndex)

        return readSize
    }

    /**
     * S1 (SS9.1): serve a warm reopen into an un-fetched region from a plain
     * bounded GET instead of a whole aligned chunk. Measured 23 Jul 2026 on the
     * Matroska tail seek: the reader's first needed byte sat 7,305,388 bytes into
     * an 8,388,608-byte chunk, so the chunk path fetched 9,216,903 bytes to
     * deliver 1,911,515. Bounded to the chunk boundary so the existing handover
     * in scheduleChunks() takes the file from there; on any failure the flag is
     * already cleared and the proven chunk path serves the read instead.
     */
    private fun materialisePendingContinuation() {
        pendingContinuationOpen = false
        if (bytesRemaining <= 0L) return
        val activeSession = session ?: return
        val boundary = ((position / chunkSize) + 1L) * chunkSize
        val end = minOf(boundary, position + bytesRemaining)
        val length = end - position
        if (length <= 0L) return
        val source = upstreamFactory.createDataSource()
        transferListeners.forEach { source.addTransferListener(it) }
        try {
            source.open(
                DataSpec.Builder()
                    .setUri(activeSession.resolvedUri ?: activeSession.requestUri)
                    .setPosition(position)
                    .setLength(length)
                    .build()
            )
        } catch (e: Exception) {
            try { source.close() } catch (_: Exception) {}
            Log.w(TAG, "Continuation open failed at $position; using chunk path: ${e.message}")
            return
        }
        continuationSource = source
        continuationEndPositionExclusive = end
        // Keep the eviction cursor with the reader while the continuation runs.
        activeSession.noteRead(position / chunkSize)
        Log.d(TAG, "Continuation open at $position, $length bytes to boundary $end")
    }

    /** Free an in-flight attempt's buffer under its lock, so a reader
     *  mid-copy can never touch freed (native) memory. */
    private fun releaseInFlightBuffer(
        activeSession: ChunkSession,
        chunkIndex: Long,
        inFlight: InFlightChunk,
        buffer: PooledBuffer
    ) {
        synchronized(inFlight.lock) {
            inFlight.buffer = null
            activeSession.inFlight.remove(chunkIndex, inFlight)
            releaseBuffer(buffer)
        }
    }

    // nt5: reader-blocked chunk escalation (spec 2026-08-26). A stagnant
    // in-flight watermark past the threshold means the chunk's request is
    // hung (dead pooled connection / pre-body stall) -- the one shape the 3a
    // body-rate watchdog structurally cannot see, because it samples inside
    // the body-read loop. Race ONE duplicate on a fresh connection into the
    // SAME future. The ownership-gated complete()/releaseBuffer() race
    // (ensureChunkScheduled's pattern) keeps the loser's buffer safe; the
    // duplicate NEVER completes the future exceptionally, so failure
    // semantics stay with the original attempt. Skipped while the
    // rate-limit clamp is latched; at most once per chunk per session.
    // OutOfMemoryError is contained exactly as in ensureChunkScheduled
    // (19 Jul 2026 incident class): drain idle buffers, log, never escape
    // the executor thread.
    private fun escalateReaderBlockedChunk(
        activeSession: ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        waitedMs: Long,
        watermark: Int
    ) {
        if (future.isDone || future.isCancelled || activeSession.abandoned.get()) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=done")
            return
        }
        if (hudClampLatched) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=clamp")
            return
        }
        val typed = activeSession.futures[chunkIndex]
        if (typed == null || typed !== future) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=stale")
            return
        }
        if (!activeSession.escalatedChunks.add(chunkIndex)) {
            Log.i(TAG, "RS_ESCALATE skip chunk=$chunkIndex reason=already")
            return
        }
        Log.w(TAG, "RS_ESCALATE fired chunk=$chunkIndex waitMs=$waitedMs watermark=$watermark")
        val t0 = SystemClock.elapsedRealtime()
        sharedExecutor.execute {
            try {
                if (typed.isDone || typed.isCancelled || activeSession.abandoned.get()) return@execute
                val result = downloadChunkOnce(activeSession, chunkIndex, typed, allowStallRestart = false)
                if (typed.complete(result)) {
                    Log.w(TAG, "RS_ESCALATE won chunk=$chunkIndex ms=${SystemClock.elapsedRealtime() - t0}")
                } else {
                    releaseBuffer(result.buffer)
                    Log.i(TAG, "RS_ESCALATE lost chunk=$chunkIndex")
                }
            } catch (e: Exception) {
                // Never completeExceptionally: the original path owns failure.
                Log.w(TAG, "RS_ESCALATE failed chunk=$chunkIndex: ${e.message}")
            } catch (e: OutOfMemoryError) {
                drainIdleBuffers(activeSession.chunkSize)
                Log.w(TAG, "RS_ESCALATE failed chunk=$chunkIndex: chunk buffer OOM (contained)")
            }
        }
    }

    /**
     * nt9: serve player bytes from a chunk still downloading, WAITING for
     * the first bytes rather than giving up when none have landed yet.
     *
     * nt7 shipped this as a single attempt and it worked -- because the
     * probe's 289-438 ms round trip was accidentally load-bearing, giving
     * the download a head start so the reader arrived to find megabytes
     * buffered (27 Jul: watermark=3131392, zero pre-frame block). nt8 then
     * removed the probe on a prefetched press, the reader began arriving
     * ~107 ms after the download started -- before its response headers had
     * returned at 372 ms -- and every attempt found watermark=0, fell
     * through, and blocked on the WHOLE 8 MB chunk (RS_CHUNK_WAIT
     * waitMs=2746, first frame 4,023 ms).
     *
     * Waiting makes the two compose: the reader is released the moment the
     * first bytes land, whatever produced the gap. The wait ends early on
     * completion, on failure (both surface as future.isDone, so the
     * caller's existing handling runs), and on close. duplicate() keeps the
     * download thread's position mutations unshared (P-F0b pattern); the
     * copy-out runs under the attempt's release lock.
     */
    private fun awaitServeFromInFlight(
        activeSession: ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        target: ByteArray,
        targetOffset: Int,
        maxLength: Int
    ): Int {
        val offsetInChunk = (position % chunkSize).toInt()
        val waitT0 = SystemClock.elapsedRealtime()
        // nt5: reader-blocked escalation state for this wait (see spec).
        var escalatedThisWait = false
        var baselineWatermark = Int.MIN_VALUE
        while (true) {
            // Completion is the caller's business: its future.get() is then
            // instant, and a FAILED download completes here too, so its
            // existing retire-and-rethrow path runs unchanged.
            if (closed.get() || future.isDone) return 0
            // Re-read every pass: a retry attempt registers a fresh entry,
            // and the first attempt may not have registered one yet.
            val inFlight = activeSession.inFlight[chunkIndex]
            if (inFlight != null) {
                val available = inFlight.watermark - offsetInChunk
                if (available > 0) {
                    val toCopy = minOf(maxLength, available)
                    synchronized(inFlight.lock) {
                        val buf = inFlight.buffer ?: return 0
                        val view = buf.byteBuffer.duplicate()
                        view.position(offsetInChunk)
                        view.get(target, targetOffset, toCopy)
                    }
                    val waitedMs = SystemClock.elapsedRealtime() - waitT0
                    if (!inFlightServeLogged) {
                        inFlightServeLogged = true
                        Log.i(
                            TAG,
                            "RS_INFLIGHT pos=$position chunk=$chunkIndex " +
                                "watermark=${inFlight.watermark} served=$toCopy waitMs=$waitedMs"
                        )
                    }
                    position += toCopy
                    bytesRemaining -= toCopy
                    bytesServedThisOpen += toCopy
                    return toCopy
                }
            }
            // nt5: stagnant watermark past the threshold = hung request.
            // Progressing-but-slow chunks (watermark moving) stay 3a's
            // jurisdiction and are never escalated here.
            val wmNow = inFlight?.watermark ?: -1
            if (baselineWatermark == Int.MIN_VALUE) baselineWatermark = wmNow
            if (!escalatedThisWait &&
                wmNow == baselineWatermark &&
                SystemClock.elapsedRealtime() - waitT0 >=
                    minOf(ESCALATE_AFTER_MS, IN_FLIGHT_WAIT_CAP_MS.toLong())
            ) {
                escalateReaderBlockedChunk(
                    activeSession, chunkIndex, future,
                    SystemClock.elapsedRealtime() - waitT0, wmNow
                )
                escalatedThisWait = true
            }
            if (SystemClock.elapsedRealtime() - waitT0 >=
                IN_FLIGHT_WAIT_CAP_MS.toLong() +
                    (if (escalatedThisWait) ESCALATE_POLL_EXTENSION_MS else 0L)
            ) {
                Log.i(
                    TAG,
                    "RS_INFLIGHT_GIVEUP pos=$position chunk=$chunkIndex " +
                        "waitMs=${SystemClock.elapsedRealtime() - waitT0}"
                )
                return 0
            }
            try {
                Thread.sleep(IN_FLIGHT_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return 0
            }
        }
    }

    private fun scheduleChunks() {
        if (!shouldAllowBackgroundPrefetch()) return
        // S1: nothing left to serve on this open. The continuation-exhaustion path
        // can otherwise schedule a chunk past the reader's last byte.
        if (bytesRemaining == 0L) return
        val currentChunkIdx =
            if (continuationSource != null && continuationEndPositionExclusive != C.TIME_UNSET && position < continuationEndPositionExclusive) {
                (continuationEndPositionExclusive + chunkSize - 1L) / chunkSize
            } else {
                position / chunkSize
            }
        // nt7 earned prefetch: lookahead only after this open has served a
        // meaningful sequential run. Side cursors (a few bytes per open on
        // scatter-read files) fetch only the chunk they actually need, instead
        // of fanning out connections+1 chunks of dead prefetch per visit.
        val earnedAhead = if (bytesServedThisOpen >= EARNED_PREFETCH_BYTES) effectivePrefetchDepth else 1
        // nt6(0.8.5): AIMD — a rate-limited session halves its depth cap and
        // recovers +1 per quiet probe interval (ChunkSession.currentAllowedDepth),
        // so a provider that 429s converges just under its actual request
        // budget instead of latching to a single connection.
        val maxAhead = session?.currentAllowedDepth(effectivePrefetchDepth)?.coerceAtMost(earnedAhead)
            ?: earnedAhead

        for (i in 0 until maxAhead) {
            val ci = currentChunkIdx + i
            if (totalFileLength != C.LENGTH_UNSET.toLong() && ci * chunkSize >= totalFileLength) break
            ensureChunkScheduled(ci)
        }
    }

    private fun ensureChunkScheduled(chunkIndex: Long) {
        val activeSession = session ?: return
        // nt7: make room under the memory-tiered cap before growing the map.
        enforceSessionCap(activeSession, protectIndex = chunkIndex, poolCap = maxPoolSize)
        activeSession.futures.computeIfAbsent(chunkIndex) {
            val future = CompletableFuture<DownloadedChunk>()
            activeSession.touch(chunkIndex)
            Log.d(TAG, "Scheduling chunk $chunkIndex")
            sharedExecutor.execute {
                try {
                    if (!future.isCancelled && !activeSession.abandoned.get()) {
                        val result = downloadChunk(activeSession, chunkIndex, future)
                        if (!future.complete(result)) {
                            releaseBuffer(result.buffer)
                        }
                    } else if (future.isCancelled) {
                        // no-op: never started
                    } else {
                        future.completeExceptionally(IOException("Session abandoned"))
                    }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                } catch (e: OutOfMemoryError) {
                    // Sweep crash-hardening leg 1 (19 Jul 2026 incident): chunk-buffer
                    // allocation (acquireBuffer -> ByteBuffer.allocateDirect) throws
                    // OutOfMemoryError, an Error the Exception catch above never sees —
                    // it escaped this worker thread to the default uncaught-exception
                    // handler and killed the process mid-assessment-sweep. Contain it:
                    // free every idle pooled buffer of this chunk size to relieve
                    // pressure, then fail the CHUNK (wrapped as IOException so every
                    // downstream Exception handler keeps working), never the process.
                    drainIdleBuffers(activeSession.chunkSize)
                    future.completeExceptionally(
                        IOException("Native chunk buffer allocation failed (out of memory)", e)
                    )
                }
            }
            future
        }
    }

    private fun downloadChunk(activeSession: ChunkSession, chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
        var lastException: Exception? = null
        for (attempt in 0..1) {
            // nt5: isDone => a racing completer (reader-blocked escalation) already
            // won; a retry here would fetch a full chunk only to lose the
            // ownership race and release it.
            if (future.isDone || future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {
                // nt7: downloads belong to the session, not the instance —
                // only future cancellation or session teardown aborts them.
                if (activeSession.abandoned.get() || future.isCancelled) throw IOException("Session abandoned or cancelled")
                lastException = e
                // nt-tier2: 429/503 is server rate-limiting, not a stalled socket.
                // Hand off to a bounded backoff loop rather than retrying now.
                // 3a: a body stall (not a 429) is abandoned and re-fetched
                // on a fresh connection, sequentially, within budget.
                if (e is StalledChunkException) {
                    return downloadChunkWithStallRestart(activeSession, chunkIndex, future, e)
                }
                val rlError = e.findRateLimitException()
                if (rlError != null) {
                    return downloadChunkWithRateLimitBackoff(activeSession, chunkIndex, future, rlError)
                }
                if (attempt == 0) {
                    if (e.isTransientInterruption()) {
                        Log.d(TAG, "Chunk $chunkIndex interrupted during prefetch (attempt 1), retrying")
                        try {
                            Thread.sleep(50)
                        } catch (_: InterruptedException) {
                        }
                    } else {
                        Log.w(TAG, "Chunk $chunkIndex download failed (attempt 1), retrying: ${e.message}")
                    }
                }
            }
        }
        throw IOException("Failed to download chunk $chunkIndex after 2 attempts", lastException)
    }

    private fun downloadChunkOnce(activeSession: ChunkSession, chunkIndex: Long, future: CompletableFuture<*>, allowStallRestart: Boolean = true): DownloadedChunk {
        val sessionLength = activeSession.totalLength
        val start = chunkIndex * chunkSize
        val end = if (sessionLength > 0L) {
            minOf(start + chunkSize, sessionLength)
        } else {
            start + chunkSize
        }

        val ds = upstreamFactory.createDataSource()
        transferListeners.forEach { ds.addTransferListener(it) }
        activeSession.activeSources.add(ds)
        try {
            val uri = activeSession.resolvedUri ?: activeSession.requestUri
            val spec = DataSpec.Builder()
                .setUri(uri)
                .setPosition(start)
                .setLength(end - start)
                .build()

            if (future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            Log.d(TAG, "Starting chunk download: idx=$chunkIndex, range=$start-$end")
            ds.open(spec)
            // pre-nt3 short-chunk rejection: with a known session length the
            // requested range is exact — a chunk that comes back short must
            // fail (and retry) rather than be cached as if complete.
            val expectedBytes = if (sessionLength > 0L) end - start else -1L
            val chunk = readIntoChunk(activeSession, chunkIndex, ds, future, expectedBytes, allowStallRestart)
            Log.d(TAG, "Successfully downloaded chunk $chunkIndex, size=${chunk.size} bytes")
            return chunk
        } finally {
            activeSession.activeSources.remove(ds)
            try { ds.close() } catch (_: Exception) {}
        }
    }

    /**
     * 3a: a stalled body is abandoned and the chunk re-fetched on a FRESH
     * connection, immediately (no backoff -- the point is speed) and
     * SEQUENTIALLY, so the stalled connection's slot is reused and the
     * user's configured connection count is never exceeded (works at 1, 2
     * or N connections identically). Up to HEDGE_MAX_RESTARTS fresh tries;
     * if the origin stalls every one, a final watchdog-disabled attempt
     * lets the chunk complete slowly rather than failing playback. A
     * fresh connection that returns a non-stall error (e.g. a 429) is
     * surfaced so the existing rate-limit / retry paths handle it.
     */
    private fun downloadChunkWithStallRestart(
        activeSession: ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        firstStall: StalledChunkException
    ): DownloadedChunk {
        var lastStall = firstStall
        var attempt = 0
        while (attempt < HEDGE_MAX_RESTARTS) {
            if (future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            hudHedgeRestarts++
            Log.w(TAG, "HEDGE_RESTART chunk=$chunkIndex attempt=${attempt + 1}/$HEDGE_MAX_RESTARTS " +
                "prevRateBps=${lastStall.rateBps} atWatermark=${lastStall.watermark}")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {
                if (activeSession.abandoned.get() || future.isCancelled) throw IOException("Session abandoned or cancelled")
                if (e !is StalledChunkException) throw e
                lastStall = e
                attempt++
            }
        }
        // Origin stalling every connection: complete slowly rather than fail.
        hudHedgeExhausted = true
        Log.w(TAG, "HEDGE_RESTART chunk=$chunkIndex exhausted after $HEDGE_MAX_RESTARTS; " +
            "final attempt with watchdog disabled")
        return downloadChunkOnce(activeSession, chunkIndex, future, allowStallRestart = false)
    }

    /** 3a: thrown by readIntoChunk when a chunk's body rate collapses; an
     *  IOException subtype so it never matches the 429 or transient-retry
     *  predicates and flows only to the stall-restart path. */
    private class StalledChunkException(
        val chunkIndex: Long,
        val watermark: Int,
        val rateBps: Long
    ) : IOException("chunk $chunkIndex body stalled at ${rateBps}B/s (watermark=$watermark)")

    private fun Exception.isTransientInterruption(): Boolean {
        if (this is InterruptedIOException || this is InterruptedException) return true
        val cause = cause
        return cause is InterruptedIOException || cause is InterruptedException
    }

    /**
     * nt-tier2: walk the cause chain for an HTTP 429/503 response. Returns the
     * exception (so the caller can read its Retry-After header) or null for any
     * other failure — those keep the existing immediate-retry stall handling.
     */
    private fun Throwable.findRateLimitException(): HttpDataSource.InvalidResponseCodeException? {
        var cause: Throwable? = this
        var depth = 0
        while (cause != null && depth < 6) {
            val c = cause
            if (c is HttpDataSource.InvalidResponseCodeException &&
                (c.responseCode == 429 || c.responseCode == 503)) {
                return c
            }
            cause = c.cause
            depth++
        }
        return null
    }

    /**
     * nt-tier2 / nt6(0.8.5): dedicated handling for a rate-limited chunk. One
     * invocation = one congestion episode: one multiplicative depth decrease
     * and one wait-escalation step, then up to RATE_LIMIT_MAX_BACKOFF_RETRIES
     * properly-spaced retries. Bounded and cancellation-aware; when the
     * budget is spent it throws and the existing failure handling /
     * auto-recovery takes over — i.e. never worse than before.
     */
    private fun downloadChunkWithRateLimitBackoff(
        activeSession: ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        firstError: HttpDataSource.InvalidResponseCodeException
    ): DownloadedChunk {
        var rl: HttpDataSource.InvalidResponseCodeException = firstError
        var lastException: Exception = firstError
        val escalation = activeSession.beginRateLimitEpisode(effectivePrefetchDepth)
        var attempt = 0
        while (attempt < RATE_LIMIT_MAX_BACKOFF_RETRIES) {
            val waitMs = rateLimitWaitMs(attempt, escalation, rl)
            Log.w(TAG, "Chunk $chunkIndex rate-limited (HTTP ${rl.responseCode}); backing off ${waitMs}ms " +
                "(attempt ${attempt + 1}/$RATE_LIMIT_MAX_BACKOFF_RETRIES, escalation $escalation)")
            if (!sleepInterruptibly(waitMs, future, activeSession)) throw IOException("Cancelled during rate-limit backoff")
            if (future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {
                if (activeSession.abandoned.get() || future.isCancelled) throw IOException("Session abandoned or cancelled")
                lastException = e
                // A non-rate-limit error after a 429 is a different failure: surface it.
                rl = e.findRateLimitException() ?: throw e
                activeSession.noteRateLimitHit()
                attempt++
            }
        }
        throw IOException("Chunk $chunkIndex still rate-limited after $RATE_LIMIT_MAX_BACKOFF_RETRIES backoffs", lastException)
    }

    /**
     * nt6(0.8.5): wait before retrying a rate-limited chunk. A server-stated
     * Retry-After (delta-seconds or HTTP-date, via ParallelRangeRetryAfter)
     * is honoured up to a hard cap — the server knows its own limiter, but a
     * broken or hostile header must never camp a real-time pipeline. With no
     * usable header the wait is exponential per attempt from a base that
     * escalates with repeated episodes, so a hard limiter produces
     * progressively longer waits across cycles instead of the indefinite
     * fixed-period retry a self-resetting ladder degenerates into. Jitter
     * decorrelates concurrent retries. Replaces the nt-tier2 version, which
     * capped even a server-stated wait at 3s — precisely the ~5s indefinite
     * re-429 loop observed against strict CDNs.
     */
    private fun rateLimitWaitMs(
        attempt: Int,
        escalation: Int,
        rl: HttpDataSource.InvalidResponseCodeException
    ): Long {
        val jitter = (Math.random() * RATE_LIMIT_BACKOFF_JITTER_MS).toLong()
        val header = rl.headerFields.entries
            .firstOrNull { it.key?.equals("Retry-After", ignoreCase = true) == true }
            ?.value?.firstOrNull()?.trim()
        val headerMs = ParallelRangeRetryAfter.parseHeaderMs(header)
        if (headerMs != null) {
            return headerMs.coerceAtMost(RATE_LIMIT_WAIT_HARD_CAP_MS) + jitter
        }
        val cycleCapMs = (RATE_LIMIT_BACKOFF_CYCLE_CAP_MS shl escalation.coerceIn(0, RATE_LIMIT_ESCALATION_MAX))
            .coerceAtMost(RATE_LIMIT_WAIT_HARD_CAP_MS)
        val base = RATE_LIMIT_BACKOFF_BASE_MS shl (attempt + escalation).coerceIn(0, 6)
        return base.coerceIn(RATE_LIMIT_BACKOFF_BASE_MS, cycleCapMs) + jitter
    }

    /**
     * nt-tier2: sleep in short slices so a stop/seek aborts the backoff promptly.
     * Watches future cancellation and session teardown only — not the instance
     * closed flag — to stay consistent with the session-owned download model.
     * Returns false if the wait should abort.
     */
    private fun sleepInterruptibly(
        totalMs: Long,
        future: CompletableFuture<*>,
        activeSession: ChunkSession
    ): Boolean {
        var slept = 0L
        while (slept < totalMs) {
            if (future.isCancelled || activeSession.abandoned.get()) return false
            val slice = minOf(RATE_LIMIT_SLEEP_SLICE_MS, totalMs - slept)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                return false
            }
            slept += slice
        }
        return !(future.isCancelled || activeSession.abandoned.get())
    }

    /** Read from an already-opened DataSource into a pooled chunk buffer. */
    private fun readIntoChunk(
        activeSession: ChunkSession,
        chunkIndex: Long,
        ds: DataSource,
        future: CompletableFuture<*>,
        expectedBytes: Long,
        allowStallRestart: Boolean = true
    ): DownloadedChunk {
        val buffer = acquireBuffer()
        // nt7 (progressive reads): publish this attempt. Registered
        // AFTER acquireBuffer so an allocation OOM never leaves a
        // dangling entry; a retry attempt overwrites its predecessor.
        val inFlight = InFlightChunk(buffer)
        activeSession.inFlight[chunkIndex] = inFlight
        val tempArray = readBufferLocal.get()!!
        var totalRead = 0
        var consecutiveZeroReads = 0
        // 3a: confirm the build is live on the first chunk read, once.
        if (obsAnnounced.compareAndSet(false, true)) {
            Log.w(TAG, "OBS_ACTIVE build=hedge-3a minOpenMs=$HEDGE_MIN_OPEN_MS " +
                "windowMs=$HEDGE_WINDOW_MS stallRateBps=$HEDGE_STALL_RATE_BPS " +
                "maxRestarts=$HEDGE_MAX_RESTARTS")
        }
        // 3a: in-loop body-stall watchdog state (per download attempt).
        val hedgeChunkT0 = SystemClock.elapsedRealtime()
        var hedgeLastCheckT0 = hedgeChunkT0
        var hedgeLastCheckBytes = 0
        // 3a source-aware: pick the stall profile once per attempt from the
        // resolved (post-redirect) URL. AIOStreams native Usenet resolves through
        // .../api/v1/usenet/stream/...; anything else (debrid CDN, Emby, direct)
        // is treated as CDN and keeps the original single-window behaviour.
        val hedgeIsUsenet = activeSession.resolvedUri?.path?.contains("/usenet/") == true
        val hedgeStallRateBps = if (hedgeIsUsenet) HEDGE_STALL_RATE_USENET else HEDGE_STALL_RATE_CDN
        val hedgeWindowsRequired = if (hedgeIsUsenet) HEDGE_WINDOWS_USENET else HEDGE_WINDOWS_CDN
        var hedgeConsecutiveStalled = 0
        try {
            val byteBufferReader = if (useNativeMemory && ds is androidx.media3.common.ByteBufferDataReader && ds.supportsByteBufferRead()) {
                ds
            } else {
                null
            }

            // nt7: the loop no longer watches the instance's closed flag —
            // downloads run to completion across ExoPlayer's seek reopens and
            // abort only on future cancellation or session teardown.
            while (!activeSession.abandoned.get()) {
                if (future.isCancelled) {
                    throw IOException("Chunk download cancelled")
                }
                val maxRead = minOf(buffer.byteBuffer.capacity() - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break

                val read = if (byteBufferReader != null) {
                    buffer.byteBuffer.position(totalRead)
                    byteBufferReader.read(buffer.byteBuffer, maxRead)
                } else {
                    val r = ds.read(tempArray, 0, maxRead)
                    if (r != C.RESULT_END_OF_INPUT) {
                        buffer.byteBuffer.position(totalRead)
                        buffer.byteBuffer.put(tempArray, 0, r)
                    }
                    r
                }

                if (read == C.RESULT_END_OF_INPUT) break
                // pre-nt3 no-progress guard: a positive-length read returning
                // 0 violates the DataSource contract; bail after a few rather
                // than busy-spinning until cancellation.
                if (read == 0) {
                    if (++consecutiveZeroReads >= MAX_CONSECUTIVE_ZERO_READS) {
                        throw IOException(
                            "No read progress after $MAX_CONSECUTIVE_ZERO_READS attempts " +
                                "(read $totalRead of $expectedBytes bytes)"
                        )
                    }
                } else {
                    consecutiveZeroReads = 0
                }
                totalRead += read
                // Volatile store orders every buffer write above it.
                inFlight.watermark = totalRead
                // 3a: rate-based body-stall sample, emitted every window
                // whether or not it trips, so the decision is always
                // visible. Only chunks taking longer than the window emit.
                val hedgeNowMs = SystemClock.elapsedRealtime()
                if (hedgeNowMs - hedgeChunkT0 >= HEDGE_MIN_OPEN_MS &&
                    hedgeNowMs - hedgeLastCheckT0 >= HEDGE_WINDOW_MS) {
                    val hedgeWindowMs = hedgeNowMs - hedgeLastCheckT0
                    val hedgeWindowBytes = totalRead - hedgeLastCheckBytes
                    val hedgeRateBps = if (hedgeWindowMs > 0L)
                        hedgeWindowBytes.toLong() * 1000L / hedgeWindowMs else Long.MAX_VALUE
                    val hedgeStalled = hedgeRateBps < hedgeStallRateBps
                    if (hedgeStalled) hedgeConsecutiveStalled++ else hedgeConsecutiveStalled = 0
                    Log.w(TAG, "HEDGE_SAMPLE chunk=$chunkIndex watermark=$totalRead " +
                        "windowBytes=$hedgeWindowBytes windowMs=$hedgeWindowMs " +
                        "rateBps=$hedgeRateBps stalled=$hedgeStalled " +
                        "consec=$hedgeConsecutiveStalled/$hedgeWindowsRequired " +
                        "usenet=$hedgeIsUsenet clamp=$hudClampLatched " +
                        "restartable=$allowStallRestart")
                    if (hedgeConsecutiveStalled >= hedgeWindowsRequired &&
                        allowStallRestart && !hudClampLatched) {
                        // Abandon this connection; downloadChunk routes the
                        // throw to a fresh-connection restart within budget.
                        throw StalledChunkException(chunkIndex, totalRead, hedgeRateBps)
                    }
                    hedgeLastCheckT0 = hedgeNowMs
                    hedgeLastCheckBytes = totalRead
                }
            }
            // pre-nt3 short-chunk rejection: a premature EOF inside a known
            // range must not produce a cached "complete" chunk — a short
            // non-final chunk otherwise dead-ends both read paths at the
            // phantom chunk boundary.
            if (expectedBytes > 0L && totalRead < expectedBytes && !activeSession.abandoned.get()) {
                throw IOException("Short chunk: read $totalRead of $expectedBytes bytes")
            }
        } catch (e: Exception) {
            releaseInFlightBuffer(activeSession, chunkIndex, inFlight, buffer)
            if (activeSession.abandoned.get()) throw IOException("Session abandoned")
            throw e
        }
        if (activeSession.abandoned.get()) {
            releaseInFlightBuffer(activeSession, chunkIndex, inFlight, buffer)
            throw IOException("Session abandoned")
        }
        // Success: the buffer graduates into the completed chunk; only
        // the in-flight view is retired (ownership-gated).
        activeSession.inFlight.remove(chunkIndex, inFlight)
        buffer.byteBuffer.flip()
        return DownloadedChunk(buffer, totalRead)
    }

    /** Read only a small startup window from an already-opened DataSource. */
    /**
     * S1m: total file size from a 206's Content-Range ("bytes 0-262143/N").
     * Returns C.LENGTH_UNSET when the header is absent or the total is
     * opaque (an asterisk), which the caller treats as "Range not honoured".
     */
    private fun parseContentRangeTotal(headers: Map<String, List<String>>): Long {
        val value = headers.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
            ?.value?.firstOrNull()
            ?: return C.LENGTH_UNSET.toLong()
        val totalPart = value.substringAfterLast('/', missingDelimiterValue = "").trim()
        if (totalPart.isEmpty() || totalPart == "*") return C.LENGTH_UNSET.toLong()
        return totalPart.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
    }

    private fun readBootstrapChunk(ds: DataSource, maxBytes: Int): DownloadedChunk {
        val buffer = ByteArray(maxBytes)
        var totalRead = 0
        try {
            while (!closed.get() && totalRead < buffer.size) {
                val maxRead = minOf(buffer.size - totalRead, READ_BUFFER_SIZE)
                if (maxRead <= 0) break
                val read = ds.read(buffer, totalRead, maxRead)
                if (read == C.RESULT_END_OF_INPUT) break
                totalRead += read
            }
        } catch (e: Exception) {
            if (closed.get()) throw IOException("DataSource closed")
            throw e
        }
        if (closed.get()) {
            throw IOException("DataSource closed")
        }
        val wrapped = ByteBuffer.wrap(buffer, 0, totalRead)
        return DownloadedChunk(PooledBuffer(null, wrapped), totalRead)
    }

    private fun acquireBuffer(): PooledBuffer {
        val pool = globalBufferPool.computeIfAbsent(chunkSize) { ConcurrentLinkedDeque() }
        val buf = pool.pollLast()
        if (buf != null) {
            buf.byteBuffer.clear()
            return buf
        }
        return if (useNativeMemory) {
            val allocation = androidx.media3.exoplayer.upstream.DefaultAllocatorNative.createAllocation(chunkSize.toInt())
            val allocBuffer = allocation?.buffer
            if (allocation != null && allocBuffer != null) {
                PooledBuffer(allocation, allocBuffer)
            } else {
                PooledBuffer(null, ByteBuffer.allocateDirect(chunkSize.toInt()))
            }
        } else {
            PooledBuffer(null, ByteBuffer.allocate(chunkSize.toInt()))
        }
    }

    /**
     *   maxPoolSize in releaseBuffer only caps how many idle/recycled buffers are kept in the pool.
     *   If the pool is full, the released buffer is GC'd instead of recycled.
     */
    private fun releaseBuffer(buffer: PooledBuffer) {
        val pool = globalBufferPool.computeIfAbsent(chunkSize) { ConcurrentLinkedDeque() }
        if (pool.size < maxPoolSize) {
            pool.offerLast(buffer)
        } else {
            if (buffer.allocation != null) {
                androidx.media3.exoplayer.upstream.DefaultAllocatorNative.freeAllocation(buffer.allocation)
            } else if (buffer.byteBuffer.isDirect) {
                freeDirectBuffer(buffer.byteBuffer)
            }
        }
    }

    /**
     * nt7: detach instance-local read state. Session chunks (and in-flight
     * downloads) are untouched — they belong to the shared session and their
     * buffers are owned by the session's futures. Releasing anything here
     * would double-free; eviction and teardown are the session's job.
     */
    private fun resetLocalReadState() {
        currentChunk = null
        currentChunkIndex = -1
        currentChunkReadOffset = 0
        bootstrapChunk = null
        bootstrapStartPosition = C.TIME_UNSET
        inFlightServeLogged = false
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            fallbackSource?.close()
            fallbackSource = null
            continuationSource?.close()
            continuationSource = null
            continuationEndPositionExclusive = C.TIME_UNSET
            pendingContinuationOpen = false

            // nt7: downloads survive this close — detach references only.
            resetLocalReadState()
            session = null

            val active = activeInstances.decrementAndGet()
            if (active <= 0) {
                clearGlobalPool()
            }
        }
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners.add(transferListener)
    }

    override fun getUri(): Uri? = resolvedUri ?: fallbackSource?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        fallbackSource?.responseHeaders ?: emptyMap()

    override fun supportsByteBufferRead(): Boolean = true

    override fun read(buffer: ByteBuffer, length: Int): Int {
        fallbackSource?.let { source ->
            val temp = ByteArray(minOf(length, READ_BUFFER_SIZE))
            val read = source.read(temp, 0, temp.size)
            if (read > 0) {
                buffer.put(temp, 0, read)
                position += read
                bytesRemaining = (bytesRemaining - read).coerceAtLeast(0L)
            }
            return read
        }

        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = minOf(length.toLong(), bytesRemaining).toInt()

        val chunkIndex = position / chunkSize
        val bootstrap = bootstrapChunk
        if (currentChunk == null &&
            bootstrap != null &&
            position >= bootstrapStartPosition &&
            position < bootstrapStartPosition + bootstrap.size
        ) {
            currentChunk = bootstrap
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position - bootstrapStartPosition).toInt()
        }

        // S1: materialise BEFORE the deferred schedule below. Placed after it,
        // scheduleChunks() would still see a null continuation and schedule the
        // whole aligned chunk -- paying the amplified fetch while merely hiding
        // it from the reader.
        if (pendingContinuationOpen && currentChunk == null && continuationSource == null) {
            materialisePendingContinuation()
        }

        if (bootstrapPrefetchDeferred && shouldAllowBackgroundPrefetch()) {
            bootstrapPrefetchDeferred = false
            scheduleChunks()
        }

        continuationSource?.let { source ->
            if (position < continuationEndPositionExclusive &&
                bytesRemaining > 0L &&
                (bootstrap == null || position >= bootstrapStartPosition + bootstrap.size)
            ) {
                val temp = ByteArray(minOf(toRead, READ_BUFFER_SIZE))
                val read = source.read(temp, 0, temp.size)
                if (read > 0) {
                    buffer.put(temp, 0, read)
                    position += read
                    bytesRemaining -= read
                    if (position >= continuationEndPositionExclusive) {
                        source.close()
                        continuationSource = null
                        continuationEndPositionExclusive = C.TIME_UNSET
                        scheduleChunks()
                    }
                    return read
                }
                if (read == C.RESULT_END_OF_INPUT || position >= continuationEndPositionExclusive) {
                    source.close()
                    continuationSource = null
                    continuationEndPositionExclusive = C.TIME_UNSET
                    scheduleChunks()
                }
            } else if (position >= continuationEndPositionExclusive || bytesRemaining <= 0L) {
                source.close()
                continuationSource = null
                continuationEndPositionExclusive = C.TIME_UNSET
            }
        }

        if (currentChunkIndex != chunkIndex || currentChunk == null) {
            val activeSession = session ?: return C.RESULT_END_OF_INPUT
            ensureChunkScheduled(chunkIndex)
            val future = activeSession.futures[chunkIndex] ?: return C.RESULT_END_OF_INPUT
            activeSession.noteRead(chunkIndex)
            try {
                // RS_CHUNK_WAIT: read() blocks on the WHOLE chunk future, so
                // ExoPlayer sees nothing of an 8 MB chunk until all 8 MB have
                // landed -- even though the first 500 KB arrived in ~60 ms.
                // Whether that gates the FIRST FRAME has never been measured,
                // and three different fixes depend on the answer. If ExoPlayer
                // renders before reading past BOOTSTRAP_READ_BYTES, chunk 0 is
                // irrelevant to TTFF. If it reads 1-3 MB, raising the bootstrap
                // constant is a one-line win. If it reads past a whole chunk,
                // only progressive in-flight reads help.
                //
                // Correlate these against first_frame_rendered in the capture:
                // the count before it, and the highest pos, is the answer.
                // preDone separates a real stall from an already-complete chunk.
                // site= distinguishes the two read() overloads; ExoPlayer's
                // progressive path uses the ByteArray one.
                val blockT0 = SystemClock.elapsedRealtime()
                val preDone = future.isDone
                currentChunk = future.get(60, TimeUnit.SECONDS)
                Log.i(
                    TAG,
                    "RS_CHUNK_WAIT site=bytebuffer pos=$position chunk=$chunkIndex " +
                        "waitMs=${SystemClock.elapsedRealtime() - blockT0} preDone=$preDone"
                )
            } catch (e: Exception) {
                if (closed.get()) return C.RESULT_END_OF_INPUT
                // P-F1: mirror of the byte[] path — cancel before dropping,
                // ownership-gated release if the download won the race.
                if (activeSession.futures.remove(chunkIndex, future)) {
                    activeSession.lastTouch.remove(chunkIndex)
                    if (!future.cancel(true) && future.isDone && !future.isCancelled) {
                        try {
                            releaseSessionBuffer(future.get().buffer, activeSession.chunkSize, maxPoolSize)
                        } catch (_: Exception) {
                        }
                    }
                }
                throw IOException("Failed to download chunk $chunkIndex", e)
            }
            currentChunkIndex = chunkIndex
            currentChunkReadOffset = (position % chunkSize).toInt()

            scheduleChunks()
        }

        val chunk = currentChunk ?: return C.RESULT_END_OF_INPUT
        val available = chunk.size - currentChunkReadOffset
        if (available <= 0) {
            if (chunk === bootstrapChunk) {
                bootstrapChunk = null
                bootstrapStartPosition = C.TIME_UNSET
            }
            currentChunk = null
            return read(buffer, length)
        }

        val readSize = minOf(toRead, available)
        val src = chunk.buffer.byteBuffer.duplicate()
        src.position(currentChunkReadOffset)
        src.limit(currentChunkReadOffset + readSize)
        buffer.put(src)
        
        currentChunkReadOffset += readSize
        position += readSize
        bytesRemaining -= readSize
        bytesServedThisOpen += readSize
        session?.noteRead(chunkIndex)

        return readSize
    }

    /**
     * nt13: schedule chunk 0 for [uri] onto a pending session, before the player
     * exists. Measured on 27 Jul 2026, chunk 0 starts 1,268-3,065 ms after the
     * stream URL is final -- the whole probe-and-build prefix is dead time from
     * chunk 0's point of view. Downloading needs neither the probe nor the file
     * length: downloadChunkOnce ranges against requestUri and, with no session
     * length yet, takes start + chunkSize.
     *
     * This instance is a throwaway scheduler: it borrows its own session field so
     * ensureChunkScheduled can run, then drops it. Ownership of the download sits
     * with the session (nt7), which outlives every instance.
     */
    internal fun prestartChunk0(uri: Uri) {
        val pending = obtainPendingSession(
            uri, emptyMap(), chunkSize, sessionChunkCap, maxPoolSize, effectivePrefetchDepth
        ) ?: return
        session = pending
        try {
            ensureChunkScheduled(0L)
            Log.i(
                TAG,
                "PRESTART: scheduled chunk 0 ahead of player build " +
                    "chunkSize=${chunkSize / 1024L}KB host=${uri.host} " +
                    "pathLen=${uri.path?.length ?: -1} queryLen=${uri.query?.length ?: -1} " +
                    "uriLen=${uri.toString().length}"
            )
        } finally {
            session = null
        }
    }

    /**
     * Factory for creating ParallelRangeDataSource instances.
     */
    class Factory(
        private val upstreamFactory: OkHttpDataSource.Factory,
        private val parallelConnections: Int = PlayerSettings.DEFAULT_PARALLEL_CONNECTION_COUNT,
        private val chunkSize: Long = PlayerSettings.DEFAULT_PARALLEL_CHUNK_SIZE_KB.toLong() * 1024,
        private val useNativeMemory: Boolean = false,
        private val prefetchDepthChunks: Int = parallelConnections + 1,
        private val shouldAllowBackgroundPrefetch: () -> Boolean = { true },
        private val onResolvedUri: (Uri?) -> Unit = {},
        private val allowContinuationReopen: Boolean = true
    ) : DataSource.Factory {
        @Volatile
        private var startupBootstrapCache: BootstrapCacheEntry? = null

        /**
         * nt13: pre-start chunk 0 through a throwaway instance of this factory, so
         * the session geometry is derived by exactly the code that will later open
         * the stream.
         */
        fun prestartChunk0(uri: Uri) {
            (createDataSource() as ParallelRangeDataSource).prestartChunk0(uri)
        }

        override fun createDataSource(): DataSource {
            return ParallelRangeDataSource(
                upstreamFactory = upstreamFactory,
                parallelConnections = parallelConnections,
                chunkSize = chunkSize,
                useNativeMemory = useNativeMemory,
                prefetchDepthChunks = prefetchDepthChunks,
                shouldAllowBackgroundPrefetch = shouldAllowBackgroundPrefetch,
                onResolvedUri = onResolvedUri,
                allowContinuationReopen = allowContinuationReopen,
                consumeBootstrapCache = { dataSpec ->
                    val cached = startupBootstrapCache ?: return@ParallelRangeDataSource null
                    val isFresh = SystemClock.uptimeMillis() - cached.createdAtUptimeMs <= 15_000L
                    if (!isFresh) {
                        startupBootstrapCache = null
                        return@ParallelRangeDataSource null
                    }
                    if (cached.startPosition != 0L || dataSpec.position != 0L) return@ParallelRangeDataSource null
                    if (dataSpec.position != cached.startPosition) return@ParallelRangeDataSource null
                    if (dataSpec.uri != cached.requestUri) return@ParallelRangeDataSource null
                    cached
                },
                updateBootstrapCache = { entry ->
                    startupBootstrapCache = entry
                }
            )
        }
    }
}

/**
 * nt8: single-slot head + tail byte windows captured by the prefetch-time
 * prewarm (PlayerPlaybackNetworking), consumed by ParallelRangeDataSource.
 *
 * The head IS the probe: same URI, position 0, the same 256 KiB window,
 * plus the total length from the 206's Content-Range -- everything open()
 * otherwise pays a network round trip to learn (289-1,023 ms probeOpen
 * across the 27 Jul capture). The tail is the Matroska Cues window the
 * extractor reads next (last 4 MiB), which otherwise costs a bounded
 * continuation GET. Sized from the 15 Aug 2026 AM9 capture: the Cues sat
 * 1.91-3.35 MB from EOF across four sources, so the original 1 MiB window
 * never covered them and every cold open paid a serialised Cues read of
 * 2.0-4.2 s. The window is a single heap slot under a 300 s TTL, outside
 * the native chunk budget, so the widening costs transient Java heap only.
 *
 * Ownership/threading: entries hold immutable heap arrays behind
 * @Volatile single slots; writers are OkHttp callback threads, readers
 * the player's open() path. Head consumption is one-shot (mirroring the
 * instance-level startupBootstrapCache semantics); the tail is
 * non-clearing within TTL so re-seeks into the Cues stay free. A URI
 * mismatch, an expired TTL, or an empty slot all fall through to today's
 * network path. Cached bytes stay valid even if the CDN link later
 * expires -- they were already downloaded; expiry surfaces on the chunk
 * downloads exactly as it does today.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
internal object PrefetchWindowStore {
    private const val TAG = "ParallelRangeDS"
    private const val TTL_MS = 300_000L
    const val TAIL_WINDOW_BYTES = 4_194_304L

    // B-1c (15 Aug 2026 AM9 A-1(a) capture): the details-page prefetch warms
    // every stream in the list (~13 titles observed), but the store held one
    // @Volatile head slot and one tail slot, so the pressed title's window was
    // routinely overwritten by a later put before the press consulted it --
    // 2 of 3 direct presses missed for exactly this reason. Keying by
    // requestUri lets warmed titles coexist so a press finds its own window
    // regardless of warm order.
    //
    // Cap 8, access-ordered LRU: the winner is always among the most recently
    // warmed few by press time. 256 KB head + 4 MiB tail per key => ~34 MiB
    // transient Java-heap ceiling at full occupancy, outside the native chunk
    // budget. Both maps are guarded by their own monitor: writers are OkHttp
    // dispatcher threads and readers the player thread, and LinkedHashMap is
    // not thread-safe (the previous single-ref design was lock-free only
    // because a reference assignment is atomic).
    private const val STORE_CAP = 8

    private val headEntries = object : LinkedHashMap<Uri, ParallelRangeDataSource.BootstrapCacheEntry>(STORE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, ParallelRangeDataSource.BootstrapCacheEntry>?): Boolean {
            return size > STORE_CAP
        }
    }

    private val tailEntries = object : LinkedHashMap<Uri, ParallelRangeDataSource.BootstrapCacheEntry>(STORE_CAP, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Uri, ParallelRangeDataSource.BootstrapCacheEntry>?): Boolean {
            return size > STORE_CAP
        }
    }

    fun putHead(entry: ParallelRangeDataSource.BootstrapCacheEntry) {
        synchronized(headEntries) {
            headEntries[entry.requestUri] = entry
        }
        Log.i(
            TAG,
            "PREFETCH_WINDOW put head bytes=${entry.bootstrapSize} " +
                "total=${entry.totalFileLength} host=${entry.resolvedUri?.host}"
        )
    }

    fun putTail(entry: ParallelRangeDataSource.BootstrapCacheEntry) {
        synchronized(tailEntries) {
            tailEntries[entry.requestUri] = entry
        }
        Log.i(TAG, "PREFETCH_WINDOW put tail start=${entry.startPosition} bytes=${entry.bootstrapSize}")
    }

    fun consumeHead(dataSpec: DataSpec): ParallelRangeDataSource.BootstrapCacheEntry? {
        if (dataSpec.position != 0L) return null
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) return null
        val cached = synchronized(headEntries) {
            val entry = headEntries[dataSpec.uri] ?: return null
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                headEntries.remove(dataSpec.uri)
                return null
            }
            if (entry.startPosition != 0L) return null
            // One-shot: remove on hit, mirroring the previous head = null.
            headEntries.remove(dataSpec.uri)
            entry
        }
        Log.i(TAG, "PREFETCH_WINDOW head hit bytes=${cached.bootstrapSize} total=${cached.totalFileLength}")
        return cached
    }

    fun peekTail(uri: Uri, position: Long): ParallelRangeDataSource.BootstrapCacheEntry? {
        val cached = synchronized(tailEntries) {
            val entry = tailEntries[uri] ?: return null
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                tailEntries.remove(uri)
                return null
            }
            if (position < entry.startPosition || position >= entry.startPosition + entry.bootstrapSize) return null
            // Non-clearing: leave the entry so re-seeks into the Cues stay free.
            entry
        }
        Log.i(TAG, "PREFETCH_WINDOW tail hit pos=$position start=${cached.startPosition}")
        return cached
    }

    /**
     * B-2: position-agnostic presence + TTL check used by the warm path to
     * decide whether the concurrent suffix-range tail already stored a window
     * for this URI, so the head-triggered fallback tail can skip a duplicate
     * 4 MiB fetch. Does not remove the entry.
     */
    fun hasFreshTail(uri: Uri): Boolean {
        return synchronized(tailEntries) {
            val entry = tailEntries[uri] ?: return false
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                tailEntries.remove(uri)
                return false
            }
            true
        }
    }

    /**
     * C-2: non-consuming peek of the warmed head bytes for [uri], for the AFR
     * preflight to parse a video frame rate from the first 256 KiB the prewarm
     * already holds. TTL-checked and non-clearing -- the subsequent open() still
     * consumes the head via consumeHead(). Returns null on miss/expiry so the
     * preflight falls through to today's post-prepare track-format AFR.
     */
    fun peekHead(uri: Uri): ByteArray? {
        return synchronized(headEntries) {
            val entry = headEntries[uri] ?: return null
            if (SystemClock.uptimeMillis() - entry.createdAtUptimeMs > TTL_MS) {
                headEntries.remove(uri)
                return null
            }
            entry.bootstrapData
        }
    }
}
