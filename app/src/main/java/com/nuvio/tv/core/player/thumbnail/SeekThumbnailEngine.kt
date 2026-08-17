/*
 * NuvioTV-Fork - seek-thumbnail workstream (T-series)
 * Copyright (C) 2026 NuvioTV-Fork contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.nuvio.tv.core.player.thumbnail

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import android.media.MediaMetadataRetriever
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * T-series Build 4: P3 main-file seek-thumbnail engine.
 *
 * On-device findings driving this revision (nt9 + nt10 logs, AM9 Pro):
 * - media3's GL frame pipeline (which EFE always routes frames through, effects or not,
 *   fresh instance or reused) intermittently throws framework/vendor-level "Unbalanced
 *   enter/exit" on this Amlogic SoC, especially under concurrent playback GPU load. No EFE
 *   configuration avoids it - the GL pipeline itself is the incompatibility. Extraction now
 *   uses the platform's GL-free path: MediaMetadataRetriever.getScaledFrameAtTime with
 *   OPTION_CLOSEST_SYNC (seek doctrine preserved). Build 7: ONE retriever reused per session
 *   (setDataSource paid once), on Dispatchers.IO, returning an owned pre-scaled bitmap
 *   (no GL, no CPU rescale, no borrowed-bitmap hazard).
 * - MMR failures are opaque (no HTTP status), so the explicit 429 session-stop is replaced by
 *   its protections: inter-frame pacing keeps connection churn low and the consecutive-failure
 *   streak aborts the session; playback is never the casualty.
 * - Bucket 0 extracts at 5 s, not 0 s - the 0 s frame of most titles is a black fade-in.
 * - Density derived from the measured scrub ramp (PlayerScrubRates: 10 s steps then 20 s
 *   steps at ~6 repeats/s => ~60-120 s of content per real second while ramping): coarse
 *   pass at 5 min matches sweep readability; 60 s final density serves the rest/landing
 *   moment (thumb <=30 s off, proportionate to CLOSEST_SYNC landing). Halves frame count,
 *   connection churn (429 exposure) and disk vs 30 s spacing.
 * - Coarse-first ordering (stride 5 -> 1 over 60 s buckets = 5 min -> 60 s)
 *   gives full-timeline coverage within the first ~2 minutes; nearest-thumb serving makes
 *   far seeks show an approximate frame instead of a blank; disk loads are rest-debounced so
 *   the accelerating held-seek ramp cannot flood IO.
 * - Bitmap ownership: EFE's frame bitmap is borrowed/read-only - always copy, never recycle.
 */
object SeekThumbnails {
    private const val TAG = "ThumbWorker"
    const val SPACING_MS = 60_000L
    private const val TARGET_HEIGHT = 360
    private const val GATE_BUFFER_AHEAD_MS = 20_000L
    private const val MAX_CONSECUTIVE_FAILURES = 3
    private const val INTER_FRAME_DELAY_MS = 1_200L
    private const val NEAREST_RADIUS_BUCKETS = 5
    private const val DISK_LOAD_DEBOUNCE_MS = 300L

    /** Bumped whenever a bitmap lands in the memory cache; the pane keys recomposition on it. */
    val tick = mutableIntStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var session: Session? = null

    fun stopSession() {
        session?.stop()
        session = null
    }

