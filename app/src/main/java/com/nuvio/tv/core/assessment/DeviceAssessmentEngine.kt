package com.nuvio.tv.core.assessment

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.R
import com.nuvio.tv.core.network.StreamSpeedTester
import com.nuvio.tv.core.network.StreamSweepEngine
import com.nuvio.tv.core.player.DisplayCapabilities
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.core.player.VodCacheSizing
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.VodCacheSizeMode
import com.nuvio.tv.ui.screens.player.AudioOutputRouteDetector
import com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.tv.ui.screens.settings.MemoryUsageStatus
import org.json.JSONObject

/**
 * Device Settings Assessment, Stage 1 (REVIEW-ONLY).
 *
 * One flow that gathers queryable device facts, resolves the live DV policy,
 * runs the shared stream sweep against the real last-played stream, and turns
 * the lot into per-setting recommendations labelled by confidence tier
 * (see AssessmentTier). Nothing here writes a setting; Stage 2 (apply path,
 * revert snapshot, safe-limit re-check) is a separate, later change.
 *
 * Grounding rules, agreed and enforced:
 *  - Tier 1 rows come only from the sweep verdict (cheapest configuration
 *    meeting 2x the title bitrate within the safe memory budget - nt38).
 *  - Tier 2 rows cite the queryable fact they were derived from. The dv7
 *    row demotes itself to VERIFY whenever hdrCapsKnown is false; the dv5
 *    and strip-HDR10+ rows are ALWAYS VERIFY and never enter the apply
 *    plan (nt6): their deciding facts ride the display chain's EDID (a
 *    soundbar in the path answers for the TV), so the probe can be
 *    confidently wrong, and strip adds per-sample work on the demux hot
 *    path - field-observed destabilising a 3 GB Tegra on 100 Mbit/s
 *    remuxes. Advisory text stays; one-tap apply does not touch them.
 *  - dv7ToDv81PreserveMappingEnabled and dv7LibdoviModeOverride are
 *    deliberately ABSENT: no queryable fact decides them (panel-dependent,
 *    eyes-on only), so the assessment does not mention them at all.
 *  - Audio is out of scope: the only honest AVR decode probe makes sound.
 *  - Tier 3 buffer profiles are user intent; the stability signal only
 *    SUGGESTS one, it never auto-applies. Profile numbers and the stability
 *    threshold are [inferred] initial values, marked as such here.
 *  - Recommendations stay inside the SAFE limit even though the sweep may
 *    PROBE up to the warning limit (probe-to-warning / recommend-to-safe
 *    asymmetry is deliberate).
 */
@UnstableApi
object DeviceAssessmentEngine {

    // Tier-3 intent-profile values, ms. [inferred] starting points, review-only
    // in Stage 1; clamped to the byte-derived buffer-seconds ceiling below.
    private const val FAST_INITIAL_MS = 2_000
    private const val FAST_REBUFFER_MS = 3_000
    private const val FAST_MIN_MS = 10_000
    private const val BAL_INITIAL_MS = 3_000
    private const val BAL_REBUFFER_MS = 5_000
    private const val BAL_MIN_MS = 20_000
    private const val STALL_INITIAL_MS = 5_000
    private const val STALL_REBUFFER_MS = 8_000
    private const val STALL_MIN_MS = 30_000

    // Stability -> suggested profile threshold. [inferred] initial value: a
    // median sub-window CoV above this reads as a link that dips enough to
    // reward deeper buffers.
    private const val STABILITY_STALL_COV = 0.25

    // Max-buffer recommendation bounds, seconds.
    private const val MAX_BUFFER_FLOOR_S = 10
    private const val MAX_BUFFER_CAP_S = 120

    suspend fun run(
        context: Context,
        activity: Activity?,
        settings: PlayerSettings,
        diagnostics: LastPlaybackDiagnostics,
        onSweepState: (String) -> Unit,
        onSweepPassAdded: (String) -> Unit,
        onSweepPassResult: (String, Double?) -> Unit
    ): AssessmentResult {
        val now = System.currentTimeMillis()

        // ── Queryable device facts ──────────────────────────────────────────
        val safeLimitMb = NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
        val warningLimitMb = NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context)
        val ramLabel = NuvioExoPlayerPerformanceHelper.getFriendlyRamLabel(context)
        val display = activity?.let { DisplayCapabilities.detect(it) }
            ?: DisplayCapabilities.Snapshot.Unknown
        val policy = DolbyVisionBaseLayerPolicy.resolve(context, DoviBridge.isLibraryLoaded)

