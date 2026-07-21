package com.nuvio.tv.core.network

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import okhttp3.Request
import kotlinx.coroutines.launch

@UnstableApi
object StreamSpeedTester {

    // Byte budget + warm-up (community speed-test proposal, section 3).
    //
    // Warm-up: discard the first WARMUP_BYTES (or WARMUP_MAX_MS, whichever
    // first) before starting the measurement clock - removes TCP slow-start
    // bias that drags short-test numbers down.
    // Measure: stop at MEASURE_BYTES transferred OR MEASURE_MAX_MS elapsed,
    // whichever first, and report bytes/elapsed over the measured window only.
    // Worst case ~72 MB per pass vs ~1 GB across a run under the old pure
    // 8-second window at gigabit speeds - strictly lower debrid/429 exposure,
    // while slow links still get the full 8 s window.
    private const val WARMUP_BYTES = 8L * 1024 * 1024
    private const val WARMUP_MAX_MS = 750L
    private const val MEASURE_BYTES = 64L * 1024 * 1024
    private const val MEASURE_MAX_MS = 8_000L

    // Stability sub-windows: a TIMER coroutine snapshots the NETWORK tally
    // every SUB_WINDOW_MS and records per-window Mbps - same tally as the
    // headline, zero extra network cost. Sampling must NOT live inside the
    // read loop: with chunk-granular prefetch a single read can block for a
    // whole chunk's download, so an in-loop clock check fired once or twice
    // per pass on large chunks and starved the sampler (field-diagnosed as
    // "0 passes with >= 4 sub-windows"). MEASURE_MIN_MS floors the window
    // so fast links can't finish the byte budget before enough windows
    // exist; the headline stays delta/elapsed either way.
    private const val SUB_WINDOW_MS = 500L
    private const val MEASURE_MIN_MS = 2_500L

    /**
     * Headline Mbps plus the per-sub-window Mbps series behind it.
     * [failureReason] is non-null when the pass died (crash-hardening leg 1,
     * 19 Jul 2026 incident): the cell failed, was cleaned up, and the sweep
     * should record it and continue rather than abort or crash.
     */
    data class ParallelPassResult(
        val mbps: Double,
        val subWindowMbps: List<Double>,
        val failureReason: String? = null,
        /**
         * N3b: how many times this cell's chunk session tripped the 429
         * rate-limit clamp. Non-zero means the cell did NOT run at its
         * labelled connection count - the clamp drops it to a single
         * connection - so its throughput describes a different
         * configuration than the one under test and must not be measured
         * against the others.
         */
        val clampTrips: Int = 0
    )

    // 1. Measures single connection baseline speed (standard OkHttp)
    suspend fun runBaselineTest(
        url: String,
        headers: Map<String, String>
    ): Double = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()

            PlayerPlaybackNetworking.playbackHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0.0
                val inputStream = response.body?.byteStream() ?: return@withContext 0.0
                val buffer = ByteArray(64 * 1024)

                // Warm-up: discard bytes, clock not running yet.
                var warmed = 0L
                val warmStart = System.currentTimeMillis()
                while (warmed < WARMUP_BYTES &&
                    System.currentTimeMillis() - warmStart < WARMUP_MAX_MS
                ) {
                    val read = inputStream.read(buffer)
                    if (read == -1) return@withContext 0.0
                    warmed += read
                }

