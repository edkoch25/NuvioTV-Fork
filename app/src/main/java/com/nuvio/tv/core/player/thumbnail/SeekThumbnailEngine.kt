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
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.transformer.ExperimentalFrameExtractor
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutionException
import kotlin.coroutines.resume

/**
 * T-series Build 3: P3 main-file seek-thumbnail engine.
 *
 * One session per playback; sequential eager pass at 30 s spacing under a playback-first gate;
 * CLOSEST_SYNC seek doctrine (rev5 S2). Zero network / zero video decode at scrub time.
 *
 * fix3: EFE + a Presentation GL effect produced intermittent "Unbalanced enter/exit" GL runtime
 * errors on the AM9 Pro (Amlogic). We extract native-res with NO user effect (the effect-free
 * path the self-test proved stable) and CPU-downscale afterwards.
 *
 * Bitmap ownership (proven from EFE source): getFrame() returns a fresh software ARGB_8888
 * Bitmap, but EFE retains it in lastExtractedFrame and may re-hand the SAME instance for a later
 * seek. So EFE's bitmap is borrowed/read-only: we never recycle it and always return an OWNED
 * copy (createScaledBitmap for >360h, else copy). Scaling runs on Dispatchers.Default so an
 * ~8 MB frame scale never janks the playback thread. fix2 recover-retry + real-error logging
 * remain as a dormant safety net.
 */
object SeekThumbnails {
    private const val TAG = "ThumbWorker"
    const val SPACING_MS = 30_000L
    private const val TARGET_HEIGHT = 360
    private const val GATE_BUFFER_AHEAD_MS = 20_000L
    private const val MAX_CONSECUTIVE_FAILURES = 3

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
        private val cache = ThumbnailCache(appContext, titleKey, durationMs)
        private var extractor: ExperimentalFrameExtractor? = null
        private var workerJob: Job? = null
        private var released = false
        private val diskLoadsInFlight = HashSet<Long>()

        fun start() {
            workerJob = scope.launch {
                val lastBucket = (durationMs - 1) / SPACING_MS
                var generated = 0
                var failures = 0
                Log.i(TAG, "session start: buckets=0..$lastBucket spacing=${SPACING_MS}ms (native+cpu-downscale)")
                try {
                    for (bucket in 0..lastBucket) {
                        if (cache.hasDisk(bucket)) continue
                        awaitGate()
                        val positionMs = (bucket * SPACING_MS).coerceAtMost(durationMs - 1)
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
                        }
                    }
                    Log.i(TAG, "session pass complete: generated=$generated")
                } finally {
                    releaseExtractor()
                }
            }
        }

        fun stop() {
            workerJob?.cancel()
            workerJob = null
            releaseExtractor()
        }

        fun thumbFor(positionMs: Long): Bitmap? {
            val lastBucket = (durationMs - 1) / SPACING_MS
            val bucket = (positionMs / SPACING_MS).coerceIn(0, lastBucket)
            cache.getMem(bucket)?.let { return it }
            if (cache.hasDisk(bucket) && diskLoadsInFlight.add(bucket)) {
                scope.launch {
                    val bmp = withContext(Dispatchers.IO) { cache.readDisk(bucket) }
                    diskLoadsInFlight.remove(bucket)
                    if (bmp != null) {
                        cache.putMem(bucket, bmp)
                        tick.intValue++
                    }
                }
            }
            return null
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

        /** One extraction; recreate the extractor once on failure and retry (EFE poisons itself on error). */
        private suspend fun extractWithRecovery(positionMs: Long, bucket: Long): Bitmap? {
            val first = runCatching { extractOnce(positionMs) }
            first.getOrNull()?.let { return it }
            Log.w(TAG, "bucket=$bucket attempt1: ${describe(first.exceptionOrNull())}")
            recreateExtractor()
            val second = runCatching { extractOnce(positionMs) }
            second.getOrNull()?.let { return it }
            Log.w(TAG, "bucket=$bucket attempt2: ${describe(second.exceptionOrNull())}")
            return null
        }

        private fun ensureExtractor(): ExperimentalFrameExtractor {
            extractor?.let { return it }
            val e = ExperimentalFrameExtractor(
                appContext,
                ExperimentalFrameExtractor.Configuration.Builder()
                    // rev5 S2 binding doctrine: nearest keyframe, never decode-to-exact.
                    .setSeekParameters(SeekParameters.CLOSEST_SYNC)
                    .build()
            )
            // No GL effect: native-res extraction (the effect-free path the self-test proved stable),
            // CPU downscale afterwards. Avoids the Presentation-shader "Unbalanced enter/exit" race.
            e.setMediaItem(MediaItem.fromUri(url), emptyList<Effect>())
            extractor = e
            return e
        }

        private suspend fun extractOnce(positionMs: Long): Bitmap? {
            val e = ensureExtractor()
            val future = e.getFrame(positionMs)
            val full = suspendCancellableCoroutine { cont ->
                future.addListener({
                    try {
                        cont.resume(future.get().bitmap)
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWith(Result.failure(t))
                    }
                }, MoreExecutors.directExecutor())
                cont.invokeOnCancellation { future.cancel(true) }
            } ?: return null
            // Scale off the playback thread; returns an owned copy (EFE's bitmap is never recycled).
            return withContext(Dispatchers.Default) { ownedDownscale(full) }
        }

        /**
         * Returns an OWNED copy of [src] at <=TARGET_HEIGHT. [src] is EFE's borrowed frame bitmap:
         * never recycled, never handed to the cache directly.
         */
        private fun ownedDownscale(src: Bitmap): Bitmap {
            if (src.height <= TARGET_HEIGHT) {
                return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
            }
            val w = (src.width.toFloat() * TARGET_HEIGHT / src.height).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(src, w, TARGET_HEIGHT, true)
        }

        /** Release the current extractor without ending the session (mid-pass recovery). */
        private fun recreateExtractor() {
            runCatching { extractor?.release() }
            extractor = null
        }

        private fun releaseExtractor() {
            if (released) return
            released = true
            runCatching { extractor?.release() }
            extractor = null
        }

        private fun describe(t: Throwable?): String {
            if (t == null) return "null"
            val root = if (t is ExecutionException && t.cause != null) t.cause!! else t
            val code = (root as? PlaybackException)?.errorCodeName ?: ""
            val cause = root.cause?.let { " <- ${it.javaClass.simpleName}: ${it.message}" } ?: ""
            val codeStr = if (code.isNotEmpty()) "($code)" else ""
            return "${root.javaClass.simpleName}$codeStr: ${root.message}$cause"
        }
    }
}
