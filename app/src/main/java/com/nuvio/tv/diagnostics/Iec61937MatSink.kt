/*
 *  §9.5 MAT workstream - Commit B1: the IEC61937 output sink (nt51 rework).
 *
 *  Owns an ENCODING_IEC61937 192 kHz / 8-channel / 16-bit AudioTrack and writes
 *  IEC61937-framed MAT bursts (produced upstream: MatPacker output with the preamble
 *  stamped in) into it. The AudioTrack buffer is the real-time pacer.
 *
 *  nt51 changes (verified against media3 1.8.0 DefaultAudioSink and Kodi AESinkAUDIOTRACK):
 *   - Writes are NON-BLOCKING (write(ByteBuffer, size, WRITE_NON_BLOCKING)), exactly as
 *     DefaultAudioSink does for every format. The caller manages partial writes via the
 *     ByteBuffer position and applies back-pressure. A blocking write here starved
 *     ExoPlayer's single playback thread (the video renderer shares it) - the stutter root.
 *   - The track is created PAUSED (Kodi does pause()+flush() at create; DefaultAudioSink
 *     never plays at configure). It starts only on play(), by which point frames are queued -
 *     continuous bursts from the first sample instead of an empty-track underrun.
 *   - A start threshold defers output until a stable initial burst is buffered.
 *
 *  Preamble bytes source-verified (FFmpeg spdif.h/spdifenc.c) AND empirically proven on the
 *  Homatics in Gate 0 nt41 (LE TrueHD cell). No payload byte-swap on LE ARM.
 *
 *  NOTE: playbackHeadPosition is a 32-bit frame counter; at 192 kHz it wraps after ~3.1 h.
 */
package com.nuvio.tv.diagnostics

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.nio.ByteBuffer

class Iec61937MatSink {

    private var track: AudioTrack? = null
    // Total bytes written since open/reopen; / IEC_FRAME_BYTES gives carrier frames written.
    private var bytesWritten: Long = 0L

    /** True once an AudioTrack is constructed (created paused, ready for writes). */
    val isOpen: Boolean get() = track?.state == AudioTrack.STATE_INITIALIZED

    /**
     * Construct the IEC61937 AudioTrack, created PAUSED. Returns false if the framework
     * rejects the configuration (caller should fall back to the normal sink path).
     */
    fun open(): Boolean {
        close()
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_IEC61937)
                .setSampleRate(IEC_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(
                IEC_RATE_HZ, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.ENCODING_IEC61937
            )
            if (minBuf <= 0) {
                Log.w(TAG, "open: getMinBufferSize=$minBuf, config rejected")
                return false
            }
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
            // ~1 s buffer (nt50); fall back to the old small size if the HAL rejects it.
            val bigBuf = maxOf(minBuf, MatPacker.MAT_BUFFER_SIZE * BUF_MAT_FRAMES_LARGE)
            val smallBuf = maxOf(minBuf, MatPacker.MAT_BUFFER_SIZE * 4)
            var built = buildTrack(format, attrs, bigBuf)
            var reqBuf = bigBuf
            if (built == null) {
                Log.w(TAG, "open: large buffer $bigBuf rejected; retrying with $smallBuf")
                built = buildTrack(format, attrs, smallBuf)
                reqBuf = smallBuf
            }
            val newTrack = built ?: run {
                Log.w(TAG, "open: AudioTrack not INITIALIZED on either buffer size")
                return false
            }
            // nt51: begin output only once a stable initial burst is buffered.
            var thresh = -1
            try { thresh = newTrack.setStartThresholdInFrames(START_THRESHOLD_FRAMES) } catch (_: Throwable) {}
            // nt51: create PAUSED (Kodi-style pause+flush); play() starts it once frames are queued.
            try { newTrack.pause(); newTrack.flush() } catch (_: Throwable) {}
            track = newTrack
            bytesWritten = 0L
            Log.i(TAG, "open: IEC61937 192k/8ch created PAUSED, reqBuf=$reqBuf" +
                " actualFrames=${newTrack.bufferSizeInFrames} startThresh=$thresh")
            true
        } catch (e: Throwable) {
            Log.w(TAG, "open threw ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun buildTrack(format: AudioFormat, attrs: AudioAttributes, bufBytes: Int): AudioTrack? {
        return try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (t.state == AudioTrack.STATE_INITIALIZED) {
                t
            } else {
                Log.w(TAG, "buildTrack: not INITIALIZED (state=${t.state}, buf=$bufBytes)")
                t.release()
                null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "buildTrack threw ${e.javaClass.simpleName} (buf=$bufBytes)")
            null
        }
    }

