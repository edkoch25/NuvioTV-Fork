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

        // nt-tier2: HTTP 429/503 is server-side rate-limiting, not a stalled
        // socket. Back off before retrying (immediate retry just re-hits the
        // limit), and after repeated hits set a one-way session latch so
        // prefetch drops to a single connection. Only reachable when parallel
        // connections are enabled (opt-in; off by default).
        private const val RATE_LIMIT_MAX_BACKOFF_RETRIES = 3
        private const val RATE_LIMIT_CLAMP_THRESHOLD = 3
        private const val RATE_LIMIT_BACKOFF_BASE_MS = 500L
        private const val RATE_LIMIT_BACKOFF_MAX_MS = 3_000L
        private const val RATE_LIMIT_BACKOFF_JITTER_MS = 250L
        private const val RATE_LIMIT_SLEEP_SLICE_MS = 100L

        // nt3 Lever 1: the clamp above is no longer session-permanent. After a
        // quiet period with no 429/503 the read path lifts it and restores full
        // prefetch depth; each re-trip doubles the next required quiet period
        // (capped) so a persistently limiting server cannot make us oscillate.
        private const val RATE_LIMIT_RECOVERY_BASE_MS = 45_000L
        private const val RATE_LIMIT_RECOVERY_MAX_MS = 360_000L

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

        /** Cooldown left before the clamp may lift; 0 when not latched. HUD read only. */
        fun hudClampCooldownRemainingMs(nowUptimeMs: Long): Long {
            if (!hudClampLatched) return 0L
            val trips = hudClampTrips.coerceAtLeast(1)
            val cooldownMs = (RATE_LIMIT_RECOVERY_BASE_MS shl (trips - 1).coerceAtMost(3))
                .coerceAtMost(RATE_LIMIT_RECOVERY_MAX_MS)
            return (cooldownMs - (nowUptimeMs - hudClampLastHitAtMs)).coerceAtLeast(0L)
        }

        private class ChunkSession(
            val requestUri: Uri,
            val requestHeaders: Map<String, String>,
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
            // nt-tier2: set when the server rate-limits us repeatedly; the
            // read path then holds prefetch to a single connection for this
            // session. nt3 Lever 1: no longer one-way — after a quiet cooldown
            // with no further 429/503 the read path lifts the clamp
            // (tryRecoverFromRateLimit below); each re-trip doubles the next
            // cooldown, capped at RATE_LIMIT_RECOVERY_MAX_MS.
            val rateLimited = AtomicBoolean(false)
            val rateLimit429s = AtomicInteger(0)
            // nt3 Lever 1: uptime stamp of the most recently observed 429/503
            // (slides on every hit, so the cooldown measures true quiet time),
            // and how many times the clamp has tripped this session (drives
            // the exponential cooldown).
            @Volatile var lastRateLimitAtMs: Long = 0L
            val rateLimitClampCount = AtomicInteger(0)
            val activeSources: MutableSet<DataSource> = java.util.concurrent.ConcurrentHashMap.newKeySet()
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

            /**
             * nt3 Lever 1: lift the rate-limit clamp once no 429/503 has been
             * observed for the current cooldown. Returns true when the clamp
             * is (now) inactive, false while it must still hold. Called from
             * the read path; trips happen on download threads — the CAS makes
             * that race benign (a concurrent 429 simply re-stamps
             * lastRateLimitAtMs and can re-trip as normal).
             */
            fun tryRecoverFromRateLimit(): Boolean {
                if (!rateLimited.get()) return true
                val trips = rateLimitClampCount.get().coerceAtLeast(1)
                val cooldownMs = (RATE_LIMIT_RECOVERY_BASE_MS shl (trips - 1).coerceAtMost(3))
                    .coerceAtMost(RATE_LIMIT_RECOVERY_MAX_MS)
                if (SystemClock.uptimeMillis() - lastRateLimitAtMs < cooldownMs) return false
                if (rateLimited.compareAndSet(true, false)) {
                    rateLimit429s.set(0)
                    hudClampLatched = false
                    Log.i(TAG, "Rate-limit cooldown (${cooldownMs}ms) elapsed with no further " +
                        "429/503; restoring parallel prefetch (clamp trips this session: $trips)")
                }
                return true
            }
        }

        private val sessionLock = Any()
        private var currentChunkSession: ChunkSession? = null

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
                }
                // nt6: fresh session, fresh clamp story for the HUD.
                hudClampLatched = false
                hudClampTrips = 0
                hudClampLastHitAtMs = 0L
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
            // S1: only when the target chunk is not already held. A reopen INTO a
            // held chunk (the fill reopen after the tail seek) keeps today's
            // instant path, which is what preserves its ~600 ms buffered head
            // start at first frame.
            pendingContinuationOpen = allowContinuationReopen &&
                attachedSession.futures[position / chunkSize] == null
            Log.d(
                TAG,
                "Attached to warm session for reopen at $position, " +
                    "file=${totalFileLength / 1024 / 1024}MB, held=${attachedSession.futures.size} chunk(s) (probe skipped)"
            )
            return bytesRemaining
        }

        consumeBootstrapCache(dataSpec)?.let { cached ->
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

        val openLength: Long
        try {
            openLength = probeSource.open(dataSpec)
            resolvedUri = probeSource.uri // Final URL after redirects (CDN URL)
            onResolvedUri(resolvedUri)
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
            val chunk = readBootstrapChunk(probeSource, bootstrapBytes)
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
            probeSource.close()
        } else {
            probeSource.close()
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
            try {
                currentChunk = future.get(60, TimeUnit.SECONDS)
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
        // nt-tier2: once the session is rate-limited, hold prefetch to a single
        // connection so we stop fanning out into the limit. nt3 Lever 1: the
        // clamp lifts after a quiet cooldown (tryRecoverFromRateLimit) instead
        // of holding for the rest of the session; a null session keeps the
        // pre-clamp behaviour (fall through to the earned-prefetch gate).
        val maxAhead = when {
            session?.tryRecoverFromRateLimit() == false -> 1
            bytesServedThisOpen >= EARNED_PREFETCH_BYTES -> effectivePrefetchDepth
            else -> 1
        }

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
            if (future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {
                // nt7: downloads belong to the session, not the instance —
                // only future cancellation or session teardown aborts them.
                if (activeSession.abandoned.get() || future.isCancelled) throw IOException("Session abandoned or cancelled")
                lastException = e
                // nt-tier2: 429/503 is server rate-limiting, not a stalled socket.
                // Hand off to a bounded backoff loop rather than retrying now.
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

    private fun downloadChunkOnce(activeSession: ChunkSession, chunkIndex: Long, future: CompletableFuture<*>): DownloadedChunk {
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
            val chunk = readIntoChunk(activeSession, ds, future, expectedBytes)
            Log.d(TAG, "Successfully downloaded chunk $chunkIndex, size=${chunk.size} bytes")
            return chunk
        } finally {
            activeSession.activeSources.remove(ds)
            try { ds.close() } catch (_: Exception) {}
        }
    }

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
     * nt-tier2: dedicated backoff-retry for a rate-limited chunk. Bounded and
     * cancellation-aware; on repeated hits it latches the session to a single
     * connection. When the budget is spent it throws, and the existing failure
     * handling / auto-recovery takes over — i.e. never worse than before.
     */
    private fun downloadChunkWithRateLimitBackoff(
        activeSession: ChunkSession,
        chunkIndex: Long,
        future: CompletableFuture<*>,
        firstError: HttpDataSource.InvalidResponseCodeException
    ): DownloadedChunk {
        var rl: HttpDataSource.InvalidResponseCodeException = firstError
        var lastException: Exception = firstError
        var attempt = 0
        while (attempt < RATE_LIMIT_MAX_BACKOFF_RETRIES) {
            // nt3 Lever 1: stamp every observed 429/503 so the recovery
            // cooldown measures quiet time since the LAST hit, not since the
            // moment the clamp tripped.
            activeSession.lastRateLimitAtMs = SystemClock.uptimeMillis()
            hudClampLastHitAtMs = activeSession.lastRateLimitAtMs
            if (activeSession.rateLimit429s.incrementAndGet() >= RATE_LIMIT_CLAMP_THRESHOLD &&
                activeSession.rateLimited.compareAndSet(false, true)) {
                val trips = activeSession.rateLimitClampCount.incrementAndGet()
                hudClampLatched = true
                hudClampTrips = trips
                Log.w(TAG, "Rate-limited (HTTP ${rl.responseCode}) repeatedly; clamping session to " +
                    "single connection (trip #$trips this session)")
            }
            val waitMs = rateLimitBackoffMs(attempt, rl)
            Log.w(TAG, "Chunk $chunkIndex rate-limited (HTTP ${rl.responseCode}); backing off ${waitMs}ms " +
                "(attempt ${attempt + 1}/$RATE_LIMIT_MAX_BACKOFF_RETRIES)")
            if (!sleepInterruptibly(waitMs, future, activeSession)) throw IOException("Cancelled during rate-limit backoff")
            if (future.isCancelled || activeSession.abandoned.get()) throw IOException("Cancelled")
            try {
                return downloadChunkOnce(activeSession, chunkIndex, future)
            } catch (e: Exception) {
                if (activeSession.abandoned.get() || future.isCancelled) throw IOException("Session abandoned or cancelled")
                lastException = e
                // A non-rate-limit error after a 429 is a different failure: surface it.
                rl = e.findRateLimitException() ?: throw e
                attempt++
            }
        }
        throw IOException("Chunk $chunkIndex still rate-limited after $RATE_LIMIT_MAX_BACKOFF_RETRIES backoffs", lastException)
    }

    /** nt-tier2: honour Retry-After (seconds) capped, else exponential + jitter. */
    private fun rateLimitBackoffMs(attempt: Int, rl: HttpDataSource.InvalidResponseCodeException): Long {
        val retryAfterMs = rl.headerFields.entries
            .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
            ?.value?.firstOrNull()?.trim()?.toLongOrNull()
            ?.let { it * 1000L }
        val base = retryAfterMs ?: (RATE_LIMIT_BACKOFF_BASE_MS shl attempt.coerceIn(0, 3))
        val capped = base.coerceIn(RATE_LIMIT_BACKOFF_BASE_MS, RATE_LIMIT_BACKOFF_MAX_MS)
        return capped + (Math.random() * RATE_LIMIT_BACKOFF_JITTER_MS).toLong()
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
        ds: DataSource,
        future: CompletableFuture<*>,
        expectedBytes: Long
    ): DownloadedChunk {
        val buffer = acquireBuffer()
        val tempArray = readBufferLocal.get()!!
        var totalRead = 0
        var consecutiveZeroReads = 0
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
            }
            // pre-nt3 short-chunk rejection: a premature EOF inside a known
            // range must not produce a cached "complete" chunk — a short
            // non-final chunk otherwise dead-ends both read paths at the
            // phantom chunk boundary.
            if (expectedBytes > 0L && totalRead < expectedBytes && !activeSession.abandoned.get()) {
                throw IOException("Short chunk: read $totalRead of $expectedBytes bytes")
            }
        } catch (e: Exception) {
            releaseBuffer(buffer)
            if (activeSession.abandoned.get()) throw IOException("Session abandoned")
            throw e
        }
        if (activeSession.abandoned.get()) {
            releaseBuffer(buffer)
            throw IOException("Session abandoned")
        }
        buffer.byteBuffer.flip()
        return DownloadedChunk(buffer, totalRead)
    }

    /** Read only a small startup window from an already-opened DataSource. */
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
                currentChunk = future.get(60, TimeUnit.SECONDS)
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
