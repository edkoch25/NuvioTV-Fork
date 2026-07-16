package com.nuvio.tv.ui.screens.player

import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.lang.reflect.Field
import java.nio.ByteBuffer
import kotlin.math.abs

/** Presentation-time span the measured audio bitrate needs before it is worth showing. */
private const val MEASURED_AUDIO_MIN_SPAN_US = 5_000_000L

// Audio-clock jitter sampling. Sampled on the playback thread from handleBuffer, rate
// limited so the cost is one position read every ~20 ms rather than one per audio buffer.
private const val JITTER_MIN_INTERVAL_MS = 20L
// A window wider than this spans a pause or a stall, not a jitter event.
private const val JITTER_MAX_WINDOW_MS = 250L
// A deviation this large is a seek or a discontinuity, not clock jitter.
private const val JITTER_MAX_PLAUSIBLE_MS = 500L
// Deviation at or above this counts as an event, not just noise.
private const val JITTER_EVENT_MS = 20L
// Below this many samples the row says nothing rather than something unstable.
private const val JITTER_MIN_SAMPLES = 25

internal class PlaybackSpeedAwareAudioSink(
    private val delegate: AudioSink,
    initialForcePcm: Boolean = false,
    /**
     * Audio review F2: when Force AC-3 Transcoding is enabled, claim AC-3
     * support here regardless of what the HAL reports. The previous approach -
     * Builder.setAudioCapabilities(...) - is silently discarded whenever the
     * builder has a Context (the sink installs live AudioCapabilitiesReceiver
     * capabilities on first configure), so the "force" never reached the sink
     * and the toggle only worked on HALs that already reported AC-3. Claiming
     * support at the wrapper survives the dynamic-capabilities design. Scoped
     * to AC-3 <= 5.1 only: that is what S/PDIF can carry.
     */
    private val forceAc3Support: Boolean = false
) : ForwardingAudioSink(delegate) {

    private val startedWithForcedPcm: Boolean = initialForcePcm

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = initialForcePcm

    @Volatile
    private var currentInputFormat: Format? = null

    @Volatile
    private var listener: AudioSink.Listener? = null

    /**
     * Whether the current audio format is playing in passthrough mode (bitstream direct to
     * HDMI receiver). When true, pause/resume requires special handling because the receiver
     * has its own internal buffer that continues draining after Android's AudioTrack is paused.
     */
    // Read from the audio thread (handleBuffer) and the main thread (the HUD's
    // measuredAudioBitrateBps) as well as written from the playback thread (configure).
    @Volatile
    private var isCurrentlyPassthrough: Boolean = false

    /**
     * Set to true when pause() is called during passthrough playback.
     * On the next play() call, we force a media time resync to compensate for
     * audio the HDMI receiver played from its internal buffer during the pause.
     */
    @Volatile
    private var passthroughPauseCompensationPending: Boolean = false

    /**
     * Set to true when passthrough mode is configured initially.
     * On the first play() call, we force a media time resync to ensure
     * immediate hardware clock alignment for tunneled passthrough audio.
     */
    @Volatile
    private var passthroughStartupCompensationPending: Boolean = false

    fun setInitialPlaybackSpeed(speed: Float) {
        playbackSpeed = normalizeSpeed(speed)
        markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        currentInputFormat = inputFormat
        resetAudioMeasurements()
        markPcmFallbackIfNeeded(inputFormat, playbackSpeed)
        // Detect if this format will play in passthrough mode (bitstream, not forced to PCM)
        val wasPassthrough = isCurrentlyPassthrough
        isCurrentlyPassthrough = isBitstreamFormat(inputFormat) && !shouldRejectDirectPlayback(inputFormat)
        if (isCurrentlyPassthrough && !wasPassthrough) {
            passthroughStartupCompensationPending = true
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun flush() {
        passthroughPauseCompensationPending = false
        passthroughStartupCompensationPending = false
        resetAudioMeasurements()
        // nt: AudioTrack reuse-on-flush disabled. Reusing the passthrough
        // AudioTrack across a seek left the audio clock mismapped on some HALs
        // (e.g. Ugoos SK1), causing progressive A/V desync after skipping.
        // Fall through to release/recreate, matching upstream flush behaviour.
        super.flush()
    }

    // Measured bitrate of the encoded audio bitstream. MKV declares no bitrate for audio
    // tracks and TrueHD / DTS-HD MA are genuinely variable-rate, so counting the bytes on
    // their way out is the only way to put a number on a lossless track. Gated on
    // passthrough: there the sink is handed the encoded bitstream, so this measures the
    // track itself. Under PCM it would measure the decoded output — a property of the sink,
    // not of the source — which is a different number wearing the same label.
    @Volatile
    private var encodedAudioBytes: Long = 0L

    @Volatile
    private var encodedAudioFirstPtsUs: Long = C.TIME_UNSET

    @Volatile
    private var encodedAudioLastPtsUs: Long = C.TIME_UNSET

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (!isCurrentlyPassthrough) {
            // Jitter is sampled under PCM too: the audio clock still drives the video
            // renderer, and a PCM-forced stream that still stutters is diagnostic.
            val handledPcm = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            maybeSampleAudioClockJitter()
            return handledPcm
        }
        // The sink consumes from the caller's buffer and may take only part of it, asking
        // to be called again with the remainder — so count the position delta, not the
        // buffer's size, or a partially consumed buffer is counted twice.
        val remainingBefore = buffer.remaining()
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val consumed = remainingBefore - buffer.remaining()
        if (consumed > 0) {
            encodedAudioBytes += consumed.toLong()
            if (encodedAudioFirstPtsUs == C.TIME_UNSET) {
                encodedAudioFirstPtsUs = presentationTimeUs
            }
            encodedAudioLastPtsUs = presentationTimeUs
        }
        maybeSampleAudioClockJitter()
        return handled
    }

    private fun resetAudioMeasurements() {
        encodedAudioBytes = 0L
        encodedAudioFirstPtsUs = C.TIME_UNSET
        encodedAudioLastPtsUs = C.TIME_UNSET
        jitterLastPosUs = C.TIME_UNSET
        jitterLastWallMs = 0L
        jitterSamples = 0
        jitterEvents = 0
        jitterMaxAbsMs = 0L
        jitterSumAbsMs = 0L
        driftWallAccumMs = 0L
        driftPosAccumMs = 0L
        driftExpectedAccumMs = 0L
        driftWindows = 0
        driftLastMs = 0L
        driftMaxAbsMs = 0L
        driftSumAbsMs = 0L
    }

    /**
     * Bitrate of the encoded audio bitstream measured over the presentation time it spans,
     * or null when the sink is not in passthrough or has not yet seen enough audio.
     */
    fun measuredAudioBitrateBps(): Long? {
        if (!isCurrentlyPassthrough) return null
        val firstUs = encodedAudioFirstPtsUs
        val lastUs = encodedAudioLastPtsUs
        val bytes = encodedAudioBytes
        if (firstUs == C.TIME_UNSET || lastUs == C.TIME_UNSET || bytes <= 0L) return null
        val spanUs = lastUs - firstUs
        if (spanUs < MEASURED_AUDIO_MIN_SPAN_US) return null
        return bytes * 8_000_000L / spanUs
    }

    /** One audio-clock jitter window: how far reported audio position drifts from wall clock. */
    data class AudioClockJitter(
        val samples: Int,
        val events: Int,
        val maxAbsMs: Long,
        val meanAbsMs: Long,
        val driftWindows: Int,
        val driftLastMs: Long,
        val driftMaxAbsMs: Long,
        val driftMeanAbsMs: Long
    )

    // nt35: the sensor only measures while the player is actually playing. During a
    // pause or rebuffer the renderer keeps feeding the sink, so sampling continued at
    // 20 ms while position legitimately froze - and every one of those samples scored
    // abs ~= wall_d >= JITTER_EVENT_MS, one false event per sample. 97% of the clean-file
    // events in the nt34 diagnostic capture fell inside rebuffer windows. The controller
    // plumbs Player.Listener.onIsPlayingChanged here; any transition resets the window
    // so no sample ever spans a gap.
    @Volatile
    private var playbackActive: Boolean = false

    fun setPlaybackActive(active: Boolean) {
        playbackActive = active
        jitterLastPosUs = C.TIME_UNSET
        jitterLastWallMs = 0L
        driftWallAccumMs = 0L
        driftPosAccumMs = 0L
        driftExpectedAccumMs = 0L
    }

    @Volatile
    private var jitterLastPosUs: Long = C.TIME_UNSET

    @Volatile
    private var jitterLastWallMs: Long = 0L

    @Volatile
    private var jitterSamples: Int = 0

    @Volatile
    private var jitterEvents: Int = 0

    @Volatile
    private var jitterMaxAbsMs: Long = 0L

    @Volatile
    private var jitterSumAbsMs: Long = 0L

    // nt35: 1 s-window drift - the headline health metric. Accumulates position advance
    // vs expected (speed-scaled wall) advance over contiguous active samples; each time
    // a window's wall time reaches 1 s the difference is one drift reading. Both sums
    // cover identical samples, so the reading is unbiased by window boundaries. On the
    // nt34 clean capture |drift| p90 was ~23 ms/s; a failing HAL clock shows as
    // sustained divergence here where per-sample noise cannot.
    @Volatile
    private var driftWallAccumMs: Long = 0L

    @Volatile
    private var driftPosAccumMs: Long = 0L

    @Volatile
    private var driftExpectedAccumMs: Long = 0L

    @Volatile
    private var driftWindows: Int = 0

    @Volatile
    private var driftLastMs: Long = 0L

    @Volatile
    private var driftMaxAbsMs: Long = 0L

    @Volatile
    private var driftSumAbsMs: Long = 0L

    /**
     * Compares the sink's reported audio position against wall clock. Under passthrough the
     * audio clock is the master clock, so a clock that jumps drags the video renderer into
     * bulk frame drops and resyncs — the visible skips. A vendor HAL failing to pack its
     * output (Amlogic MS12's TrueHD MAT packer on a thin stream) jitters the reported
     * position by tens of milliseconds while every app-level counter stays clean, so this is
     * the sensor for that entire failure class.
     *
     * Runs on the playback thread (handleBuffer's caller), which is where the delegate's
     * position is safe to read; rate limited to ~20 ms.
     */
    private fun maybeSampleAudioClockJitter() {
        val nowMs = SystemClock.elapsedRealtime()
        val lastWall = jitterLastWallMs
        if (lastWall != 0L && nowMs - lastWall < JITTER_MIN_INTERVAL_MS) return

        val posUs = runCatching { delegate.getCurrentPositionUs(false) }.getOrNull() ?: return
        if (posUs == AudioSink.CURRENT_POSITION_NOT_SET) return

        val active = playbackActive
        val lastPos = jitterLastPosUs
        if (lastPos != C.TIME_UNSET && lastWall != 0L) {
            val wallDeltaMs = nowMs - lastWall
            val posDeltaMs = (posUs - lastPos) / 1_000L
            val expectedMs = (wallDeltaMs * playbackSpeed).toLong()
            val absMs = abs(posDeltaMs - expectedMs)
            // nt35: a sample only counts while the player is actually playing (see
            // playbackActive) AND its window is plausible AND its deviation is
            // plausible. setPlaybackActive resets the window on every transition, so
            // reaching here with a stale lastPos across a pause is not possible.
            val counted = active &&
                wallDeltaMs in JITTER_MIN_INTERVAL_MS..JITTER_MAX_WINDOW_MS &&
                absMs <= JITTER_MAX_PLAUSIBLE_MS
            if (counted) {
                jitterSamples += 1
                jitterSumAbsMs += absMs
                if (absMs > jitterMaxAbsMs) jitterMaxAbsMs = absMs
                if (absMs >= JITTER_EVENT_MS) jitterEvents += 1
                driftWallAccumMs += wallDeltaMs
                driftPosAccumMs += posDeltaMs
                driftExpectedAccumMs += expectedMs
                if (driftWallAccumMs >= 1_000L) {
                    val driftMs = driftPosAccumMs - driftExpectedAccumMs
                    val driftAbs = abs(driftMs)
                    driftWindows += 1
                    driftLastMs = driftMs
                    driftSumAbsMs += driftAbs
                    if (driftAbs > driftMaxAbsMs) driftMaxAbsMs = driftAbs
                    driftWallAccumMs = 0L
                    driftPosAccumMs = 0L
                    driftExpectedAccumMs = 0L
                }
            }
        }
        jitterLastPosUs = posUs
        jitterLastWallMs = nowMs
    }

    /** Jitter over this playback session, or null before enough samples exist to mean anything. */
    fun audioClockJitter(): AudioClockJitter? {
        val n = jitterSamples
        if (n < JITTER_MIN_SAMPLES) return null
        val w = driftWindows
        return AudioClockJitter(
            samples = n,
            events = jitterEvents,
            maxAbsMs = jitterMaxAbsMs,
            meanAbsMs = jitterSumAbsMs / n,
            driftWindows = w,
            driftLastMs = driftLastMs,
            driftMaxAbsMs = driftMaxAbsMs,
            driftMeanAbsMs = if (w > 0) driftSumAbsMs / w else 0L
        )
    }

    @Volatile
    private var audioTrackFieldLookupFailed: Boolean = false

    private var cachedAudioTrackField: Field? = null

    /**
     * The native AudioTrack's own underrun count, read straight off the track.
     *
     * The HUD's Underruns row is NOT unwired: media3 on API 24+ already derives its underrun
     * events from AudioTrack.getUnderrunCount() deltas (AudioTrackPositionTracker
     * .hasPendingAudioTrackUnderruns, verified in the shipped AAR) and dispatches them through
     * AudioSink.Listener -> AnalyticsListener.onAudioUnderrun, which this fork already handles.
     * But media3 only polls that counter inside handleBuffer — when the renderer happens to be
     * feeding. Reading it directly on the HUD tick is a cross-check, and a discriminating one:
     *
     *  - native > media3's event count  => media3 missed underruns the hardware did report.
     *  - both zero while the HAL logs underrun-restarts => the failure is below the client
     *    buffer, inside the vendor HAL, where no app-visible counter can reach — which is the
     *    case for MAT-packing failures and is the argument for packing MAT ourselves.
     *
     * Same reflection pattern as tryReuseAudioTrackOnFlush; proguard-rules.pro keeps
     * androidx.media3.** in full, so the field name survives R8. Fails closed: one warning,
     * then the row simply omits the native figure.
     */
    fun nativeAudioTrackUnderrunCount(): Int? {
        if (audioTrackFieldLookupFailed) return null
        val defaultSink = delegate as? DefaultAudioSink ?: return null
        return try {
            val field = cachedAudioTrackField
                ?: DefaultAudioSink::class.java.getDeclaredField("audioTrack")
                    .apply { isAccessible = true }
                    .also { cachedAudioTrackField = it }
            (field.get(defaultSink) as? AudioTrack)?.underrunCount
        } catch (t: Throwable) {
            audioTrackFieldLookupFailed = true
            Log.w(TAG, "native AudioTrack underrun count unavailable: ${t.message}")
            null
        }
    }

    // nt: retained but no longer called (see flush()). Reuse-on-flush desynced
    // passthrough audio after seeks on some HALs; kept for reference/re-enable.
    @Suppress("unused")
    private fun tryReuseAudioTrackOnFlush(): Boolean {
        val defaultSink = delegate as? DefaultAudioSink ?: return false
        return try {
            val audioTrackField = DefaultAudioSink::class.java.getDeclaredField("audioTrack").apply { isAccessible = true }
            val audioTrack = audioTrackField.get(defaultSink) as? AudioTrack ?: return false

            val pendingConfigField = DefaultAudioSink::class.java.getDeclaredField("pendingConfiguration").apply { isAccessible = true }
            val pendingConfiguration = pendingConfigField.get(defaultSink)

            val configurationField = DefaultAudioSink::class.java.getDeclaredField("configuration").apply { isAccessible = true }
            val configuration = configurationField.get(defaultSink) ?: return false

            if (pendingConfiguration != null) {
                val canReuseMethod = configuration.javaClass.getDeclaredMethod("canReuseAudioTrack", pendingConfiguration.javaClass).apply { isAccessible = true }
                val canReuse = canReuseMethod.invoke(configuration, pendingConfiguration) as Boolean
                if (!canReuse) {
                    return false
                }
                // Update configuration to the pending one
                configurationField.set(defaultSink, pendingConfiguration)
                pendingConfigField.set(defaultSink, null)
            }

            val positionTrackerField = DefaultAudioSink::class.java.getDeclaredField("audioTrackPositionTracker").apply { isAccessible = true }
            val positionTracker = positionTrackerField.get(defaultSink) ?: return false

            val isPlayingMethod = positionTracker.javaClass.getDeclaredMethod("isPlaying").apply { isAccessible = true }
            val isPlaying = isPlayingMethod.invoke(positionTracker) as Boolean
            if (isPlaying) {
                audioTrack.pause()
            }

            val isOffloadedPlaybackMethod = DefaultAudioSink::class.java.getDeclaredMethod("isOffloadedPlayback", AudioTrack::class.java).apply { isAccessible = true }
            val isOffloaded = isOffloadedPlaybackMethod.invoke(null, audioTrack) as Boolean
            if (isOffloaded) {
                val offloadCallbackField = DefaultAudioSink::class.java.getDeclaredField("offloadStreamEventCallbackV29").apply { isAccessible = true }
                val offloadCallback = offloadCallbackField.get(defaultSink)
                if (offloadCallback != null) {
                    val unregisterMethod = offloadCallback.javaClass.getDeclaredMethod("unregister", AudioTrack::class.java).apply { isAccessible = true }
                    unregisterMethod.invoke(offloadCallback, audioTrack)
                }
            }

            // Flush the native AudioTrack buffer
            audioTrack.flush()

            // Reset position tracker state, re-associating with the same AudioTrack
            val setAudioTrackMethod = positionTracker.javaClass.getDeclaredMethod(
                "setAudioTrack",
                AudioTrack::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType
            ).apply { isAccessible = true }

            val outputEncodingField = configuration.javaClass.getDeclaredField("outputEncoding").apply { isAccessible = true }
            val outputEncoding = outputEncodingField.get(configuration) as Int
            val outputPcmFrameSizeField = configuration.javaClass.getDeclaredField("outputPcmFrameSize").apply { isAccessible = true }
            val outputPcmFrameSize = outputPcmFrameSizeField.get(configuration) as Int
            val bufferSizeField = configuration.javaClass.getDeclaredField("bufferSize").apply { isAccessible = true }
            val bufferSize = bufferSizeField.get(configuration) as Int

            val enableOnAudioPositionAdvancingFixField = DefaultAudioSink::class.java.getDeclaredField("enableOnAudioPositionAdvancingFix").apply { isAccessible = true }
            val enableOnAudioPositionAdvancingFix = enableOnAudioPositionAdvancingFixField.get(defaultSink) as Boolean

            setAudioTrackMethod.invoke(
                positionTracker,
                audioTrack,
                true, // isPassthrough
                outputEncoding,
                outputPcmFrameSize,
                bufferSize,
                enableOnAudioPositionAdvancingFix
            )

            // Reset all internal default sink states for a clean flush
            val resetSinkStateForFlushMethod = DefaultAudioSink::class.java.getDeclaredMethod("resetSinkStateForFlush").apply { isAccessible = true }
            resetSinkStateForFlushMethod.invoke(defaultSink)

            // Clear exception holders
            val writeExceptionField = DefaultAudioSink::class.java.getDeclaredField("writeExceptionPendingExceptionHolder").apply { isAccessible = true }
            val writeExceptionHolder = writeExceptionField.get(defaultSink)
            val clearMethod = writeExceptionHolder.javaClass.getDeclaredMethod("clear").apply { isAccessible = true }
            clearMethod.invoke(writeExceptionHolder)

            val initExceptionField = DefaultAudioSink::class.java.getDeclaredField("initializationExceptionPendingExceptionHolder").apply { isAccessible = true }
            val initExceptionHolder = initExceptionField.get(defaultSink)
            clearMethod.invoke(initExceptionHolder)

            // Reset frame counts
            DefaultAudioSink::class.java.getDeclaredField("skippedOutputFrameCountAtLastPosition").apply { isAccessible = true }.set(defaultSink, 0L)
            DefaultAudioSink::class.java.getDeclaredField("accumulatedSkippedSilenceDurationUs").apply { isAccessible = true }.set(defaultSink, 0L)

            val reportSkippedSilenceHandlerField = DefaultAudioSink::class.java.getDeclaredField("reportSkippedSilenceHandler").apply { isAccessible = true }
            val reportSkippedSilenceHandler = reportSkippedSilenceHandlerField.get(defaultSink) as? android.os.Handler
            reportSkippedSilenceHandler?.removeCallbacksAndMessages(null)

            if (isOffloaded) {
                val offloadCallbackField = DefaultAudioSink::class.java.getDeclaredField("offloadStreamEventCallbackV29").apply { isAccessible = true }
                val offloadCallback = offloadCallbackField.get(defaultSink)
                if (offloadCallback != null) {
                    val registerMethod = offloadCallback.javaClass.getDeclaredMethod("register", AudioTrack::class.java).apply { isAccessible = true }
                    registerMethod.invoke(offloadCallback, audioTrack)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reuse AudioTrack on flush, falling back to full recreation", e)
            false
        }
    }

    fun armPassthroughResync() {
        if (isCurrentlyPassthrough) {
            passthroughPauseCompensationPending = true
            Log.d(TAG, "Passthrough resync manually armed for rebuffer/recovery")
        }
    }

    override fun pause() {
        if (isCurrentlyPassthrough) {
            passthroughPauseCompensationPending = true
            Log.d(TAG, "Passthrough pause: compensation armed for ${currentInputFormat?.sampleMimeType}")
        }
        super.pause()
    }

    override fun play() {
        if (passthroughPauseCompensationPending || passthroughStartupCompensationPending) {
            val isStartup = passthroughStartupCompensationPending
            passthroughPauseCompensationPending = false
            passthroughStartupCompensationPending = false
            // Force DefaultAudioSink to resync startMediaTimeUs on the next handleBuffer() call.
            // This compensates for initial passthrough handshake or audio played from receiver buffer during pause.
            handleDiscontinuity()
            Log.d(TAG, "Passthrough ${if (isStartup) "startup" else "resume"}: forced media time resync via handleDiscontinuity()")
        }
        super.play()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        playbackSpeed = normalizeSpeed(playbackParameters.speed)
        var shouldNotify = markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
        // Audio review F7: returning to 1.0x previously left forcePcm set for the
        // rest of the session - one visit to 1.25x silently killed TrueHD/DTS
        // passthrough until the next title. Clear it (unless PCM was forced at
        // construction as part of error recovery) and notify so the track
        // selector re-evaluates bypass; the selector is configured with
        // setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true).
        if (playbackSpeed == 1f && forcePcmForCurrentSession && !startedWithForcedPcm) {
            forcePcmForCurrentSession = false
            shouldNotify = true
        }
        super.setPlaybackParameters(playbackParameters)
        if (shouldNotify) {
            listener?.onAudioCapabilitiesChanged()
        }
    }

    fun notifyAudioProcessingRequirementChanged() {
        listener?.onAudioCapabilitiesChanged()
    }

    override fun getFormatSupport(format: Format): Int {
        if (shouldRejectDirectPlayback(format)) {
            return AudioSink.SINK_FORMAT_UNSUPPORTED
        }
        if (forceAc3Support &&
            format.sampleMimeType == MimeTypes.AUDIO_AC3 &&
            format.channelCount <= 6 &&
            super.getFormatSupport(format) == AudioSink.SINK_FORMAT_UNSUPPORTED
        ) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return super.getFormatSupport(format)
    }

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport {
        if (shouldRejectDirectPlayback(format)) {
            return AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
        return super.getFormatOffloadSupport(format)
    }

    fun shouldForcePcmForFormat(format: Format): Boolean {
        return shouldRejectDirectPlayback(format)
    }

    /** Returns true if audio is currently playing in direct passthrough mode. */
    fun isDirectPlaybackActive(): Boolean {
        val format = currentInputFormat ?: return false
        return isBitstreamFormat(format) && !shouldRejectDirectPlayback(format)
    }

    private fun shouldRejectDirectPlayback(format: Format): Boolean {
        return isBitstreamFormat(format) && (forcePcmForCurrentSession || playbackSpeed != 1f)
    }

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || speed == 1f || !isBitstreamFormat(format)) {
            return false
        }
        val wasForcingPcm = forcePcmForCurrentSession
        forcePcmForCurrentSession = true
        return !wasForcingPcm
    }

    private fun normalizeSpeed(speed: Float): Float {
        return speed.takeIf { it > 0f } ?: 1f
    }

    private fun isBitstreamFormat(format: Format): Boolean {
        val mimeType = format.sampleMimeType
        if (mimeType != null && (
                mimeType == MimeTypes.AUDIO_E_AC3 ||
                    mimeType == MimeTypes.AUDIO_E_AC3_JOC ||
                    mimeType == MimeTypes.AUDIO_AC3 ||
                    mimeType == MimeTypes.AUDIO_AC4 ||
                    mimeType == MimeTypes.AUDIO_TRUEHD ||
                    mimeType == MimeTypes.AUDIO_DTS ||
                    mimeType == MimeTypes.AUDIO_DTS_HD ||
                    mimeType == MimeTypes.AUDIO_DTS_EXPRESS ||
                    mimeType.startsWith("audio/vnd.dts")
                )
        ) {
            return true
        }
        val codecs = format.codecs
        if (codecs != null) {
            return codecs.contains("ac-3", ignoreCase = true) ||
                codecs.contains("ac-4", ignoreCase = true) ||
                codecs.contains("ec-3", ignoreCase = true) ||
                codecs.contains("dts", ignoreCase = true) ||
                codecs.contains("truehd", ignoreCase = true) ||
                codecs.contains("dtshd", ignoreCase = true)
        }
        return false
    }

    companion object {
        private const val TAG = "PassthroughAudioSink"
    }
}