    /**
     * Write from [buffer] (position..limit) to the AudioTrack, NON-BLOCKING. Returns bytes
     * written (advancing the buffer position), 0 if the track buffer is full, or a negative
     * AudioTrack error / -1 if not open. Caller re-offers a partially-written buffer.
     */
    fun writeNonBlocking(buffer: ByteBuffer): Int {
        val t = track ?: return -1
        return try {
            val n = t.write(buffer, buffer.remaining(), AudioTrack.WRITE_NON_BLOCKING)
            if (n > 0) bytesWritten += n
            n
        } catch (e: Throwable) {
            Log.w(TAG, "writeNonBlocking threw ${e.javaClass.simpleName}")
            -1
        }
    }

    /** Playback head in carrier frames (192 kHz), or -1 if not open. */
    fun playbackHeadPosition(): Int = try { track?.playbackHeadPosition ?: -1 } catch (_: Throwable) { -1 }

    /** Total bytes written since open/reopen. */
    fun bytesWritten(): Long = bytesWritten

    /** AudioTrack play state (PLAYSTATE_*), or -1. */
    fun playState(): Int = try { track?.playState ?: -1 } catch (_: Throwable) { -1 }

    /** Native AudioTrack underrun count (API 24+), or -1. */
    fun underrunCount(): Int = try { track?.underrunCount ?: -1 } catch (_: Throwable) { -1 }

    /** Actual allocated buffer size in frames, or 0. */
    fun bufferSizeFrames(): Int = try { track?.bufferSizeInFrames ?: 0 } catch (_: Throwable) { 0 }

    fun pause() { try { track?.pause() } catch (_: Throwable) {} }

    /** Start or resume playback (also the resume-after-pause path). */
    fun play() { try { track?.play() } catch (_: Throwable) {} }

    fun close() {
        val t = track ?: return
        try { t.pause(); t.flush(); t.stop() } catch (_: Throwable) {}
        try { t.release() } catch (_: Throwable) {}
        track = null
        bytesWritten = 0L
    }

    companion object {
        private const val TAG = "Iec61937MatSink"
        private const val IEC_RATE_HZ = 192_000
        // 8-channel 16-bit IEC61937 frame = 16 bytes; ~1 s buffer = 50 MAT frames.
        const val IEC_FRAME_BYTES = 16
        private const val BUF_MAT_FRAMES_LARGE = 50
        // Defer output until ~8 MAT frames (8 * 3840 = 30720 carrier frames ~= 160 ms) buffered.
        private const val START_THRESHOLD_FRAMES = 30720

        // Empirically-validated IEC61937 preamble for MAT-wrapped TrueHD (Gate 0 nt41):
        // Pa=0xF872, Pb=0x4E1F, Pc=IEC61937_TRUEHD (0x16), Pd=MAT_FRAME_SIZE (61424 BYTES).
        private const val PA = 0xF872
        private const val PB = 0x4E1F
        private const val PC_TRUEHD = 0x0016
        private const val PD_TRUEHD = 61424

        /**
         * Pure function: overwrite the first 8 bytes of a MAT frame with the IEC61937
         * preamble, little-endian. MatPacker leaves those bytes zeroed for this purpose.
         */
        fun writeIecPreamble(frame: ByteArray) {
            putLe16(frame, 0, PA)
            putLe16(frame, 2, PB)
            putLe16(frame, 4, PC_TRUEHD)
            putLe16(frame, 6, PD_TRUEHD)
        }

        private fun putLe16(b: ByteArray, off: Int, word: Int) {
            b[off] = (word and 0xFF).toByte()
            b[off + 1] = ((word ushr 8) and 0xFF).toByte()
        }
    }
}
