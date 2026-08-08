/*
 *  §9.5 MAT workstream - Commit B2b: the routing AudioSink wrapper (nt51 rework).
 *
 *  Extends ForwardingAudioSink (delegates every AudioSink method by default) and overrides
 *  only the methods that must differ when routing TrueHD through the app-side MAT path.
 *  Non-TrueHD playback and the toggle-off case are byte-for-byte the delegate's behaviour.
 *
 *  When active (toggle on, input is TrueHD, IEC61937 sink opens):
 *    handleBuffer bytes -> TrueHdAuFramer -> MatPacker -> preamble stamp
 *      -> ByteBuffer queue -> Iec61937MatSink (NON-BLOCKING writes)
 *
 *  nt51 contract fixes (verified against media3 1.8.0 DefaultAudioSink / AudioSink javadoc,
 *  and Kodi AESinkAUDIOTRACK):
 *   - NON-BLOCKING writes + a pending-frame queue. handleBuffer drains the queue first; if
 *     frames remain it returns false WITHOUT advancing the input buffer (AudioSink contract:
 *     the same ByteBuffer is re-offered until fully consumed), so the shared ExoPlayer
 *     playback thread never blocks in AudioTrack.write and the video renderer is not starved.
 *     The AudioTrack buffer is the real-time pacer.
 *   - The sink's track is created PAUSED and started on play(); flush() (seek) CLOSES and
 *     REOPENS it (DefaultAudioSink recreates on flush; head-across-flush is device-murky and
 *     our position is head-anchored) and restores the play state.
 *   - setVolume is a no-op in MAT mode (gain on an IEC61937 bitstream corrupts it).
 *
 *  KNOWN v1 LIMITATIONS (safe for single-stream movie playback):
 *   - getFormatSupport is delegated (the box reports ENCODING_DOLBY_TRUEHD as passthrough-
 *     supported, so the renderer hands us the encoded bitstream).
 *   - No IEC pause-bursts are emitted on a genuine rebuffer; the receiver may re-hunt after a
 *     real network stall (Kodi's Android sink does not send them either).
 *   - onPositionDiscontinuity is not raised on a PTS jump (MatPacker handles timing).
 */
package com.nuvio.tv.diagnostics

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.util.ArrayDeque