                // Measured window.
                var totalBytes = 0L
                val tStart = System.currentTimeMillis()
                val tDeadline = tStart + MEASURE_MAX_MS
                while (totalBytes < MEASURE_BYTES && System.currentTimeMillis() < tDeadline) {
                    val read = inputStream.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                }
                val elapsed = (System.currentTimeMillis() - tStart).coerceAtLeast(1)
                return@withContext (totalBytes * 8.0) / (elapsed * 1000.0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext 0.0
        }
        @Suppress("UNREACHABLE_CODE")
        0.0
    }

    // 2. Measures parallel connection speed at a specific connection count and
    // chunk size (both now swept by the orchestrator - the old test varied
    // chunk size only, at a fixed connection count, despite its labels).
    suspend fun runParallelChunkTest(
        url: String,
        headers: Map<String, String>,
        chunkSizeBytes: Long,
        parallelConnections: Int,
        // Crash-hardening leg 2 (19 Jul 2026 incident): the window is now the
        // CALLER's budget-derived figure (MemoryBudget.sweepCellPrefetchDepth),
        // not an unconditional connections*4 — which on a 3 conn / 64 MB cell
        // permitted a ~1 GB session ceiling against the S905X5M's 250 MB safe
        // native budget and killed the app mid-sweep.
        prefetchDepthChunks: Int
    ): ParallelPassResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // AtomicLong: the tally is incremented from multiple connection threads,
        // and plain 64-bit writes can tear on 32-bit ABIs.
        val totalBytesDownloaded = java.util.concurrent.atomic.AtomicLong(0L)

        val transferListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) {
                    totalBytesDownloaded.addAndGet(bytesTransferred.toLong())
                }
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }

        var openSource: ParallelRangeDataSource? = null
        var sampler: kotlinx.coroutines.Job? = null
        try {
            // Fresh session per cell: a retained session from the previous cell
            // (or from playback, nt6) carries THAT cell's chunk cap and warm
            // chunks — wrong window bounds and a warm-start bias for a
            // measurement. Tear it down so every cell opens cold with its own
            // budget-derived cap. (The assessment flow runs with the player
            // closed; anything a live player retained would be re-fetched on
            // its next seek, a cost, not a correctness issue.)
            ParallelRangeDataSource.releaseRetainedSession()
            val okHttpFactory = OkHttpDataSource.Factory(PlayerPlaybackNetworking.playbackHttpClient).apply {
                setDefaultRequestProperties(headers)
            }
            // Use existing ParallelRangeDataSource from the app
            val dataSource = ParallelRangeDataSource(
                upstreamFactory = okHttpFactory,
                parallelConnections = parallelConnections,
                chunkSize = chunkSizeBytes,
                useNativeMemory = true,
                prefetchDepthChunks = prefetchDepthChunks
            ).apply {
                addTransferListener(transferListener)
            }
            openSource = dataSource
            dataSource.open(DataSpec(android.net.Uri.parse(url)))
            val buffer = ByteArray(64 * 1024)

            // The parallel source PREFETCHES ahead of reads, so read progress and
            // network progress are decoupled: reads can drain already-downloaded
            // chunks at RAM speed. Both the warm-up and the measure window must
            // therefore be conditioned on the NETWORK tally, never on read bytes
            // (conditioning on reads produced absurd figures - a 64 MB read pass
            // completing in milliseconds against a tiny network delta).
            var eof = false

            // Warm-up: keep the pipeline draining until WARMUP_BYTES have crossed
            // the network, or the warm-up window expires.
            val warmStart = System.currentTimeMillis()
            while (totalBytesDownloaded.get() < WARMUP_BYTES &&
                System.currentTimeMillis() - warmStart < WARMUP_MAX_MS
            ) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == -1) { eof = true; break }
            }

            val networkAtMeasureStart = totalBytesDownloaded.get()
            val tStart = System.currentTimeMillis()
            val tDeadline = tStart + MEASURE_MAX_MS
            val subWindowMbps = mutableListOf<Double>()
            val samplerJob = launch {
                var wStartMs = System.currentTimeMillis()
                var wStartBytes = totalBytesDownloaded.get()
                while (true) {
                    kotlinx.coroutines.delay(SUB_WINDOW_MS)
                    val now = System.currentTimeMillis()
                    val bytes = totalBytesDownloaded.get()
                    subWindowMbps += ((bytes - wStartBytes) * 8.0) /
                        ((now - wStartMs) * 1000.0)
                    wStartMs = now
                    wStartBytes = bytes
                }
            }
            sampler = samplerJob
            // Run to BOTH the byte budget and the minimum duration; the last
            // partial window is simply dropped when the sampler is cancelled.
            while (!eof &&
                (totalBytesDownloaded.get() - networkAtMeasureStart < MEASURE_BYTES ||
                    System.currentTimeMillis() - tStart < MEASURE_MIN_MS) &&
                System.currentTimeMillis() < tDeadline
            ) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == -1) { eof = true }
            }
            val endMs = System.currentTimeMillis()
            samplerJob.cancel()
            samplerJob.join()
            val elapsed = (endMs - tStart).coerceAtLeast(1)
            val networkDelta = totalBytesDownloaded.get() - networkAtMeasureStart
            // N3b: read the clamp trip count BEFORE closing, and read the
            // companion counter rather than ChunkSession.rateLimited - the
            // boolean is CLEARED by the Lever 1 recovery path, so a clamp
            // that fired and recovered inside this cell would be invisible
            // to an end-of-cell boolean read. The counter is reset in
            // obtainSession() whenever a fresh session is created, and the
            // sweep calls releaseRetainedSession() before every cell, so
            // this is per-cell. If a warm session were ever attached the
            // count would carry over and OVER-report: that errs toward
            // invalidating a cell, which is the safe direction here.
            val clampTrips = ParallelRangeDataSource.hudClampTrips
            dataSource.close()

            return@withContext ParallelPassResult(
                mbps = (networkDelta * 8.0) / (elapsed * 1000.0),
                subWindowMbps = subWindowMbps.toList(),
                clampTrips = clampTrips
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Crash-hardening leg 1 (19 Jul 2026 incident): a sweep cell must
            // fail, never the process. Throwable, not Exception, because the
            // proven killer is OutOfMemoryError — an Error — from chunk-buffer
            // allocation. Free everything the dead cell holds (its session's
            // chunks and the idle pool for this chunk size) so the next cell
            // starts from a clean slate, then report the failure upward.
            android.util.Log.e(
                "StreamSpeedTester",
                "Sweep cell failed (${parallelConnections}c/${chunkSizeBytes / (1024L * 1024L)}MB)",
                t
            )
            ParallelRangeDataSource.releaseRetainedSession()
            ParallelRangeDataSource.drainIdleBuffers(chunkSizeBytes)
            val reason = t.javaClass.simpleName +
                (t.message?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
            return@withContext ParallelPassResult(0.0, emptyList(), failureReason = reason)
        } finally {
            sampler?.cancel()
            try {
                openSource?.close()
            } catch (_: Exception) {
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ParallelPassResult(0.0, emptyList())
    }

    suspend fun getStreamContentLength(
        url: String,
        headers: Map<String, String>
    ): Long = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).head().apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            PlayerPlaybackNetworking.playbackHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val len = response.headers["Content-Length"]?.toLongOrNull()
                    if (len != null && len > 0) return@withContext len
                }
            }

            // Fallback to GET request if HEAD is not allowed/supported
            val getRequest = Request.Builder().url(url).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            PlayerPlaybackNetworking.playbackHttpClient.newCall(getRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body
                    if (body != null) {
                        return@withContext body.contentLength().coerceAtLeast(0L)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        0L
    }
}
