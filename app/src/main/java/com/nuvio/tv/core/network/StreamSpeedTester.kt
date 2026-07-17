package com.nuvio.tv.core.network

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.nuvio.tv.ui.screens.player.ParallelRangeDataSource
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import okhttp3.Request

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

    // Stability sub-windows: snapshot the NETWORK tally roughly every
    // SUB_WINDOW_MS during the measured window and record per-window Mbps.
    // Same tally the headline figure uses - zero extra network cost. Windows
    // are normalised by their actual elapsed time, so a read blocking past
    // the nominal boundary widens the window rather than skewing the rate.
    // A trailing partial window shorter than SUB_WINDOW_MIN_TAIL_MS is
    // discarded as noise.
    private const val SUB_WINDOW_MS = 500L
    private const val SUB_WINDOW_MIN_TAIL_MS = 250L

    /** Headline Mbps plus the per-sub-window Mbps series behind it. */
    data class ParallelPassResult(val mbps: Double, val subWindowMbps: List<Double>)

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
        parallelConnections: Int
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

        try {
            val okHttpFactory = OkHttpDataSource.Factory(PlayerPlaybackNetworking.playbackHttpClient).apply {
                setDefaultRequestProperties(headers)
            }
            // Use existing ParallelRangeDataSource from the app
            val speedTestPrefetchDepth = parallelConnections * 4
            val dataSource = ParallelRangeDataSource(
                upstreamFactory = okHttpFactory,
                parallelConnections = parallelConnections,
                chunkSize = chunkSizeBytes,
                useNativeMemory = true,
                prefetchDepthChunks = speedTestPrefetchDepth
            ).apply {
                addTransferListener(transferListener)
            }
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
            var windowStartMs = tStart
            var windowStartBytes = networkAtMeasureStart
            while (!eof &&
                totalBytesDownloaded.get() - networkAtMeasureStart < MEASURE_BYTES &&
                System.currentTimeMillis() < tDeadline
            ) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == -1) { eof = true }
                val nowMs = System.currentTimeMillis()
                if (nowMs - windowStartMs >= SUB_WINDOW_MS) {
                    val bytesNow = totalBytesDownloaded.get()
                    subWindowMbps += ((bytesNow - windowStartBytes) * 8.0) /
                        ((nowMs - windowStartMs) * 1000.0)
                    windowStartMs = nowMs
                    windowStartBytes = bytesNow
                }
            }
            val endMs = System.currentTimeMillis()
            if (endMs - windowStartMs >= SUB_WINDOW_MIN_TAIL_MS) {
                val bytesNow = totalBytesDownloaded.get()
                subWindowMbps += ((bytesNow - windowStartBytes) * 8.0) /
                    ((endMs - windowStartMs) * 1000.0)
            }
            val elapsed = (endMs - tStart).coerceAtLeast(1)
            val networkDelta = totalBytesDownloaded.get() - networkAtMeasureStart
            dataSource.close()

            return@withContext ParallelPassResult(
                mbps = (networkDelta * 8.0) / (elapsed * 1000.0),
                subWindowMbps = subWindowMbps.toList()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext ParallelPassResult(0.0, emptyList())
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