        // ── Last-stream facts + bitrate (same derivation as the stream test) ─
        val streamUrl = diagnostics.streamUrl
        val headers = parseHeaders(diagnostics.headersJson)
        var estimatedBitrate: Long? = diagnostics.videoBitrate.takeIf { it > 0 }?.toLong()
        if (estimatedBitrate == null && !streamUrl.isNullOrBlank() && diagnostics.durationMs > 0) {
            val size = StreamSpeedTester.getStreamContentLength(streamUrl, headers)
            if (size > 0) {
                val durationSecs = diagnostics.durationMs / 1000.0
                if (durationSecs > 0) estimatedBitrate = ((size * 8.0) / durationSecs).toLong()
            }
        }
        val bitrateMbps = estimatedBitrate?.takeIf { it > 0 }?.let { it / 1_000_000.0 }

        // ── Tier 1: the shared sweep against the real stream ────────────────
        val sweep: StreamSweepEngine.SweepOutcome? = if (!streamUrl.isNullOrBlank()) {
            StreamSweepEngine.run(
                context = context,
                streamUrl = streamUrl,
                headers = headers,
                estimatedBitrate = estimatedBitrate,
                onState = onSweepState,
                onPassAdded = onSweepPassAdded,
                onPassResult = onSweepPassResult
            )
        } else {
            null
        }

        val items = mutableListOf<AssessmentItem>()
        fun s(resId: Int, vararg args: Any) = context.getString(resId, *args)
        val on = s(R.string.assessment_value_on)
        val off = s(R.string.assessment_value_off)

        val currentChunkLabel = chunkLabel(context, settings.parallelChunkSizeKb)
        val currentChunkMbForOverhead = (settings.parallelChunkSizeKb + 1023) / 1024
        val currentParallelLabel = if (settings.useParallelConnections) {
            s(R.string.assessment_current_parallel_on, settings.parallelConnectionCount, currentChunkLabel)
        } else {
            off
        }

        // ── Tier 1 rows ─────────────────────────────────────────────────────
        val rec = sweep?.recommendation
        if (sweep != null && sweep.errorText == null) {
            when (sweep.verdictKind) {
                StreamSweepEngine.VerdictKind.LEAVE_PARALLEL_OFF -> {
                    items += AssessmentItem(
                        key = "parallel",
                        title = s(R.string.assessment_item_parallel),
                        currentValue = currentParallelLabel,
                        recommendedValue = off,
                        grounds = s(
                            R.string.assessment_grounds_parallel_off,
                            "%.1f Mbps".format(sweep.baselineMbps)
                        ),
                        tier = AssessmentTier.MEASURED,
                        changeNeeded = settings.useParallelConnections
                    )
                }
                StreamSweepEngine.VerdictKind.RECOMMEND_CONFIG,
                StreamSweepEngine.VerdictKind.MARGINAL,
                StreamSweepEngine.VerdictKind.CANNOT_SUSTAIN,
                StreamSweepEngine.VerdictKind.FASTEST_NO_BITRATE -> {
                    if (rec != null) {
                        val groundsRes = when (sweep.verdictKind) {
                            StreamSweepEngine.VerdictKind.RECOMMEND_CONFIG ->
                                if (rec.fitsSafeBudget) R.string.assessment_grounds_parallel_rec
                                else R.string.assessment_grounds_parallel_rec_unsafe
                            StreamSweepEngine.VerdictKind.MARGINAL ->
                                R.string.assessment_grounds_parallel_marginal
                            StreamSweepEngine.VerdictKind.CANNOT_SUSTAIN ->
                                R.string.assessment_grounds_parallel_cannot
                            else -> R.string.assessment_grounds_parallel_fastest
                        }
                        val overhead = MemoryBudget.parallelOverheadMb(rec.connections, rec.chunkMb)
                        val status = MemoryBudget.getUsageStatus(overhead, safeLimitMb, warningLimitMb)
                        items += AssessmentItem(
                            key = "parallel",
                            title = s(R.string.assessment_item_parallel),
                            currentValue = currentParallelLabel,
                            recommendedValue = s(
                                R.string.assessment_current_parallel_on,
                                rec.connections,
                                s(R.string.assessment_unit_mb, rec.chunkMb)
                            ),
                            grounds = rec.bufferTrade?.let { trade ->
                                s(
                                    R.string.assessment_grounds_parallel_trade,
                                    "%.1f Mbps".format(rec.mbps),
                                    rec.connections,
                                    rec.chunkMb,
                                    trade.overConnections,
                                    trade.overChunkMb,
                                    trade.chosenBufferMb,
                                    trade.chosenBufferS,
                                    trade.overBufferMb,
                                    trade.overBufferS
                                )
                            } ?: s(
                                groundsRes,
                                "%.1f Mbps".format(rec.mbps),
                                rec.connections,
                                rec.chunkMb
                            ),
                            tier = AssessmentTier.MEASURED,
                            changeNeeded = !settings.useParallelConnections ||
                                settings.parallelConnectionCount != rec.connections ||
                                settings.parallelChunkSizeKb != rec.chunkMb * 1024,
                            memoryStatus = status
                        )
                        items += AssessmentItem(
                            key = "chunk",
                            title = s(R.string.assessment_item_chunk),
                            currentValue = currentChunkLabel,
                            recommendedValue = s(R.string.assessment_unit_mb, rec.chunkMb),
                            grounds = s(R.string.assessment_grounds_chunk),
                            tier = AssessmentTier.MEASURED,
                            changeNeeded = settings.parallelChunkSizeKb != rec.chunkMb * 1024
                        )
                    }
                }
                StreamSweepEngine.VerdictKind.NONE -> Unit
            }
        }

