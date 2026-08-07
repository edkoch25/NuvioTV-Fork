package com.nuvio.tv.ui.screens.player

import android.media.AudioDeviceInfo
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
import com.nuvio.tv.core.player.AudioPassthroughPolicy
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

// nt7: byte floor and wall cap for the deferred TrueHD passthrough start (see play()).
// 192 KiB is 3.5+ s of a near-silent (~0.3 Mbps) TrueHD head — comfortably past the
// Amlogic MS12 startup draw that emptied a ~55 KB prefill on device — while a typical
// (>= 1 Mbps) track has written this much before play() is ever called and never defers.
private const val TRUEHD_START_MIN_BYTES = 196_608L
private const val TRUEHD_START_DEFER_CAP_MS = 1_500L

// nt8: TrueHD startup-storm detector (see truehdStormLeadAccumMs). Storm lead was
// measured on device at ~550 ms of clock lead per wall second (and the storm never
// self-recovered); clean-playback noise is symmetric, and signed accumulation with
// a floor at zero random-walks near zero, so a 1 s accumulated lead inside the 60 s
// startup window separates cleanly.
private const val TRUEHD_STORM_LEAD_THRESHOLD_MS = 1_000L
private const val TRUEHD_STORM_SAMPLE_CAP_MS = 2_000L
private const val TRUEHD_STORM_MONITOR_WINDOW_MS = 60_000L

// nt16: SHADOW governor tunables (log-only; getCurrentPositionUs returns raw).
private const val GOV_ENGAGE_RATE_X100 = 150L   // engage when raw races >1.50x
private const val GOV_RATE_WINDOW_MS = 50L       // window for the raw-rate estimate
private const val GOV_A_CAP_US = 2_000_000L      // variant A: snap forward if >2s behind
private const val GOV_C_FREEZE_US = 300_000L     // variant C: freeze once >300ms ahead
private const val GOV_LOG_INTERVAL_MS = 5L       // GOV trace rate limit
private const val GOV_TELEPORT_REJECT_X100 = 4000L  // >40x = seek/rebuffer jump, never a storm
private const val GOV_MAX_FREEZE_MS = 3500L         // nt19: hard cap on frozen-frame duration (>D-spacing)