    suspend fun startWhenEligible(
        context: Context,
        url: String,
        titleKey: String,
        playerProvider: () -> ExoPlayer?
    ) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        var width = 0
        var height = 0
        var colorTransfer: Int? = null
        var durationMs = 0L
        var ready = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val p = playerProvider()
            val fmt = p?.videoFormat
            val dur = p?.duration ?: C.TIME_UNSET
            if (fmt != null && dur != C.TIME_UNSET && dur > 0) {
                width = fmt.width
                height = fmt.height
                colorTransfer = fmt.colorInfo?.colorTransfer
                durationMs = dur
                ready = true
                break
            }
            delay(500L)
        }
        if (!ready) {
            Log.i(TAG, "skip: player format/duration not available in time")
            return
        }
        val isSdr = colorTransfer == null ||
            (colorTransfer != C.COLOR_TRANSFER_ST2084 && colorTransfer != C.COLOR_TRANSFER_HLG)
        if (height > 1080 || width > 1920 || !isSdr) {
            Log.i(TAG, "skip: not <=1080p SDR (${width}x${height} ct=$colorTransfer)")
            return
        }
        stopSession()
        val s = Session(context, url, titleKey, durationMs, playerProvider)
        session = s
        s.start()
    }

    fun thumbFor(positionMs: Long): Bitmap? = session?.thumbFor(positionMs)

    private class Session(
        context: Context,
        private val url: String,
        titleKey: String,
        private val durationMs: Long,
        private val playerProvider: () -> ExoPlayer?
    ) {
        private val appContext = context.applicationContext
        private val cache = ThumbnailCache(appContext, "$titleKey|s${SPACING_MS / 1000}", durationMs)
        private var workerJob: Job? = null
        private val diskLoadsInFlight = HashSet<Long>()
        private var lastDiskLoadRequestAt = 0L

        // T-series Build 7 probe: ONE MediaMetadataRetriever reused across the whole session.
        // The worker loop awaits each extractWithRecovery before the next bucket, so this
        // instance is touched strictly sequentially - never concurrently - even though
        // successive frames may run on different IO-pool threads. @Volatile guards visibility.
        @Volatile private var retriever: MediaMetadataRetriever? = null
        @Volatile private var srcAspect: Float = 16f / 9f

        fun start() {
            workerJob = scope.launch {
                val lastBucket = (durationMs - 1) / SPACING_MS
                // Coarse-first ordering: full-timeline coverage early, then refine.
                val order = buildList {
                    val seen = HashSet<Long>()
                    for (stride in listOf(5L, 1L)) {
                        var b = 0L
                        while (b <= lastBucket) {
                            if (seen.add(b)) add(b)
                            b += stride
                        }
                    }
                }
                var generated = 0
                var failures = 0
                Log.i(TAG, "session start: buckets=0..$lastBucket coarse-first, mmr-extraction")
                try {
                    for (bucket in order) {
                        if (cache.hasDisk(bucket)) continue
                        awaitGate()
                        delay(INTER_FRAME_DELAY_MS)
                        val positionMs = (bucket * SPACING_MS)
                            .coerceAtLeast(if (bucket == 0L) 5_000L else 0L)
                            .coerceAtMost(durationMs - 1)
                        val bmp = extractWithRecovery(positionMs, bucket)
                        if (bmp == null) {
                            failures++
                            Log.w(TAG, "bucket=$bucket failed after retry (streak=$failures)")
                            if (failures >= MAX_CONSECUTIVE_FAILURES) {
                                Log.w(TAG, "aborting session after $failures consecutive failures")
                                break
                            }
                            continue
                        }
                        failures = 0
                        val written = withContext(Dispatchers.IO) { cache.writeDisk(bucket, bmp) }
                        if (written) {
                            if (generated == 0) Log.i(TAG, "first thumb ${bmp.width}x${bmp.height}")
                            cache.putMem(bucket, bmp)
                            tick.intValue++
                            generated++
                            if (generated == ((lastBucket / 5L) + 1L).toInt()) {
                                Log.i(TAG, "coarse pass complete (~5min spacing) generated=$generated")
                            }
                        }
                    }
                    Log.i(TAG, "session pass complete: generated=$generated")
                } finally {
                    // Release the session-shared retriever off-main; NonCancellable so it
                    // still runs when the session was cancelled (title switch / stop()).
                    withContext(NonCancellable + Dispatchers.IO) { releaseRetriever() }
                }
            }
        }

        fun stop() {
            workerJob?.cancel()
            workerJob = null
        }

        fun thumbFor(positionMs: Long): Bitmap? {
            val lastBucket = (durationMs - 1) / SPACING_MS
            val bucket = (positionMs / SPACING_MS).coerceIn(0, lastBucket)
            cache.getMem(bucket)?.let { return it }
            // Nearest-thumb serving: an approximate frame beats a blank during ramped seeks.
            var nearest: Bitmap? = null
            for (d in 1..NEAREST_RADIUS_BUCKETS) {
                cache.getMem(bucket - d)?.let { nearest = it }
                if (nearest == null) cache.getMem(bucket + d)?.let { nearest = it }
                if (nearest != null) break
            }
            requestDiskLoad(bucket)
            return nearest
        }

        /** Rest-debounced async disk load: at most one dispatch per DISK_LOAD_DEBOUNCE_MS. */
        private fun requestDiskLoad(bucket: Long) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDiskLoadRequestAt < DISK_LOAD_DEBOUNCE_MS) return
            if (!cache.hasDisk(bucket) || !diskLoadsInFlight.add(bucket)) return
            lastDiskLoadRequestAt = now
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { cache.readDisk(bucket) }
                diskLoadsInFlight.remove(bucket)
                if (bmp != null) {
                    cache.putMem(bucket, bmp)
                    tick.intValue++
                }
            }
        }

        /** Playback-first gate: playing AND >=20 s buffer ahead, or buffered to the end. */
        private suspend fun awaitGate() {
            while (true) {
                val p = playerProvider()
                if (p != null && p.isPlaying) {
                    val ahead = p.bufferedPosition - p.currentPosition
                    val dur = p.duration
                    val bufferedToEnd = dur != C.TIME_UNSET && dur > 0 &&
                        p.bufferedPosition >= dur - 1_000L
                    if (ahead >= GATE_BUFFER_AHEAD_MS || bufferedToEnd) return
                }
                delay(1_000L)
            }
        }

        /**
         * Up to two attempts. CancellationException is RETHROWN, never retried or counted
         * toward the failure streak: the previous runCatching swallowed job cancellation,
         * producing dead retry-after-cancel work and false streak entries (nt12 log - every
         * "failure" there was JobCancellationException from a title switch). On a real
         * exception the shared retriever is dropped so the next attempt/bucket reopens a
         * clean instance (reuse-poison guard, the MMR analogue of the EFE self-poison).
         */
        private suspend fun extractWithRecovery(positionMs: Long, bucket: Long): Bitmap? {
            for (attempt in 1..2) {
                try {
                    val frame = extractFrame(positionMs)
                    if (frame != null) return frame
                    Log.w(TAG, "bucket=$bucket attempt$attempt: null (frame unavailable)")
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "bucket=$bucket attempt$attempt: ${describe(t)}")
                    releaseRetriever()
                }
            }
            return null
        }

        /**
         * Opens the session-shared retriever exactly once (the expensive setDataSource -
         * HTTP connect + container parse, ~11-14 s over debrid in the nt12 log). Subsequent
         * frames reuse it, so the per-frame open cost that dominated the coarse pass is paid
         * a single time. Caller runs on Dispatchers.IO. Sequential access only (see field).
         */
        private fun ensureRetrieverBlocking(): MediaMetadataRetriever {
            retriever?.let { return it }
            val r = MediaMetadataRetriever()
            val tOpen0 = SystemClock.elapsedRealtime()
            r.setDataSource(url, emptyMap())
            val tOpen1 = SystemClock.elapsedRealtime()
            val srcW = r.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            val srcH = r.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
            srcAspect = if (srcW > 0 && srcH > 0) srcW.toFloat() / srcH.toFloat() else 16f / 9f
            Log.i(TAG, "mmr open=${tOpen1 - tOpen0}ms (setDataSource, session-shared)")
            retriever = r
            return r
        }

        private fun releaseRetriever() {
            retriever?.let { r -> runCatching { r.release() } }
            retriever = null
        }

        /**
         * GL-free extraction on the shared retriever: getScaledFrameAtTime decodes via the
         * platform codec straight to an OWNED, pre-scaled bitmap. OPTION_CLOSEST_SYNC =
         * nearest keyframe (rev5 S2 doctrine). Logs per-frame decode time and a 9-point pixel
         * signature so the probe can confirm repeated seeks on ONE instance return DISTINCT,
         * correct frames - the reuse behaviour that was previously [unverified].
         */
        private suspend fun extractFrame(positionMs: Long): Bitmap? =
            withContext(Dispatchers.IO) {
                val r = ensureRetrieverBlocking()
                val dstH = TARGET_HEIGHT
                val dstW = (dstH * srcAspect).toInt().coerceAtLeast(1)
                val tDec0 = SystemClock.elapsedRealtime()
                val frame = r.getScaledFrameAtTime(
                    positionMs * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    dstW,
                    dstH
                )
                val decodeMs = SystemClock.elapsedRealtime() - tDec0
                Log.i(TAG, "mmr pos=${positionMs}ms decode=${decodeMs}ms " +
                    "sig=${frame?.let { signature(it) } ?: "----"}")
                frame
            }

        /** Cheap 3x3-grid (9-point) pixel signature; distinct frames => distinct hex. */
        private fun signature(bmp: Bitmap): String {
            var acc = 0L
            val w = bmp.width
            val h = bmp.height
            for (gy in 0 until 3) {
                for (gx in 0 until 3) {
                    val x = (w * (gx * 2 + 1) / 6).coerceIn(0, w - 1)
                    val y = (h * (gy * 2 + 1) / 6).coerceIn(0, h - 1)
                    acc = acc * 31 + bmp.getPixel(x, y).toLong()
                }
            }
            return java.lang.Long.toHexString(acc)
        }

        private fun describe(t: Throwable?): String {
            if (t == null) return "null (frame unavailable)"
            val cause = t.cause?.let { " <- ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            return "${t.javaClass.simpleName}: ${t.message}$cause"
        }
    }
}