        // ── Tier 2: calculated from device facts ────────────────────────────
        val parallelRecommendedOn = rec != null && sweep.errorText == null
        val overheadForTargetMb = when {
            rec != null -> MemoryBudget.parallelOverheadMb(rec.connections, rec.chunkMb)
            sweep != null && sweep.verdictKind == StreamSweepEngine.VerdictKind.LEAVE_PARALLEL_OFF -> 0
            settings.useParallelConnections ->
                MemoryBudget.parallelOverheadMb(settings.parallelConnectionCount, currentChunkMbForOverhead)
            else -> 0
        }

        // The whole assessed feature set (buffers, parallel network, VOD
        // cache, DV pipeline) is the ExoPlayer path; under the MVP player
        // none of it takes effect, so say so before recommending any of it.
        val engineOk = settings.internalPlayerEngine == InternalPlayerEngine.EXOPLAYER ||
            settings.internalPlayerEngine == InternalPlayerEngine.AUTO
        val engineLabel = when (settings.internalPlayerEngine) {
            InternalPlayerEngine.EXOPLAYER -> s(R.string.assessment_value_engine_exo)
            InternalPlayerEngine.AUTO -> s(R.string.assessment_value_engine_auto)
            InternalPlayerEngine.MVP_PLAYER -> s(R.string.assessment_value_engine_mvp)
        }
        items += AssessmentItem(
            key = "engine",
            title = s(R.string.assessment_item_engine),
            currentValue = engineLabel,
            // When the engine is right, the row ENDORSES the current value
            // (green tick) instead of a vague no-change.
            recommendedValue = if (engineOk) engineLabel
            else s(R.string.assessment_value_engine_exo),
            grounds = if (engineOk) s(R.string.assessment_grounds_engine_ok)
            else s(R.string.assessment_grounds_engine_switch),
            tier = AssessmentTier.CALCULATED,
            changeNeeded = !engineOk
        )

        items += AssessmentItem(
            key = "network_master",
            title = s(R.string.assessment_item_network_master),
            currentValue = if (settings.parallelNetworkEnabled) on else off,
            recommendedValue = on,
            grounds = s(R.string.assessment_grounds_network_master),
            tier = AssessmentTier.CALCULATED,
            changeNeeded = !settings.parallelNetworkEnabled
        )
        items += AssessmentItem(
            key = "http2",
            title = s(R.string.assessment_item_http2),
            currentValue = if (settings.enableHttp2) on else off,
            recommendedValue = on,
            grounds = s(R.string.assessment_grounds_http2),
            tier = AssessmentTier.CALCULATED,
            changeNeeded = !settings.enableHttp2
        )