/**
 * Audio sink wrapper that forces a decode-to-PCM path when:
 * - Playback speed != 1x for bitstream formats that cannot be tempo-adjusted in passthrough, or
 * - Bluetooth media output is active (Media3 policy: Bluetooth only supports PCM).
 *
 * Bluetooth cannot carry TrueHD / Atmos / DTS-HD passthrough. Forcing PCM lets MediaCodec/FFmpeg
 * decode to the format the BT stack actually accepts; the system then encodes to SBC/AAC/aptX/LDAC.
 */
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
    private val forceAc3Support: Boolean = false,
    /**
     * Which formats the user has said their receiver can decode. Defaults to
     * [AudioPassthroughPolicy.ALLOW_ALL], which denies nothing - so a construction
     * site that omits it keeps the platform-report behaviour rather than changing it.
     */
    private val passthroughPolicy: AudioPassthroughPolicy = AudioPassthroughPolicy.ALLOW_ALL,
    /**
     * Diagnostic harness (build 1): when non-null, refuse to open a passthrough AudioTrack
     * for this sample MIME by throwing InitializationException on the first buffer - a
     * faithful stand-in for a TV/HAL that advertises an encoding via
     * isDirectPlaybackSupported but rejects it at open() (the Skyworth DTS-HD case). Lets
     * the 5001 recovery ladder be walked on hardware that accepts the format natively.
     * Armed with `settings put global nuvio_fault_reject_mime <mime>`; null (the shipping
     * default) is fully inert - one volatile read per buffer and nothing else.
     */
    private val faultInjectRejectMime: String? = null,
    /**
     * Upstream 0.8.2: when Bluetooth media output is active, always decode to PCM
     * (Media3 policy - A2DP/LE Audio cannot carry TrueHD/Atmos/DTS-HD bitstream).
     */
    forcePcmForBluetooth: Boolean = false
) : ForwardingAudioSink(delegate) {

    // Set when the sink is built with forcePcm (error recovery). Don't clear on speed reset.
    private val startedWithForcedPcm: Boolean = initialForcePcm

    @Volatile
    private var playbackSpeed: Float = 1f

    @Volatile
    private var forcePcmForCurrentSession: Boolean = initialForcePcm

    @Volatile
    private var bluetoothForcePcm: Boolean = forcePcmForBluetooth

    @Volatile
    private var currentInputFormat: Format? = null

    // Diagnostic harness: one refusal per configure cycle, mirroring a real init-time
    // failure (the track open() throws once, not on every buffer).
    private var faultInjectFiredForCurrentConfig: Boolean = false

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
     * F9 fallback: the format this sink was last configured with - post-decode
     * (PCM) or post-transcode (AC-3) on the FFmpeg path, the original bitstream
     * on passthrough. Lets the controller derive the Audio Path diagnostics row
     * without depending on the renderer-side track-init event, which never
     * arrives on the extension-renderer path.
     */
    internal val lastConfiguredInputFormat: Format?
        get() = currentInputFormat

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

    /**
     * nt7: byte-gated TrueHD passthrough start (see play()). Set when play() was
     * deferred because too few encoded bytes had been written; released from
     * handleBuffer() once the byte floor is met or the wall cap expires.
     */
    @Volatile
    private var passthroughDeferredPlayPending: Boolean = false

    @Volatile
    private var passthroughDeferredPlaySinceMs: Long = 0L

    // nt8: TrueHD startup-storm detector. After a display-mode switch the Amlogic
    // MS12 TrueHD bypass parser can start misaligned and hunt for a major sync
    // indefinitely, consuming input 3-4x faster than real time; under passthrough
    // the audio clock is the master, so the position visibly races ahead. Detected
    // here as accumulated positive clock lead over wall time within a startup
    // window; consumed by the controller, whose proven cure is an in-place seek.
    @Volatile
    private var truehdStormLeadAccumMs: Long = 0L

    @Volatile
    private var truehdStormMonitorUntilMs: Long = 0L

    @Volatile
    private var truehdStormDetected: Boolean = false

    // nt9: one storm-accumulator sample is skipped right after a passthrough resync:
    // handleDiscontinuity() makes DefaultAudioSink restamp startMediaTimeUs on the
    // next buffer, which can appear as a single large position stride. The jitter
    // row's plausibility cap filters it there; the storm accumulator deliberately
    // has no such cap, so it must skip that one stride instead.
    @Volatile
    private var skipNextStormSampleAfterResync: Boolean = false

    // nt14: log-only wall-stamp rate limiter for the SEEK_TRACE POS instrument
    // (getCurrentPositionUs override). Observation only; no clock-path effect.
    @Volatile
    private var seekTracePosLogAtMs: Long = 0L

    // nt16: SHADOW governor state. All log-only -- getCurrentPositionUs returns the
    // raw delegate value regardless. Engages on a raced raw rate while playing,
    // disengages in resetAudioMeasurements() (flush/configure).
    @Volatile private var govEngaged: Boolean = false
    @Volatile private var govBasePosUs: Long = 0L   // B & C baseline
    @Volatile private var govBaseWallMs: Long = 0L
    @Volatile private var govAPosUs: Long = 0L      // A baseline (re-anchors on cap)
    @Volatile private var govAWallMs: Long = 0L
    @Volatile private var govCFrozen: Boolean = false
    @Volatile private var govCHoldUs: Long = 0L
    @Volatile private var govFreezeStartMs: Long = 0L  // nt19: when the current freeze began
    @Volatile private var govCReleased: Boolean = false // nt19: freeze cap tripped -> follow raw this storm
    @Volatile private var govRateRefPosUs: Long = C.TIME_UNSET
    @Volatile private var govRateRefWallMs: Long = 0L
    @Volatile private var govRateX100: Long = 100L
    @Volatile private var govLogAtMs: Long = 0L
    @Volatile private var govOldEngaged: Boolean = false  // nt17: nt16-rule latch, for old-vs-new logging
    @Volatile private var govLastSpikeMs: Long = 0L        // nt17: last in-band spike, for recurrence

    fun setInitialPlaybackSpeed(speed: Float) {
        playbackSpeed = normalizeSpeed(speed)
        markPcmFallbackIfNeeded(currentInputFormat, playbackSpeed)
    }

    /**
     * Update Bluetooth policy without rebuilding the player when possible.
     * Call [notifyAudioProcessingRequirementChanged] after flipping from false → true mid-session
     * if the sink is already configured for passthrough.
     */
    fun setBluetoothForcePcm(enabled: Boolean) {
        bluetoothForcePcm = enabled
        if (enabled) {
            forcePcmForCurrentSession = true
        }
    }

    fun isBluetoothForcePcm(): Boolean = bluetoothForcePcm

    // ORD_TRACE (log-only ordering probe, strip before publication): an R2-class
    // cold start proved the play()-side gate logic can be bypassed while the
    // AudioTrack still starts. These entry logs name the real call order at the
    // wrapper boundary. Zero behaviour change.
    private var ordFirstHandleBufferPending = false

    // nt10: the renderer's most recent play/pause intent. configure() uses it to
    // detect a play() that arrived before the sink knew its format (the
    // quiesce-ordering hole proven by the ORD_TRACE capture, 7 Aug 2026).
    private var playRequested = false


    private fun ordState(): String =
        "mime=${currentInputFormat?.sampleMimeType} pt=$isCurrentlyPassthrough " +
            "bytes=$encodedAudioBytes defer=$passthroughDeferredPlayPending " +
            "startPend=$passthroughStartupCompensationPending " +
            "pausePend=$passthroughPauseCompensationPending " +
            "armed=${truehdStormMonitorUntilMs != 0L}"

    // ORD_TRACE: the AFR quiesce's renderer disable/re-enable traverses reset()
    // with no wrapper override, invisibly to every existing log. Observe it.
    // nt10: make reset() truthful. The AFR quiesce's renderer disable lands here;
    // leaving the previous stream's format flags in place made every post-quiesce
    // play() gate on stale state (correct only by luck) and defeated configure()'s
    // wasPassthrough transition check, silently losing the startup resync.
    override fun reset() {
        Log.w(TAG, "ORD_TRACE reset() ${ordState()}")
        currentInputFormat = null
        isCurrentlyPassthrough = false
        playRequested = false
        super.reset()
    }

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(listener)
    }

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        Log.w(TAG, "ORD_TRACE configure(in=${inputFormat.sampleMimeType}) ${ordState()}")
        ordFirstHandleBufferPending = true
        currentInputFormat = inputFormat
        faultInjectFiredForCurrentConfig = false
        resetAudioMeasurements()
        markPcmFallbackIfNeeded(inputFormat, playbackSpeed)
        // Detect if this format will play in passthrough mode (bitstream, not forced to PCM)
        val wasPassthrough = isCurrentlyPassthrough
        isCurrentlyPassthrough = isBitstreamFormat(inputFormat) && !shouldRejectDirectPlayback(inputFormat)
        if (isCurrentlyPassthrough && !wasPassthrough) {
            passthroughStartupCompensationPending = true
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        maybeEngageLateStartGate()
    }

    override fun flush() {
        Log.w(TAG, "ORD_TRACE flush() ${ordState()}")
        passthroughPauseCompensationPending = false
        passthroughStartupCompensationPending = false
        passthroughDeferredPlayPending = false
        resetAudioMeasurements()
        // nt: AudioTrack reuse-on-flush disabled. Reusing the passthrough
        // AudioTrack across a seek left the audio clock mismapped on some HALs
        // (e.g. Ugoos SK1), causing progressive A/V desync after skipping.
        // Fall through to release/recreate, matching upstream flush behaviour.
        super.flush()
    }

    // nt14: SEEK_TRACE POS -- log-only observation of the renderer master-clock
    // read. Returns super(...) UNCHANGED (no clamp, no intervention); logs the
    // value rate-limited to ~5ms so the seek->flush poison window can be read on
    // the real read path rather than the :361 proxy.
    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val posUs = super.getCurrentPositionUs(sourceEnded)
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - seekTracePosLogAtMs >= 5L) {
            seekTracePosLogAtMs = nowMs
            Log.w(TAG, "SEEK_TRACE POS er=$nowMs posUs=$posUs sourceEnded=$sourceEnded")
        }
        val governedUs = computeShadowGovernor(posUs, nowMs)
        return governedUs
    }

    // nt16: SHADOW governor. Computes three candidate governed positions and logs
    // them against raw. RETURNS NOTHING and CHANGES NOTHING -- getCurrentPositionUs
    // returns the raw delegate value. Engages when the windowed raw rate exceeds
    // GOV_ENGAGE_RATE_X100 while playing; disengaged in resetAudioMeasurements().
    private fun computeShadowGovernor(rawUs: Long, nowMs: Long): Long {
        if (rawUs == AudioSink.CURRENT_POSITION_NOT_SET) return rawUs
        if (govRateRefPosUs == C.TIME_UNSET) {
            govRateRefPosUs = rawUs; govRateRefWallMs = nowMs
        } else if (nowMs - govRateRefWallMs >= GOV_RATE_WINDOW_MS) {
            val dW = nowMs - govRateRefWallMs
            if (dW > 0L) govRateX100 = ((rawUs - govRateRefPosUs) * 100L) / (dW * 1000L)
            govRateRefPosUs = rawUs; govRateRefWallMs = nowMs
            // nt17: classify this rate window once (spike-edge counting, not per-read).
            val r = govRateX100
            // old rule (nt16): engaged on the first >1.5x window while playing. Logged
            // for old-vs-new comparison -- proves the refinement only drops rebuffers.
            if (!govOldEngaged && playbackActive && r > GOV_ENGAGE_RATE_X100) {
                govOldEngaged = true
                Log.w(TAG, "SEEK_TRACE GOV_WOULD_ENGAGE_OLD er=$nowMs atPosUs=$rawUs rate100=$r")
            }
            if (r > GOV_TELEPORT_REJECT_X100) {
                // teleport (seek/rebuffer jump) -- never a storm; reject, do not count.
                Log.w(TAG, "SEEK_TRACE GOV_REJECT er=$nowMs rate100=$r reason=teleport")
            } else if (r > GOV_ENGAGE_RATE_X100) {
                // nt18: in-band spike (1.5x..40x) engages directly. The recurrence
                // gate (removed) suppressed real storms -- run5 missed 3 storms at
                // 14-25x; teleport-reject alone separates storms from rebuffer jumps.
                if (!govEngaged && playbackActive) {
                    govEngaged = true
                    govBasePosUs = rawUs; govBaseWallMs = nowMs
                    govAPosUs = rawUs; govAWallMs = nowMs
                    govCFrozen = false; govCHoldUs = rawUs; govCReleased = false; govFreezeStartMs = 0L
                    Log.w(TAG, "SEEK_TRACE GOV_ENGAGE er=$nowMs atPosUs=$rawUs rate100=$r " +
                        "sinceLastSpikeMs=${nowMs - govLastSpikeMs}")
                }
                govLastSpikeMs = nowMs
            }
        }
        var govA = rawUs
        var govB = rawUs
        var govC = rawUs
        if (govEngaged) {
            val ceilBC = govBasePosUs + (nowMs - govBaseWallMs) * 1000L
            govB = if (rawUs < ceilBC) rawUs else ceilBC
            // nt19: C is a 3-state machine. TRACKING follows raw at <=1.0x; on a
            // >300ms lead it FREEZES (holds the frame). If frozen past the hard cap
            // it RELEASES and follows raw for the rest of this storm, so the picture
            // can never freeze indefinitely if a recovery seek never lands.
            if (govCReleased) {
                govC = rawUs
            } else if (!govCFrozen) {
                if (rawUs > ceilBC + GOV_C_FREEZE_US) {
                    govCFrozen = true; govCHoldUs = ceilBC; govFreezeStartMs = nowMs; govC = ceilBC
                } else {
                    govC = if (rawUs < ceilBC) rawUs else ceilBC
                }
            } else if (nowMs - govFreezeStartMs > GOV_MAX_FREEZE_MS) {
                govCReleased = true; govCFrozen = false; govC = rawUs
                Log.w(TAG, "SEEK_TRACE GOV_FREEZE_CAP er=$nowMs heldMs=${nowMs - govFreezeStartMs} snapToUs=$rawUs")
            } else {
                govC = govCHoldUs
            }
            val ceilA = govAPosUs + (nowMs - govAWallMs) * 1000L
            if (rawUs - ceilA > GOV_A_CAP_US) {
                govAPosUs = rawUs; govAWallMs = nowMs; govA = rawUs
            } else {
                govA = if (rawUs < ceilA) rawUs else ceilA
            }
        }
        if (nowMs - govLogAtMs >= GOV_LOG_INTERVAL_MS) {
            govLogAtMs = nowMs
            Log.w(TAG, "SEEK_TRACE GOV er=$nowMs raw=$rawUs A=$govA B=$govB C=$govC " +
                "eng=${if (govEngaged) 1 else 0} frozen=${if (govCFrozen) 1 else 0} rate100=$govRateX100")
        }
        // nt19 LIVE C: report the frozen position while engaged+frozen; raw otherwise.
        return if (govEngaged && govCFrozen) govCHoldUs else rawUs
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
        if (ordFirstHandleBufferPending) {
            ordFirstHandleBufferPending = false
            Log.w(TAG, "ORD_TRACE first handleBuffer after configure ${ordState()}")
        }
        val rejectMime = faultInjectRejectMime
        val injectFmt = currentInputFormat
        if (rejectMime != null && isCurrentlyPassthrough && !faultInjectFiredForCurrentConfig &&
            injectFmt != null && injectFmt.sampleMimeType == rejectMime
        ) {
            faultInjectFiredForCurrentConfig = true
            Log.w(TAG, "FAULT_INJECT: refusing passthrough AudioTrack for $rejectMime (simulated HAL rejection)")
            throw AudioSink.InitializationException(
                "AudioTrack init failed ${AudioTrack.STATE_UNINITIALIZED} fault-injected $rejectMime",
                AudioTrack.STATE_UNINITIALIZED,
                injectFmt,
                true,
                null
            )
        }
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
        maybeReleaseDeferredPlay()
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
        truehdStormLeadAccumMs = 0L
        truehdStormMonitorUntilMs = 0L
        truehdStormDetected = false
        skipNextStormSampleAfterResync = false
        // nt16: disengage the shadow governor on flush/configure.
        govEngaged = false
        govCFrozen = false
        govRateRefPosUs = C.TIME_UNSET
        govRateX100 = 100L
        govOldEngaged = false
        govLastSpikeMs = 0L
        govFreezeStartMs = 0L
        govCReleased = false
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
        // nt7: while a deferred start holds the track stopped, the player already
        // reports isPlaying and the position legitimately sits still — every sample
        // would score as a fake jitter event (the nt35 lesson). Sit out the deferral.
        if (passthroughDeferredPlayPending) return
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
            if (absMs > 50L) {
                Log.w(
                    TAG,
                    "SEEK_TRACE JITTER er=$nowMs posUs=$posUs posDeltaMs=$posDeltaMs " +
                        "expectedMs=$expectedMs devMs=$absMs"
                )
            }
            // nt8: storm detector -- signed lead accumulation, floored at zero, each
            // sample clamped. Runs independently of the jitter row's plausibility cap
            // so violent strides still register. Active-only and window-bounded.
            if (truehdStormMonitorUntilMs != 0L && !truehdStormDetected &&
                active && wallDeltaMs in JITTER_MIN_INTERVAL_MS..JITTER_MAX_WINDOW_MS
            ) {
                if (nowMs > truehdStormMonitorUntilMs) {
                    truehdStormMonitorUntilMs = 0L
                } else if (skipNextStormSampleAfterResync) {
                    skipNextStormSampleAfterResync = false
                } else {
                    val leadMs = (posDeltaMs - expectedMs)
                        .coerceIn(-TRUEHD_STORM_SAMPLE_CAP_MS, TRUEHD_STORM_SAMPLE_CAP_MS)
                    truehdStormLeadAccumMs = (truehdStormLeadAccumMs + leadMs).coerceAtLeast(0L)
                    if (truehdStormLeadAccumMs >= TRUEHD_STORM_LEAD_THRESHOLD_MS) {
                        truehdStormDetected = true
                        truehdStormMonitorUntilMs = 0L
                        Log.w(
                            TAG,
                            "TRUEHD_STORM detected: audio clock leads wall by " +
                                "$truehdStormLeadAccumMs ms accumulated in the startup window"
                        )
                    }
                }
            }
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

    // nt6 Route row state. Single writer (the HUD sampler, ~1 Hz, panel
    // visible only), so the read-modify-write on the counter is safe.
    // A brand-new AudioTrack instance re-baselines WITHOUT counting —
    // flush() recreates the track on every seek by design (nt51) and
    // that must not read as a route change. Counting rule: same track,
    // transition FROM a known device to a different device or to null
    // (device lost mid-track) = one route change.
    @Volatile private var routeLastTrackIdentity: Int = 0
    @Volatile private var routeLastDeviceId: Int = -1
    @Volatile private var routeChangeCount: Int = 0

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

    /** One HUD-tick sample of the AudioTrack's routed output device (nt6). */
    data class AudioRouteSnapshot(val deviceLabel: String, val changeCount: Int)

    /**
     * nt6: poll the current AudioTrack's routed device for the stats HUD's
     * Route row. Same reflection pattern and fail-closed behaviour as
     * nativeAudioTrackUnderrunCount() above; called only from the HUD
     * sampler, never from the write path. A count ticking up while Buffer
     * stays healthy is the route-steal static signature (system capture
     * tool, HDMI renegotiation) — the class of fault the pinned-
     * capabilities design deliberately keeps the app blind to elsewhere.
     */
    fun sampleAudioRoute(): AudioRouteSnapshot? {
        if (audioTrackFieldLookupFailed) return null
        val defaultSink = delegate as? DefaultAudioSink ?: return null
        return try {
            val field = cachedAudioTrackField
                ?: DefaultAudioSink::class.java.getDeclaredField("audioTrack")
                    .apply { isAccessible = true }
                    .also { cachedAudioTrackField = it }
            val track = field.get(defaultSink) as? AudioTrack ?: return null
            val identity = System.identityHashCode(track)
            val device = track.routedDevice
            val deviceId = device?.id ?: -1
            when {
                identity != routeLastTrackIdentity -> {
                    routeLastTrackIdentity = identity
                    routeLastDeviceId = deviceId
                }
                routeLastDeviceId != -1 && deviceId != routeLastDeviceId -> {
                    routeChangeCount += 1
                    routeLastDeviceId = deviceId
                }
                routeLastDeviceId == -1 -> routeLastDeviceId = deviceId
            }
            AudioRouteSnapshot(routeDeviceLabel(device), routeChangeCount)
        } catch (t: Throwable) {
            audioTrackFieldLookupFailed = true
            Log.w(TAG, "audio route sample unavailable: ${t.message}")
            null
        }
    }

    private fun routeDeviceLabel(device: AudioDeviceInfo?): String {
        val type = device?.type ?: return "none"
        return when (type) {
            AudioDeviceInfo.TYPE_HDMI -> "HDMI"
            AudioDeviceInfo.TYPE_HDMI_ARC -> "HDMI ARC"
            AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI eARC"
            AudioDeviceInfo.TYPE_LINE_DIGITAL -> "SPDIF"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
            AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "Submix"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
            else -> "type $type"
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
        Log.w(TAG, "ORD_TRACE pause() ${ordState()}")
        playRequested = false
        if (isCurrentlyPassthrough) {
            passthroughPauseCompensationPending = true
            Log.d(TAG, "Passthrough pause: compensation armed for ${currentInputFormat?.sampleMimeType}")
        }
        // nt7: a pause during a deferred start cancels the deferral without starting
        // the track. The next play() re-evaluates the byte floor from scratch.
        passthroughDeferredPlayPending = false
        super.pause()
    }

    override fun play() {
        Log.w(TAG, "ORD_TRACE play() ${ordState()}")
        playRequested = true
        // nt7: byte-gate TrueHD passthrough starts. ExoPlayer's start decision is
        // duration-based (~1.5 s buffered), but the Amlogic MS12 TrueHD path fails on
        // BYTE starvation: a near-silent VBR head (~0.3 Mbps, measured on device)
        // leaves ~55 KB in the AudioTrack at start, the pipeline's startup draw
        // empties it, and the resulting input gap costs MS12 its MLP access-unit
        // alignment — it then discards input hunting for a major sync faster than a
        // thin head can refill, a self-sustaining storm (~15 s on device) that steps
        // the master audio clock and drags the video renderer into a decoder flush.
        // Deferring the track start until a byte floor is met keeps the pipeline fed
        // through its startup draw. Dense tracks pass the floor before play() is
        // called and start exactly as before; the wall cap fail-safes to the old
        // behaviour so no stream can hold the start hostage.
        if (isCurrentlyPassthrough &&
            currentInputFormat?.sampleMimeType == MimeTypes.AUDIO_TRUEHD &&
            encodedAudioBytes < TRUEHD_START_MIN_BYTES
        ) {
            if (!passthroughDeferredPlayPending) {
                passthroughDeferredPlayPending = true
                passthroughDeferredPlaySinceMs = SystemClock.elapsedRealtime()
                Log.d(
                    TAG,
                    "Passthrough deferred start: $encodedAudioBytes B < " +
                        "$TRUEHD_START_MIN_BYTES B floor for audio/true-hd"
                )
            }
            return
        }
        firePendingPassthroughResync()
        armTruehdStormMonitor()
        super.play()
    }

    // nt10: play() can precede the first configure on the AFR-quiesced path (the
    // renderer is re-enabled and its start consumed before the decoder re-delivers
    // a format), so the play()-side byte-gate, monitor arm and startup resync all
    // silently miss -- proven by the ORD_TRACE capture (7 Aug 2026). Re-evaluate
    // here, where the format is finally known. The byte-floor condition makes this
    // a no-op on any warm pipeline; the deferral, release, resync and arm are all
    // the existing nt7/nt8 machinery. A user pause still cancels the deferral via
    // pause() exactly as on the play()-side path.
    private fun maybeEngageLateStartGate() {
        if (!playRequested || !isCurrentlyPassthrough ||
            currentInputFormat?.sampleMimeType != MimeTypes.AUDIO_TRUEHD ||
            passthroughDeferredPlayPending
        ) {
            return
        }
        if (encodedAudioBytes < TRUEHD_START_MIN_BYTES) {
            passthroughDeferredPlayPending = true
            passthroughDeferredPlaySinceMs = SystemClock.elapsedRealtime()
            super.pause()
            Log.d(
                TAG,
                "Passthrough late deferred start (configure after play): " +
                    "$encodedAudioBytes B < $TRUEHD_START_MIN_BYTES B floor for audio/true-hd"
            )
        } else {
            Log.d(TAG, "Passthrough late start gate (configure after play): pipeline warm; resync + arm applied")
            firePendingPassthroughResync()
            armTruehdStormMonitor()
        }
    }

    override fun playToEndOfStream() {
        // nt7: end of stream while a deferred start is pending — release it so the
        // buffered tail plays out instead of stalling a very short stream forever.
        maybeReleaseDeferredPlay(force = true)
        super.playToEndOfStream()
    }

    // nt7: the resync formerly inlined in play(); shared by the deferred-start release.
    private fun firePendingPassthroughResync() {
        if (passthroughPauseCompensationPending || passthroughStartupCompensationPending) {
            val isStartup = passthroughStartupCompensationPending
            passthroughPauseCompensationPending = false
            passthroughStartupCompensationPending = false
            // Force DefaultAudioSink to resync startMediaTimeUs on the next handleBuffer() call.
            // This compensates for initial passthrough handshake or audio played from receiver buffer during pause.
            handleDiscontinuity()
            Log.d(TAG, "Passthrough ${if (isStartup) "startup" else "resume"}: forced media time resync via handleDiscontinuity()")
            skipNextStormSampleAfterResync = true
        }
    }

    // nt7: called from handleBuffer() (passthrough branch) while a deferred start is
    // pending; starts the track once the byte floor is met or the wall cap expires.
    private fun maybeReleaseDeferredPlay(force: Boolean = false) {
        if (!passthroughDeferredPlayPending) return
        val elapsedMs = SystemClock.elapsedRealtime() - passthroughDeferredPlaySinceMs
        val floorMet = encodedAudioBytes >= TRUEHD_START_MIN_BYTES
        if (!force && !floorMet && elapsedMs < TRUEHD_START_DEFER_CAP_MS) return
        passthroughDeferredPlayPending = false
        firePendingPassthroughResync()
        Log.d(
            TAG,
            "Passthrough deferred start released: bytes=$encodedAudioBytes " +
                "floorMet=$floorMet elapsedMs=$elapsedMs force=$force"
        )
        armTruehdStormMonitor()
        super.play()
    }

    // nt8: arm the storm monitor at every real TrueHD passthrough start (deferred or
    // not) -- mode-switch storms are independent of head density.
    private fun armTruehdStormMonitor() {
        if (isCurrentlyPassthrough && currentInputFormat?.sampleMimeType == MimeTypes.AUDIO_TRUEHD) {
            // nt13 (0.8.2): arming must not destroy an undelivered verdict. Every
            // rebuffer-resume play() re-arms; clearing the detection here wiped
            // verdicts that the controller's spacing/cap gates had deferred across
            // a rebuffer boundary, leaving the storm path dead with an orphaned
            // onset latch (proven, 7 Aug capture: R1 wiped 343 ms into its consume
            // window; R3 wiped three times before the budget reset opened). A
            // pending detection now survives re-arm and is cleared only by
            // consume or by resetAudioMeasurements() (seek/flush/configure).
            if (!truehdStormDetected) {
                truehdStormLeadAccumMs = 0L
            }
            truehdStormMonitorUntilMs = SystemClock.elapsedRealtime() + TRUEHD_STORM_MONITOR_WINDOW_MS
            Log.w(TAG, "ORD_TRACE monitor ARMED windowMs=$TRUEHD_STORM_MONITOR_WINDOW_MS detectedPending=$truehdStormDetected")
        } else {
            Log.w(TAG, "ORD_TRACE monitor arm SKIPPED ${ordState()}")
        }
    }

    /**
     * nt11: non-consuming storm peek. The controller latches the player-timeline
     * position on the first tick this reads true, so recovery rolls back to storm
     * onset rather than the raced position at consume time. Does NOT clear the flag.
     */
    fun isTruehdStormDetected(): Boolean = truehdStormDetected

    /**
     * nt12 (0.8.2): observing accessor for the controller's snap-recovery gate --
     * true while the active sink format is TrueHD in passthrough mode.
     */
    fun isTruehdPassthroughActive(): Boolean =
        isCurrentlyPassthrough && currentInputFormat?.sampleMimeType == MimeTypes.AUDIO_TRUEHD

    /**
     * nt8: one-shot storm verdict for the controller's progress tick. Returns the
     * accumulated clock lead in ms once per detection, then clears.
     */
    fun consumeTruehdStormRecoverySignal(): Long? {
        if (!truehdStormDetected) return null
        truehdStormDetected = false
        return truehdStormLeadAccumMs
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

    /**
     * True when the *user's per-format policy* is the reason this format is being
     * decoded, as opposed to a speed change or a PCM-fallback recovery.
     *
     * The distinction matters because the renderer routes these to the bundled FFmpeg
     * decoder rather than the device one. Measured on an Amlogic S905X5M:
     * c2.amlogic.audio.decoder.dtshd folds a 5.1 DTS-HD MA track to 2 channels, where
     * FFmpeg returns the full 6. A user who switches a format off is asking for it to
     * be decoded properly, not halved, so the vendor decoder is not trusted for this
     * path. Speed changes and 5001-error recovery keep the device decoder, which is
     * cheaper and has never been implicated.
     */
    fun isPolicyDeniedPassthrough(format: Format): Boolean {
        return isBitstreamFormat(format) &&
            passthroughPolicy.deniesPassthrough(format.sampleMimeType)
    }

    /** Returns true if audio is currently playing in direct passthrough mode. */
    fun isDirectPlaybackActive(): Boolean {
        val format = currentInputFormat ?: return false
        return isBitstreamFormat(format) && !shouldRejectDirectPlayback(format)
    }

    private fun shouldRejectDirectPlayback(format: Format): Boolean {
        if (!isBitstreamFormat(format)) return false
        // Bluetooth: always decode to PCM (Media3 DEFAULT_AUDIO_CAPABILITIES policy).
        if (bluetoothForcePcm || forcePcmForCurrentSession || playbackSpeed != 1f) return true
        // Per-format user override. Inert on the default policy, so with every switch
        // left on this function is behaviourally identical to before. This is the only
        // chokepoint that needs to change: getFormatSupport, getFormatOffloadSupport,
        // configure and shouldForcePcmForFormat all route through here, and
        // PlaybackSpeedAwareAudioRenderer keys its decoder selection, bypass decision
        // and offload support off shouldForcePcmForFormat.
        return passthroughPolicy.deniesPassthrough(format.sampleMimeType)
    }

    private fun markPcmFallbackIfNeeded(format: Format?, speed: Float): Boolean {
        if (format == null || !isBitstreamFormat(format)) {
            return false
        }
        if (bluetoothForcePcm) {
            val wasForcingPcm = forcePcmForCurrentSession
            forcePcmForCurrentSession = true
            return !wasForcingPcm
        }
        if (speed == 1f) {
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
