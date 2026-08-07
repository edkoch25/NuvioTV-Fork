/*
 * NuvioTV Fork -- MS12 silent-primer trial (nt18, 0.8.2). GPL v3.0, built on
 * the NuvioTV codebase by the NuvioMedia authors and contributors.
 *
 * EXPERIMENT [hypothesis under test]: warm-replay cleanliness survives the
 * nt16 depth falsification only as residual MS12 state, and every recovery
 * works by giving MS12 a fresh track. Feeding ~0.9 s of valid silent TrueHD
 * through a short-lived direct AudioTrack during the AFR settle hold spends
 * one "lock lottery" ticket before the viewer is watching. Asset is FFmpeg-
 * synthesised 5.1 silence (36 KB, dense major syncs); the real content is
 * 7.1 -- whether the parameter mismatch matters is part of what the trial
 * measures. Every step and failure is logged PRIMER_TRACE.
 */
package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log

object TruehdPrimer {
    private const val TAG = "TruehdPrimer"
    private const val ASSET = "primer_silence_51.thd"
    private const val PLAY_HOLD_MS = 900L
    private const val OPEN_BUDGET_MS = 800L

    fun prime(context: Context) {
        val t0 = SystemClock.elapsedRealtime()
        fun elapsed() = SystemClock.elapsedRealtime() - t0
        var track: AudioTrack? = null
        try {
            val bytes = context.assets.open(ASSET).use { it.readBytes() }
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_DOLBY_TRUEHD)
                .setSampleRate(48000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bytes.size)
                .build()
            Log.w(TAG, "PRIMER_TRACE open state=${track.state} elapsedMs=${elapsed()}")
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "PRIMER_TRACE FAIL stage=open state=${track.state}")
                return
            }
            if (elapsed() > OPEN_BUDGET_MS) {
                // The hold budget is finite; a slow open leaves no room to feed.
                Log.w(TAG, "PRIMER_TRACE ABORT stage=open-budget elapsedMs=${elapsed()}")
                return
            }
            track.play()
            var written = 0
            while (written < bytes.size) {
                val w = track.write(bytes, written, bytes.size - written, AudioTrack.WRITE_BLOCKING)
                if (w < 0) {
                    Log.w(TAG, "PRIMER_TRACE FAIL stage=write code=$w after=$written")
                    return
                }
                written += w
            }
            Log.w(TAG, "PRIMER_TRACE fed bytes=$written elapsedMs=${elapsed()}")
            Thread.sleep(PLAY_HOLD_MS)
            Log.w(TAG, "PRIMER_TRACE done elapsedMs=${elapsed()}")
        } catch (e: Exception) {
            Log.w(TAG, "PRIMER_TRACE FAIL stage=exception detail=$e elapsedMs=${elapsed()}")
        } finally {
            try { track?.pause() } catch (_: Exception) {}
            try { track?.flush() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            Log.w(TAG, "PRIMER_TRACE released elapsedMs=${elapsed()}")
        }
    }
}