class MatRoutingAudioSink(
    private val delegate: AudioSink,
    private val matEnabled: Boolean
) : ForwardingAudioSink(delegate) {

    private val framer = TrueHdAuFramer()
    private var packer = MatPacker()
    private val matSink = Iec61937MatSink()

    // MAT frames stamped and awaiting write to the AudioTrack (our real-time buffer).
    private val pendingFrames = ArrayDeque<ByteBuffer>()

    @Volatile
    private var matMode = false
    private var isPlaying = false

    // Position anchoring: PTS of the first buffer handled since the last flush.
    private var startPtsUs = C.TIME_UNSET

    // nt31 observability: windowed average of consumed TrueHD input bytes, read by
    // the HUD's A-bitrate row while MAT is active. Null until the first ~2 s window.
    private var rateWindowStartMs = 0L
    private var rateWindowBytes = 0L
    @Volatile
    private var lastInputRateBps = -1L
    private var playToEndCalled = false

    // nt48/nt51 diagnostics (throttled ~1/sec; strip once MAT is proven).
    private var dbgLastLogMs = 0L
    private var dbgAusTotal = 0L
    private var dbgFramesEnq = 0L
    private var dbgFramesDrained = 0L
    private var dbgBytesWrittenTotal = 0L
    private var dbgWriteErrors = 0L
    private var dbgLastWrite = 0
    private var dbgCallsSinceLog = 0

    // --- configuration / capability -------------------------------------------------

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        val isTrueHd = inputFormat.sampleMimeType == MimeTypes.AUDIO_TRUEHD
        val wantMat = matEnabled && isTrueHd

        val newMatMode = if (wantMat) {
            if (matSink.isOpen || matSink.open()) {
                true
            } else {
                Log.w(TAG, "configure: MAT wanted but IEC61937 sink would not open; falling back")
                false
            }
        } else {
            false
        }

        if (matMode && !newMatMode) {
            matSink.close()
        }
        if (!matMode && newMatMode) {
            try { super.flush() } catch (_: Throwable) {}
        }

        matMode = newMatMode

        if (matMode) {
            framer.reset()
            packer = MatPacker()
            pendingFrames.clear()
            startPtsUs = C.TIME_UNSET
            playToEndCalled = false
            resetDiag()
            resetInputRate()
            // A reconfigure may arrive on an already-playing sink; restore play state.
            if (isPlaying) matSink.play()
            Log.i(TAG, "configure: MAT mode ON  mime=${inputFormat.sampleMimeType}" +
                " pcmEnc=${inputFormat.pcmEncoding} rate=${inputFormat.sampleRate}" +
                " ch=${inputFormat.channelCount}")
        } else {
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
        }
    }

    // --- data path -------------------------------------------------------------------

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!matMode) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (startPtsUs == C.TIME_UNSET) {
            startPtsUs = presentationTimeUs
        }

        // 1. Drain any backlog first. If frames remain, apply back-pressure: return false
        //    WITHOUT advancing the input buffer (AudioSink contract - media3 re-offers the
        //    same buffer). This never blocks the shared playback thread.
        drainPending()
        if (pendingFrames.isNotEmpty()) {
            maybeLogDiag(buffer.remaining(), backlogged = true)
            return false
        }

        // 2. Queue empty - consume the input into whole TrueHD AUs, pack, stamp, enqueue.
        val len = buffer.remaining()
        val arr: ByteArray
        val off: Int
        if (buffer.hasArray()) {
            arr = buffer.array()
            off = buffer.arrayOffset() + buffer.position()
        } else {
            arr = ByteArray(len)
            val mark = buffer.position()
            buffer.get(arr)
            buffer.position(mark)
            off = 0
        }

        val ausThisCall = framer.feed(arr, off, len) { au ->
            if (packer.packTrueHD(au, au.size)) {
                var frame = packer.getOutputFrame()
                while (frame != null) {
                    // Kodi parity (nt30): payload words to wire byte order, THEN the
                    // LE preamble stamp over bytes 0..7.
                    Iec61937MatSink.swapPayloadWords(frame)
                    Iec61937MatSink.writeIecPreamble(frame)
                    pendingFrames.addLast(ByteBuffer.wrap(frame))
                    dbgFramesEnq += 1
                    frame = packer.getOutputFrame()
                }
            }
            0
        }
        dbgAusTotal += ausThisCall

        // 3. Try to drain what we just enqueued (best effort; leftovers ride the next call).
        drainPending()

        // 4. Input fully consumed.
        buffer.position(buffer.position() + len)
        val rateNowMs = android.os.SystemClock.elapsedRealtime()
        if (rateWindowStartMs == 0L) rateWindowStartMs = rateNowMs
        rateWindowBytes += len
        val rateWinMs = rateNowMs - rateWindowStartMs
        if (rateWinMs >= 2000L) {
            lastInputRateBps = rateWindowBytes * 8000L / rateWinMs
            rateWindowStartMs = rateNowMs
            rateWindowBytes = 0L
        }
        maybeLogDiag(len, backlogged = false)
        return true
    }

    /** True while TrueHD is actively routed through the app-side MAT path. */
    fun isMatActive(): Boolean = matMode

    /** Windowed input bitrate while MAT is active, or null before the first window. */
    fun measuredInputBitrateBps(): Long? = lastInputRateBps.takeIf { it > 0L }

    private fun resetInputRate() {
        rateWindowStartMs = 0L
        rateWindowBytes = 0L
        lastInputRateBps = -1L
    }

    /** Drain as many queued frames as the AudioTrack will accept, NON-BLOCKING. */
    private fun drainPending() {
        while (pendingFrames.isNotEmpty()) {
            val head = pendingFrames.peekFirst()
            val n = matSink.writeNonBlocking(head)
            dbgLastWrite = n
            if (n < 0) {
                // Write error: drop this frame so we don't spin, count it.
                pendingFrames.pollFirst()
                dbgWriteErrors += 1
                continue
            }
            if (n > 0) dbgBytesWrittenTotal += n
            if (!head.hasRemaining()) {
                pendingFrames.pollFirst()
                dbgFramesDrained += 1
            } else {
                // Partial write - the AudioTrack buffer is full. Back-pressure.
                break
            }
        }
    }

    override fun playToEndOfStream() {
        if (!matMode) {
            super.playToEndOfStream()
            return
        }
        // Called repeatedly at EOS by the renderer; each call drains more as the track plays out.
        drainPending()
        playToEndCalled = true
    }

    override fun isEnded(): Boolean {
        if (!matMode) return super.isEnded()
        return playToEndCalled && !hasPendingMatData()
    }

    override fun hasPendingData(): Boolean {
        if (!matMode) return super.hasPendingData()
        return hasPendingMatData()
    }

    private fun hasPendingMatData(): Boolean {
        if (pendingFrames.isNotEmpty()) return true
        val writtenFrames = matSink.bytesWritten() / Iec61937MatSink.IEC_FRAME_BYTES
        if (writtenFrames == 0L) return false
        val head = matSink.playbackHeadPosition()
        if (head < 0) return false
        return head.toLong() < writtenFrames
    }

    override fun handleDiscontinuity() {
        if (!matMode) {
            super.handleDiscontinuity()
            return
        }
        // MatPacker detects seamless-branch/timing discontinuities from the AU headers.
    }

    // --- transport / position --------------------------------------------------------

    override fun play() {
        // media3 contract: play() may arrive BEFORE configure() (a renderer enabled on
        // an already-playing player starts first, then configures - observed on-device
        // via the AFR quiesce/re-enable cycle, 8 Aug capture 2). DefaultAudioSink
        // latches a playing flag unconditionally and starts its track later; mirror
        // that: latch isPlaying regardless of matMode so the configure()/flush()
        // restore paths can start the MAT track, and always forward to the delegate so
        // its own play-state machine stays truthful for a later MAT disengage.
        isPlaying = true
        super.play()
        if (matMode) matSink.play()
    }

    override fun pause() {
        isPlaying = false
        super.pause()
        if (matMode) matSink.pause()
    }

    override fun flush() {
        if (matMode) {
            // Head-across-flush is device-murky and our position is head-anchored, so recreate
            // the track (as DefaultAudioSink does) rather than flush in place; restore play state.
            matSink.close()
            matSink.open()
            if (isPlaying) matSink.play()
            pendingFrames.clear()
            framer.reset()
            packer = MatPacker()
            startPtsUs = C.TIME_UNSET
            playToEndCalled = false
            resetDiag()
            resetInputRate()
        } else {
            super.flush()
        }
    }

    override fun reset() {
        matSink.close()
        pendingFrames.clear()
        framer.reset()
        packer = MatPacker()
        matMode = false
        isPlaying = false
        startPtsUs = C.TIME_UNSET
        playToEndCalled = false
        resetInputRate()
        super.reset()
    }

    override fun release() {
        matSink.close()
        pendingFrames.clear()
        super.release()
    }

    override fun setVolume(volume: Float) {
        if (matMode) {
            // Gain on an IEC61937 bitstream corrupts it; volume is a no-op in MAT mode.
            if (volume != 1.0f) Log.i(TAG, "setVolume($volume) ignored in MAT mode")
        } else {
            super.setVolume(volume)
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!matMode) return super.getCurrentPositionUs(sourceEnded)
        if (startPtsUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET
        val head = matSink.playbackHeadPosition()
        if (head < 0) return AudioSink.CURRENT_POSITION_NOT_SET
        val playedUs = head.toLong() * 1_000_000L / CARRIER_FRAMES_PER_SECOND
        return startPtsUs + playedUs
    }

    override fun getAudioTrackBufferSizeUs(): Long {
        if (!matMode) return super.getAudioTrackBufferSizeUs()
        val frames = matSink.bufferSizeFrames()
        if (frames <= 0) return DEFAULT_BUFFER_US
        return frames.toLong() * 1_000_000L / CARRIER_FRAMES_PER_SECOND
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        // MAT is bitstream passthrough: only 1.0 speed is achievable. Forward so the delegate
        // stores the value (harmless while dormant); the MAT sink always plays at native rate.
        super.setPlaybackParameters(playbackParameters)
    }

    override fun enableTunnelingV21() {
        if (!matMode) super.enableTunnelingV21()
    }

    // --- diagnostics -----------------------------------------------------------------

    private fun resetDiag() {
        dbgLastLogMs = 0L; dbgAusTotal = 0L; dbgFramesEnq = 0L; dbgFramesDrained = 0L
        dbgBytesWrittenTotal = 0L; dbgWriteErrors = 0L; dbgLastWrite = 0; dbgCallsSinceLog = 0
    }

    private fun maybeLogDiag(len: Int, backlogged: Boolean) {
        dbgCallsSinceLog += 1
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (nowMs - dbgLastLogMs < 1000L) return
        dbgLastLogMs = nowMs
        Log.i(TAG, "mat-diag: calls=$dbgCallsSinceLog len=$len backlog=$backlogged" +
            " pending=${pendingFrames.size} head=${matSink.playbackHeadPosition()}" +
            " playState=${matSink.playState()} underruns=${matSink.underrunCount()}" +
            " AUtot=$dbgAusTotal enq=$dbgFramesEnq drained=$dbgFramesDrained" +
            " wrtot=$dbgBytesWrittenTotal wrErr=$dbgWriteErrors lastWrite=$dbgLastWrite")
        dbgCallsSinceLog = 0
    }

    companion object {
        private const val TAG = "MatRoutingAudioSink"
        // 192000 carrier frames == 1 s of media (50 MAT frames/s * 3840 carrier frames each).
        private const val CARRIER_FRAMES_PER_SECOND = 192_000L
        private const val DEFAULT_BUFFER_US = 250_000L
    }
}
