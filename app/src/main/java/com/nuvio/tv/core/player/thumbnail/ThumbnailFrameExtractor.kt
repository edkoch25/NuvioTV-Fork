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
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.transformer.ExperimentalFrameExtractor
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Build 2 runtime gate for the vendored [ExperimentalFrameExtractor].
 *
 * Not wired into any feature. A debug-only one-shot that proves EFE's GL/decode pipeline
 * executes against the vendored lib-common at runtime and yields a [Bitmap]. The outcome is
 * logged under [TAG]. Delete once the T-series runtime gate is recorded closed.
 */
object ThumbnailFrameExtractor {
    const val TAG = "ThumbSelfTest"

    suspend fun selfTest(context: Context, url: String, positionMs: Long) {
        val started = SystemClock.elapsedRealtime()
        val host = runCatching { java.net.URI(url).host }.getOrNull()
        Log.i(TAG, "start host=$host pos=${positionMs}ms")
        val result = runCatching { extractFrame(context, url, positionMs) }
        val ms = SystemClock.elapsedRealtime() - started
        result.onSuccess { bmp ->
            if (bmp != null) Log.i(TAG, "PASS ${bmp.width}x${bmp.height} in ${ms}ms")
            else Log.w(TAG, "NULL frame in ${ms}ms (pipeline ran, no bitmap returned)")
        }.onFailure { t ->
            Log.e(TAG, "FAIL in ${ms}ms: ${t.javaClass.simpleName}: ${t.message}", t)
        }
    }

    // Runs on the caller's thread (the LaunchedEffect main dispatcher). EFE builds an internal
    // ExoPlayer that wants a Looper thread, and getFrame() is future-based so nothing blocks.
    private suspend fun extractFrame(context: Context, url: String, positionMs: Long): Bitmap? {
        val extractor = ExperimentalFrameExtractor(
            context.applicationContext,
            ExperimentalFrameExtractor.Configuration.Builder().build()
        )
        return try {
            extractor.setMediaItem(MediaItem.fromUri(url), emptyList<Effect>())
            val future = extractor.getFrame(positionMs)
            suspendCancellableCoroutine { cont ->
                future.addListener({
                    try {
                        cont.resume(future.get().bitmap)
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWith(Result.failure(t))
                    }
                }, MoreExecutors.directExecutor())
                cont.invokeOnCancellation { future.cancel(true) }
            }
        } finally {
            extractor.release()
        }
    }
}