        val perfModeSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        items += AssessmentItem(
            key = "perf_mode",
            title = s(R.string.assessment_item_perf_mode),
            currentValue = if (settings.nuvioPerformanceModeEnabled) on else off,
            recommendedValue = if (perfModeSupported) on else off,
            grounds = if (perfModeSupported) {
                s(
                    R.string.assessment_grounds_perf_mode,
                    ramLabel,
                    safeLimitMb,
                    warningLimitMb,
                    PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
                )
            } else {
                s(R.string.assessment_grounds_perf_mode_unsupported)
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = settings.nuvioPerformanceModeEnabled != perfModeSupported
        )

        items += AssessmentItem(
            key = "buffer_master",
            title = s(R.string.assessment_item_buffer_master),
            currentValue = if (settings.bufferEngineEnabled) on else off,
            recommendedValue = on,
            grounds = s(R.string.assessment_grounds_buffer_master),
            tier = AssessmentTier.CALCULATED,
            changeNeeded = !settings.bufferEngineEnabled
        )
        items += AssessmentItem(
            key = "managed_budget",
            title = s(R.string.assessment_item_managed_budget),
            currentValue = if (settings.bufferBudgetManaged) on else off,
            recommendedValue = off,
            grounds = s(R.string.assessment_grounds_managed_budget),
            tier = AssessmentTier.CALCULATED,
            changeNeeded = settings.bufferBudgetManaged
        )

        // Largest 25 MB step where target + overhead stays <= the safe limit.
        val recTargetMb = (((safeLimitMb - overheadForTargetMb) / MemoryBudget.BUFFER_STEP_MB) *
            MemoryBudget.BUFFER_STEP_MB)
            .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
            .coerceAtMost(PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB)
        items += AssessmentItem(
            key = "target_buffer",
            title = s(R.string.assessment_item_target_buffer),
            currentValue = s(R.string.assessment_unit_mb, settings.bufferSettings.targetBufferSizeMb),
            recommendedValue = s(R.string.assessment_unit_mb, recTargetMb),
            grounds = buildString {
                append(
                    s(
                        R.string.assessment_grounds_target_buffer,
                        recTargetMb,
                        overheadForTargetMb,
                        recTargetMb + overheadForTargetMb,
                        safeLimitMb,
                        MemoryBudget.BUFFER_STEP_MB
                    )
                )
                // Acknowledge a working current config honestly instead of
                // implying it is broken: warning band = reduced headroom,
                // not failure; past the warning limit = genuine kill risk.
                val currentOverheadMb = if (settings.useParallelConnections) {
                    MemoryBudget.parallelOverheadMb(
                        settings.parallelConnectionCount, currentChunkMbForOverhead
                    )
                } else 0
                val currentSumMb = settings.bufferSettings.targetBufferSizeMb + currentOverheadMb
                if (currentSumMb > warningLimitMb) {
                    append('\n')
                    append(
                        s(
                            R.string.assessment_grounds_target_current_danger,
                            settings.bufferSettings.targetBufferSizeMb,
                            currentOverheadMb,
                            currentSumMb,
                            warningLimitMb
                        )
                    )
                } else if (currentSumMb > safeLimitMb) {
                    append('\n')
                    append(
                        s(
                            R.string.assessment_grounds_target_current_warning,
                            settings.bufferSettings.targetBufferSizeMb,
                            currentOverheadMb,
                            currentSumMb,
                            safeLimitMb,
                            warningLimitMb
                        )
                    )
                }
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = settings.bufferSettings.targetBufferSizeMb != recTargetMb,
            memoryStatus = MemoryBudget.getUsageStatus(
                recTargetMb + overheadForTargetMb, safeLimitMb, warningLimitMb
            )
        )

        val standardSliderMaxMb = MemoryBudget.maxBufferMb(overheadForTargetMb)
        val allowLargeNeeded = recTargetMb > standardSliderMaxMb
        items += AssessmentItem(
            key = "allow_large",
            title = s(R.string.assessment_item_allow_large),
            currentValue = if (settings.allowLargeTargetBuffer) on else off,
            recommendedValue = if (allowLargeNeeded) on else off,
            grounds = if (allowLargeNeeded) {
                s(R.string.assessment_grounds_allow_large_on, recTargetMb)
            } else {
                s(R.string.assessment_grounds_allow_large_off)
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = settings.allowLargeTargetBuffer != allowLargeNeeded
        )

        // Max buffer: byte-derived ceiling - seconds the recommended target can
        // actually hold at this title's bitrate.
        val capacityS = bitrateMbps?.takeIf { it > 0 }?.let { (recTargetMb * 8.0 / it).toInt() }
        val recMaxS = (capacityS ?: MAX_BUFFER_CAP_S).coerceIn(MAX_BUFFER_FLOOR_S, MAX_BUFFER_CAP_S)
        items += AssessmentItem(
            key = "max_buffer",
            title = s(R.string.assessment_item_max_buffer),
            currentValue = s(R.string.assessment_value_seconds, settings.bufferSettings.maxBufferMs / 1000),
            recommendedValue = s(R.string.assessment_value_seconds, recMaxS),
            grounds = if (capacityS != null && bitrateMbps != null) {
                buildString {
                    append(
                        s(
                            R.string.assessment_grounds_max_buffer,
                            recTargetMb,
                            capacityS,
                            "%.1f Mbps".format(bitrateMbps)
                        )
                    )
                    val currentTargetMb = settings.bufferSettings.targetBufferSizeMb
                    if (currentTargetMb != recTargetMb) {
                        append('\n')
                        append(
                            s(
                                R.string.assessment_grounds_max_buffer_current,
                                currentTargetMb,
                                (currentTargetMb * 8.0 / bitrateMbps).toInt()
                            )
                        )
                    }
                }
            } else {
                s(R.string.assessment_grounds_max_buffer_nobitrate, MAX_BUFFER_CAP_S)
            },
            tier = AssessmentTier.CALCULATED,
            changeNeeded = settings.bufferSettings.maxBufferMs / 1000 != recMaxS
        )

        // VOD cache: resolved figure from the SAME formula the runtime uses.
        val freeSpaceBytes = context.cacheDir.usableSpace
        val autoBytes = VodCacheSizing.resolveMaxBytes(
            freeSpaceBytes = freeSpaceBytes,
            mode = VodCacheSizeMode.AUTO,
            manualSizeMb = settings.vodCacheSizeMb
        )
        val vodCurrent = when {
            !settings.vodCacheEnabled -> off
            settings.vodCacheSizeMode == VodCacheSizeMode.AUTO -> s(R.string.assessment_value_auto)
            else -> s(R.string.assessment_unit_mb, settings.vodCacheSizeMb)
        }
        if (autoBytes > 0L) {
            items += AssessmentItem(
                key = "vod_cache",
                title = s(R.string.assessment_item_vod_cache),
                currentValue = vodCurrent,
                recommendedValue = s(R.string.assessment_value_on_auto, formatBytes(autoBytes)),
                grounds = s(R.string.assessment_grounds_vod_auto, formatBytes(autoBytes)),
                tier = AssessmentTier.CALCULATED,
                changeNeeded = !settings.vodCacheEnabled ||
                    settings.vodCacheSizeMode != VodCacheSizeMode.AUTO
            )
        } else {
            items += AssessmentItem(
                key = "vod_cache",
                title = s(R.string.assessment_item_vod_cache),
                currentValue = vodCurrent,
                recommendedValue = off,
                grounds = s(R.string.assessment_grounds_vod_off),
                tier = AssessmentTier.CALCULATED,
                changeNeeded = settings.vodCacheEnabled
            )
        }

        // Force AC-3 transcode vs the actual audio route. The audio-decode
        // scope-out stands (no silent AVR probe exists); the ROUTE TYPE is
        // silently queryable, and on an HDMI chain this setting only turns
        // lossless passthrough into lossy AC-3.
        val audioRoute = AudioOutputRouteDetector.detect(context)
        val audioRouteType = audioRoute?.key
            ?.removePrefix("type:")?.substringBefore("|")
            ?.let { raw ->
                when (raw) {
                    "hdmi" -> "HDMI"
                    "hdmi_arc" -> "HDMI ARC"
                    "hdmi_earc" -> "HDMI eARC"
                    else -> raw
                }
            }
        if (audioRoute != null && audioRoute.key.startsWith("type:hdmi")) {
            items += AssessmentItem(
                key = "force_ac3",
                title = s(R.string.assessment_item_ac3),
                currentValue = if (settings.forceOpticalPassthrough) on else off,
                recommendedValue = off,
                grounds = s(
                    R.string.assessment_grounds_ac3_hdmi,
                    audioRouteType ?: "HDMI",
                    audioRoute.label
                ),
                tier = AssessmentTier.CALCULATED,
                changeNeeded = settings.forceOpticalPassthrough
            )
        } else {
            items += AssessmentItem(
                key = "force_ac3",
                title = s(R.string.assessment_item_ac3),
                currentValue = if (settings.forceOpticalPassthrough) on else off,
                recommendedValue = s(R.string.assessment_value_leave_as_is),
                grounds = s(R.string.assessment_grounds_ac3_unknown),
                tier = AssessmentTier.VERIFY,
                changeNeeded = false
            )
        }

        // AFR + resolution: honest per display capability; VERIFY when the
        // display could not be inspected (no Activity, or API unsupported).
        if (display.apiSupported) {
            val ratesLabel = ratesAtCurrentResolution(display)
            if (display.supportsFrameRateSwitching) {
                if (settings.frameRateMatchingMode == FrameRateMatchingMode.OFF) {
                    items += AssessmentItem(
                        key = "afr",
                        title = s(R.string.assessment_item_afr),
                        currentValue = off,
                        recommendedValue = s(R.string.assessment_value_afr_start_stop),
                        grounds = s(R.string.assessment_grounds_afr_on, ratesLabel),
                        tier = AssessmentTier.CALCULATED,
                        changeNeeded = true
                    )
                } else {
                    items += AssessmentItem(
                        key = "afr",
                        title = s(R.string.assessment_item_afr),
                        currentValue = if (settings.frameRateMatchingMode == FrameRateMatchingMode.START_STOP) {
                            s(R.string.assessment_value_afr_start_stop)
                        } else {
                            s(R.string.assessment_value_afr_start)
                        },
                        recommendedValue = s(R.string.assessment_value_your_call),
                        grounds = s(R.string.assessment_grounds_afr_already),
                        tier = AssessmentTier.CALCULATED,
                        changeNeeded = false
                    )
                }
            } else {
                items += AssessmentItem(
                    key = "afr",
                    title = s(R.string.assessment_item_afr),
                    currentValue = afrModeLabel(context, settings.frameRateMatchingMode),
                    recommendedValue = off,
                    grounds = s(R.string.assessment_grounds_afr_single),
                    tier = AssessmentTier.CALCULATED,
                    changeNeeded = settings.frameRateMatchingMode != FrameRateMatchingMode.OFF
                )
            }
            // Resolution matching is a preference, not a derivable fact: it
            // decides WHICH scaler upscales sub-native content (the panel's or
            // this box's), and no API says which looks better. State the trade
            // and leave the choice; never write it.
            items += AssessmentItem(
                key = "res_match",
                title = s(R.string.assessment_item_res_match),
                currentValue = if (settings.resolutionMatchingEnabled) on else off,
                recommendedValue = if (display.supportsResolutionSwitching) {
                    s(R.string.assessment_value_your_call)
                } else {
                    s(R.string.assessment_value_leave_as_is)
                },
                grounds = if (display.supportsResolutionSwitching) {
                    s(R.string.assessment_grounds_res_choice, resolutionsLabel(display))
                } else {
                    s(R.string.assessment_grounds_res_inert)
                },
                tier = AssessmentTier.CALCULATED,
                changeNeeded = false
            )
        } else {
            items += AssessmentItem(
                key = "afr",
                title = s(R.string.assessment_item_afr),
                currentValue = afrModeLabel(context, settings.frameRateMatchingMode),
                recommendedValue = s(R.string.assessment_value_leave_as_is),
                grounds = s(R.string.assessment_grounds_display_unknown),
                tier = AssessmentTier.VERIFY,
                changeNeeded = false
            )
            items += AssessmentItem(
                key = "res_match",
                title = s(R.string.assessment_item_res_match),
                currentValue = if (settings.resolutionMatchingEnabled) on else off,
                recommendedValue = s(R.string.assessment_value_leave_as_is),
                grounds = s(R.string.assessment_grounds_display_unknown),
                tier = AssessmentTier.VERIFY,
                changeNeeded = false
            )
        }

        // dv7 row: CALCULATED when the display's HDR types are readable,
        // VERIFY otherwise (the policy still acts, but the deciding fact is
        // not queryable - say so instead of pretending). dv5 and strip are
        // permanent VERIFY advisories and never machine-applied (nt6, see
        // class doc).
        val dvTier = if (policy.hdrCapsKnown) AssessmentTier.CALCULATED else AssessmentTier.VERIFY
        val dv7DecisionLabel = when (policy.decision) {
            DolbyVisionBaseLayerPolicy.Decision.NATIVE_DV7 -> s(R.string.assessment_dv7_native)
            DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81 -> s(R.string.assessment_dv7_convert)
            DolbyVisionBaseLayerPolicy.Decision.STRIP_TO_HDR10 -> s(R.string.assessment_dv7_strip)
            DolbyVisionBaseLayerPolicy.Decision.STRIP_BEST_EFFORT -> s(R.string.assessment_dv7_strip_besteffort)
            DolbyVisionBaseLayerPolicy.Decision.STRIP_AND_TONEMAP -> s(R.string.assessment_dv7_tonemap)
        }
        items += AssessmentItem(
            key = "dv7_mode",
            title = s(R.string.assessment_item_dv7),
            currentValue = dv7ModeLabel(context, settings.dv7HandlingMode),
            recommendedValue = s(R.string.assessment_value_auto),
            grounds = if (policy.hdrCapsKnown) {
                s(R.string.assessment_grounds_dv7_auto, dv7DecisionLabel)
            } else {
                s(R.string.assessment_grounds_dv7_unknown)
            },
            tier = dvTier,
            changeNeeded = settings.dv7HandlingMode != Dv7HandlingMode.AUTO
        )

        val dv5RecommendOn = !policy.displayDv && policy.bridgeReady && policy.codecSupportsDvheSt
        items += AssessmentItem(
            key = "dv5",
            title = s(R.string.assessment_item_dv5),
            currentValue = if (settings.dv5ToDv81Enabled) on else off,
            recommendedValue = if (dv5RecommendOn) on else off,
            grounds = when {
                policy.displayDv -> s(R.string.assessment_grounds_dv5_off_display)
                dv5RecommendOn -> s(R.string.assessment_grounds_dv5_on)
                else -> s(R.string.assessment_grounds_dv5_off_nopath)
            },
            tier = AssessmentTier.VERIFY,
            changeNeeded = settings.dv5ToDv81Enabled != dv5RecommendOn
        )

        val stripRecommendOn = policy.hdrCapsKnown && !policy.displayHdr10Plus
        items += AssessmentItem(
            key = "strip_hdr10plus",
            title = s(R.string.assessment_item_strip_hdr10plus),
            currentValue = if (settings.stripHdr10PlusSei) on else off,
            recommendedValue = if (stripRecommendOn) on else off,
            grounds = when {
                !policy.hdrCapsKnown -> s(R.string.assessment_grounds_dv7_unknown)
                stripRecommendOn -> s(R.string.assessment_grounds_strip_on)
                else -> s(R.string.assessment_grounds_strip_off)
            },
            tier = AssessmentTier.VERIFY,
            changeNeeded = settings.stripHdr10PlusSei != stripRecommendOn
        )

        // ── Tier 4: not queryable at all - verify yourself ──────────────────
        items += AssessmentItem(
            key = "tunneling",
            title = s(R.string.assessment_item_tunneling),
            currentValue = if (settings.tunnelingEnabled) on else off,
            recommendedValue = off,
            grounds = s(R.string.assessment_grounds_tunneling),
            tier = AssessmentTier.VERIFY,
            changeNeeded = settings.tunnelingEnabled
        )

        // ── Tier 3: intent profiles (user chooses; stability only suggests) ─
        val maxMinMs = recMaxS * 1000
        fun profile(kind: ProfileKind, titleRes: Int, subRes: Int, i: Int, r: Int, m: Int): IntentProfileOption {
            val minMs = m.coerceAtMost(maxMinMs)
            return IntentProfileOption(
                kind = kind,
                title = s(titleRes),
                subtitle = s(subRes, i / 1000),
                initialBufferMs = i,
                rebufferMs = r,
                minBufferMs = minMs,
                minCappedFromMs = m.takeIf { minMs < it }
            )
        }
        val profiles = listOf(
            profile(
                ProfileKind.FAST_START,
                R.string.assessment_profile_fast_title,
                R.string.assessment_profile_fast_sub,
                FAST_INITIAL_MS, FAST_REBUFFER_MS, FAST_MIN_MS
            ),
            profile(
                ProfileKind.BALANCED,
                R.string.assessment_profile_balanced_title,
                R.string.assessment_profile_balanced_sub,
                BAL_INITIAL_MS, BAL_REBUFFER_MS, BAL_MIN_MS
            ),
            profile(
                ProfileKind.STALL_RESISTANT,
                R.string.assessment_profile_stall_title,
                R.string.assessment_profile_stall_sub,
                STALL_INITIAL_MS, STALL_REBUFFER_MS, STALL_MIN_MS
            )
        )
        val suggestedProfile = sweep?.stabilityCoV?.let { cov ->
            if (cov > STABILITY_STALL_COV) ProfileKind.STALL_RESISTANT else ProfileKind.BALANCED
        }

        // ── Header facts ────────────────────────────────────────────────────
        val displaySummary = if (display.apiSupported) {
            val res = currentResolutionLabel(display)
            val rates = ratesAtCurrentResolution(display)
            listOfNotNull(res, rates.takeIf { it.isNotBlank() }?.plus(" Hz"))
                .joinToString(" \u00b7 ")
                .ifBlank { null }
        } else {
            null
        }
        val header = AssessmentHeaderFacts(
            deviceRamLabel = ramLabel,
            safeLimitMb = safeLimitMb,
            warningLimitMb = warningLimitMb,
            displaySummary = displaySummary,
            streamLabel = diagnostics.filename ?: diagnostics.host.takeIf { it.isNotBlank() },
            streamBitrateMbps = bitrateMbps,
            streamDvProfile = diagnostics.dvSourceProfile,
            streamHdrType = diagnostics.videoHdrType
        )

        // ── Machine-actionable plan: mirrors the CALCULATED/MEASURED rows
        // above, null when no change or the row is VERIFY. The Internal
        // Engine row stays advisory (switching players is not a knob).
        val afrPlan = when {
            !display.apiSupported -> null
            display.supportsFrameRateSwitching ->
                FrameRateMatchingMode.START_STOP.takeIf {
                    settings.frameRateMatchingMode == FrameRateMatchingMode.OFF
                }
            else -> FrameRateMatchingMode.OFF.takeIf {
                settings.frameRateMatchingMode != FrameRateMatchingMode.OFF
            }
        }
        val plan = AssessmentApplyPlan(
            nuvioPerformanceModeEnabled = perfModeSupported.takeIf {
                settings.nuvioPerformanceModeEnabled != perfModeSupported
            },
            bufferEngineEnabled = true.takeIf { !settings.bufferEngineEnabled },
            parallelNetworkEnabled = true.takeIf { !settings.parallelNetworkEnabled },
            bufferBudgetManaged = false.takeIf { settings.bufferBudgetManaged },
            allowLargeTargetBuffer = allowLargeNeeded.takeIf {
                settings.allowLargeTargetBuffer != allowLargeNeeded
            },
            targetBufferSizeMb = recTargetMb.takeIf {
                settings.bufferSettings.targetBufferSizeMb != recTargetMb
            },
            // Never WRITE a max-buffer value the engine couldn't ground: with
            // no title bitrate the ceiling is the generic fallback, so the row
            // stays display-only (its grounds already say why).
            maxBufferMs = (recMaxS * 1000).takeIf {
                capacityS != null && settings.bufferSettings.maxBufferMs / 1000 != recMaxS
            },
            useParallelConnections = when {
                sweep == null || sweep.errorText != null -> null
                sweep.verdictKind == StreamSweepEngine.VerdictKind.LEAVE_PARALLEL_OFF ->
                    false.takeIf { settings.useParallelConnections }
                rec != null -> true.takeIf { !settings.useParallelConnections }
                else -> null
            },
            parallelConnectionCount = rec?.connections?.takeIf {
                sweep?.errorText == null && settings.parallelConnectionCount != it
            },
            parallelChunkSizeKb = rec?.let { it.chunkMb * 1024 }?.takeIf {
                sweep?.errorText == null && settings.parallelChunkSizeKb != it
            },
            enableHttp2 = true.takeIf { !settings.enableHttp2 },
            vodCacheEnabled = if (autoBytes > 0L) {
                true.takeIf { !settings.vodCacheEnabled }
            } else {
                false.takeIf { settings.vodCacheEnabled }
            },
            vodCacheSizeMode = VodCacheSizeMode.AUTO.takeIf {
                autoBytes > 0L && settings.vodCacheSizeMode != VodCacheSizeMode.AUTO
            },
            frameRateMatchingMode = afrPlan,
            // Preference row: never written (see the res_match item above).
            resolutionMatchingEnabled = null,
            dv7HandlingMode = Dv7HandlingMode.AUTO.takeIf {
                policy.hdrCapsKnown && settings.dv7HandlingMode != Dv7HandlingMode.AUTO
            },
            // nt6: advisory-only rows - never machine-applied (see class doc).
            dv5ToDv81Enabled = null,
            stripHdr10PlusSei = null,
            forceOpticalPassthrough = false.takeIf {
                audioRoute != null && audioRoute.key.startsWith("type:hdmi") &&
                    settings.forceOpticalPassthrough
            }
        )

        return AssessmentResult(
            timestampMs = now,
            header = header,
            sweepRan = sweep != null,
            sweepVerdictText = sweep?.verdictText,
            items = items.toList(),
            profiles = profiles,
            suggestedProfile = suggestedProfile,
            stabilityCoV = sweep?.stabilityCoV,
            stabilityPassCount = sweep?.stabilityPassCount ?: 0,
            applyPlan = plan,
            errorText = sweep?.errorText
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun parseHeaders(headersJson: String?): Map<String, String> {
        if (headersJson.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(headersJson)
            val map = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.getString(key)
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun chunkLabel(context: Context, chunkKb: Int): String =
        if (chunkKb >= 1024 && chunkKb % 1024 == 0) {
            context.getString(R.string.assessment_unit_mb, chunkKb / 1024)
        } else {
            context.getString(R.string.assessment_unit_kb, chunkKb)
        }

    private fun afrModeLabel(context: Context, mode: FrameRateMatchingMode): String = when (mode) {
        FrameRateMatchingMode.OFF -> context.getString(R.string.assessment_value_off)
        FrameRateMatchingMode.START -> context.getString(R.string.assessment_value_afr_start)
        FrameRateMatchingMode.START_STOP -> context.getString(R.string.assessment_value_afr_start_stop)
    }

    private fun dv7ModeLabel(context: Context, mode: Dv7HandlingMode): String = when (mode) {
        Dv7HandlingMode.AUTO -> context.getString(R.string.assessment_value_auto)
        else -> mode.name
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) "%.1f GB".format(gb) else "${bytes / (1024L * 1024L)} MB"
    }

    private fun currentResolutionLabel(display: DisplayCapabilities.Snapshot): String? {
        val current = display.supportedModes.firstOrNull { it.modeId == display.currentModeId }
            ?: return null
        return "${current.physicalWidth}x${current.physicalHeight}"
    }

    private fun ratesAtCurrentResolution(display: DisplayCapabilities.Snapshot): String {
        val current = display.supportedModes.firstOrNull { it.modeId == display.currentModeId }
        return display.supportedModes
            .filter {
                current == null ||
                    (it.physicalWidth == current.physicalWidth &&
                        it.physicalHeight == current.physicalHeight)
            }
            .map { it.refreshRate.toDouble() }
            .distinct()
            .sorted()
            .joinToString("/") { rate ->
                if (rate == rate.toInt().toDouble()) {
                    rate.toInt().toString()
                } else {
                    "%.3f".format(rate).trimEnd('0').trimEnd('.')
                }
            }
    }

    private fun resolutionsLabel(display: DisplayCapabilities.Snapshot): String =
        display.supportedModes
            .map { it.physicalWidth to it.physicalHeight }
            .distinct()
            .sortedByDescending { it.first.toLong() * it.second }
            .joinToString("/") { "${it.first}x${it.second}" }
}
