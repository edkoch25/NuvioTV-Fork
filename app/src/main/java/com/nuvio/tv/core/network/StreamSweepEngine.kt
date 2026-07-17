package com.nuvio.tv.core.network

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.R
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.tv.ui.screens.settings.MemoryUsageStatus

/**
 * The adaptive greedy stream sweep, extracted behaviour-identical from
 * NetworkSettingsScreen.runStreamDiagnostics so the Advanced-settings card and
 * the Device Assessment share ONE implementation (single source of truth —
 * the same rationale as MemoryBudget.prefetchDepthChunks). Labels, stop
 * rules, memory gating and verdict strings are unchanged; only the Compose
 * state mutations became callbacks and the verdict is additionally returned
 * in structured form.
 *
 * Threading: run() executes on the caller's dispatcher and suspends into IO
 * inside StreamSpeedTester for the transfers, exactly as the inline original
 * did from its scope.launch. Callbacks therefore fire on the caller's context.
 *
 * Algorithm (Item 1(b)) — unchanged:
 *   Stage 1  Baseline (single connection). If it already meets the target
 *            (2x the last title's average bitrate) the verdict is "leave
 *            parallel off" and the sweep ends - parallel connections only
 *            help when one connection cannot feed the title.
 *   Stage 2  Chunk climb at 2 connections up the 8/16/32/64/128 ladder.
 *   Stage 3  Connection climb at the best chunk: 3 -> 4 -> 8 -> 16 (counts
 *            above 4 need Nuvio Performance Mode at runtime; rows are
 *            labelled).
 *   Stage 4  Neighbour refinement around the best config (chunk up, conn
 *            up, chunk down).
 *   Stage 5  Below-target cross-check: if the target is still unmet with
 *            pass budget left, probe untested 3- and 4-connection combos
 *            against the two strongest chunk sizes measured this session,
 *            cheapest first, stopping the moment a pass meets the target.
 * Stop rules are asymmetric around the target. While the target is UNMET,
 * every Mbps matters: climbs continue on any gain and get one grace step
 * through a single regression (single-sample passes are noisy - one dip
 * is not a wall), stopping only when a second consecutive rung fails to
 * recover. Once the target is MET, the economy rule applies: continue or
 * adopt only on >=10% gains. Sustained 429 rate-limiting on debrid CDNs
 * shows up as consecutive collapses and still stops the climb.
 * Every candidate is gated against the device RAM tier first (the
 * safe/warning native limits, matching the tester's native-memory
 * allocations); configs beyond the warning limit never run, rows between
 * safe and warning are marked. Hard cap of 12 parallel passes; once the
 * target is met at most 2 further passes run (to show headroom). The
 * winner is a recommendation, not a provable optimum.
 */
@UnstableApi
object StreamSweepEngine {

    data class MeasuredPass(val connections: Int, val chunkMb: Int, val mbps: Double)

    enum class VerdictKind {
        /** Baseline alone met the 2x target — parallel connections stay off. */
        LEAVE_PARALLEL_OFF,
        /** Cheapest sufficient configuration found (safe-budget preferred). */
        RECOMMEND_CONFIG,
        /** Nothing met 2x, but the fastest pass beats the title's own bitrate. */
        MARGINAL,
        /** The fastest pass is below the title's own bitrate. */
        CANNOT_SUSTAIN,
        /** No bitrate known — fastest measured configuration reported. */
        FASTEST_NO_BITRATE,
        /** Sweep ended without a verdict (error, or no successful parallel pass). */
        NONE
    }

    data class Recommendation(
        val connections: Int,
        val chunkMb: Int,
        val mbps: Double,
        val meetsTarget: Boolean,
        val fitsSafeBudget: Boolean
    )

    data class SweepOutcome(
        val verdictKind: VerdictKind,
        val verdictText: String?,
        val recommendation: Recommendation?,
        val baselineMbps: Double,
        val targetMbps: Double?,
        val measured: List<MeasuredPass>,
        val errorText: String?
    )

    suspend fun run(
        context: Context,
        streamUrl: String,
        headers: Map<String, String>,
        estimatedBitrate: Long?,
        onState: (String) -> Unit,
        onPassAdded: (String) -> Unit,
        onPassResult: (String, Double) -> Unit
    ): SweepOutcome {
        val chunkLadderMb = listOf(8, 16, 32, 64, 128)
        val maxChunkMb = com.nuvio.tv.data.local.PlayerSettings.MAX_PARALLEL_CHUNK_SIZE_KB / 1024
        val minChunkMb = (com.nuvio.tv.data.local.PlayerSettings.MIN_PARALLEL_CHUNK_SIZE_KB + 1023) / 1024
        val connLadder = listOf(2, 3, 4, 8, 16)
        val standardConnLimit = com.nuvio.tv.data.local.PlayerSettings.MAX_PARALLEL_CONNECTION_COUNT
        val safeLimitMb =
            com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper.getSafeNativeMemoryLimitMb(context)
        val warningLimitMb =
            com.nuvio.tv.ui.screens.player.NuvioExoPlayerPerformanceHelper.getWarningNativeMemoryLimitMb(context)
        val targetMbps = estimatedBitrate?.takeIf { it > 0 }?.let { it * 2.0 / 1_000_000.0 }
        val ranConfigs = mutableSetOf<Pair<Int, Int>>() // (connections, chunkMb)
        var parallelPasses = 0
        var passesSinceSufficient = -1 // -1 = target not yet met
        val maxParallelPasses = 12

        fun overheadMb(connections: Int, chunkMb: Int) =
            MemoryBudget.parallelOverheadMb(connections, chunkMb)

        fun allowed(connections: Int, chunkMb: Int) =
            chunkMb in minChunkMb..maxChunkMb && overheadMb(connections, chunkMb) <= warningLimitMb

        fun mayContinue() =
            parallelPasses < maxParallelPasses && passesSinceSufficient < 2

        // Target unmet -> continue on any gain; target met -> require >=10%.
        fun belowTarget() = targetMbps != null && passesSinceSufficient < 0

        fun continueBar() = if (belowTarget()) 1.0 else 1.10

        fun rowLabel(connections: Int, chunkMb: Int): String {
            var label = context.getString(R.string.stream_test_label_parallel_dyn, connections, chunkMb)
            val status = MemoryBudget.getUsageStatus(overheadMb(connections, chunkMb), safeLimitMb, warningLimitMb)
            if (status == MemoryUsageStatus.WARNING) {
                label += context.getString(R.string.stream_test_row_warning_suffix)
            }
            if (connections > standardConnLimit) {
                label += context.getString(R.string.stream_test_row_pm_suffix)
            }
            return label
        }

        val measured = mutableListOf<MeasuredPass>()

        suspend fun measure(connections: Int, chunkMb: Int): Double {
            val label = rowLabel(connections, chunkMb)
            ranConfigs += connections to chunkMb
            parallelPasses += 1
            onState(label)
            onPassAdded(label)
            val mbps = StreamSpeedTester.runParallelChunkTest(
                streamUrl,
                headers,
                chunkMb * 1024L * 1024L,
                connections
            )
            onPassResult(label, mbps)
            if (mbps > 0) measured += MeasuredPass(connections, chunkMb, mbps)
            if (targetMbps != null && mbps >= targetMbps && passesSinceSufficient < 0) {
                passesSinceSufficient = 0
            } else if (passesSinceSufficient >= 0) {
                passesSinceSufficient += 1
            }
            return mbps
        }

        fun withPmSuffix(text: String, connections: Int): String =
            if (connections > standardConnLimit) {
                text + context.getString(R.string.stream_test_verdict_pm_suffix)
            } else text

        var baselineMbps = 0.0

        try {
            // Stage 1 - baseline.
            val baselineLabel = context.getString(R.string.stream_test_label_baseline)
            onState(baselineLabel)
            onPassAdded(baselineLabel)
            val baseline = StreamSpeedTester.runBaselineTest(
                streamUrl,
                headers
            )
            onPassResult(baselineLabel, baseline)
            baselineMbps = baseline

            if (baseline <= 0.0) {
                return SweepOutcome(
                    verdictKind = VerdictKind.NONE,
                    verdictText = null,
                    recommendation = null,
                    baselineMbps = baseline,
                    targetMbps = targetMbps,
                    measured = measured.toList(),
                    errorText = context.getString(R.string.stream_test_error_connection)
                )
            }

            if (targetMbps != null && baseline >= targetMbps) {
                return SweepOutcome(
                    verdictKind = VerdictKind.LEAVE_PARALLEL_OFF,
                    verdictText = context.getString(
                        R.string.stream_test_verdict_leave_off,
                        "%.1f Mbps".format(baseline)
                    ),
                    recommendation = null,
                    baselineMbps = baseline,
                    targetMbps = targetMbps,
                    measured = measured.toList(),
                    errorText = null
                )
            }

            // Stage 2 - chunk climb at 2 connections.
            var bestConnections = 2
            var bestChunkMb = -1
            var bestMbps = -1.0
            var prevMbps = -1.0
            var grace = true // one pass through a single regression while below target
            for (chunkMb in chunkLadderMb) {
                if (!mayContinue() || !allowed(2, chunkMb)) break
                val mbps = measure(2, chunkMb)
                if (mbps > bestMbps) { bestMbps = mbps; bestChunkMb = chunkMb }
                if (prevMbps > 0 && mbps < prevMbps * continueBar()) {
                    if (belowTarget() && grace && mbps < prevMbps) {
                        grace = false
                        prevMbps = mbps
                        continue
                    }
                    break
                }
                if (mbps > prevMbps) grace = true
                prevMbps = mbps
            }

            if (bestChunkMb <= 0 || bestMbps <= 0.0) {
                return SweepOutcome(
                    verdictKind = VerdictKind.NONE,
                    verdictText = null,
                    recommendation = null,
                    baselineMbps = baseline,
                    targetMbps = targetMbps,
                    measured = measured.toList(),
                    errorText = null
                )
            }

            // Stage 3 - connection climb at the best chunk.
            prevMbps = bestMbps
            grace = true
            for (connections in connLadder) {
                if (connections <= 2) continue
                if (!mayContinue() || !allowed(connections, bestChunkMb)) break
                if (connections to bestChunkMb in ranConfigs) continue
                val mbps = measure(connections, bestChunkMb)
                if (mbps > bestMbps) { bestMbps = mbps; bestConnections = connections }
                if (mbps < prevMbps * continueBar()) {
                    if (belowTarget() && grace && mbps < prevMbps) {
                        grace = false
                        prevMbps = mbps
                        continue
                    }
                    break
                }
                if (mbps > prevMbps) grace = true
                prevMbps = mbps
            }

            // Stage 4 - neighbour refinement around the best config.
            var improved = true
            while (improved && mayContinue()) {
                improved = false
                val chunkUp = chunkLadderMb.firstOrNull { it > bestChunkMb }
                val chunkDown = chunkLadderMb.lastOrNull { it < bestChunkMb }
                val connUp = connLadder.firstOrNull { it > bestConnections }
                val neighbours = listOfNotNull(
                    chunkUp?.let { bestConnections to it },
                    connUp?.let { it to bestChunkMb },
                    chunkDown?.let { bestConnections to it }
                )
                for ((connections, chunkMb) in neighbours) {
                    if (!mayContinue()) break
                    if (connections to chunkMb in ranConfigs || !allowed(connections, chunkMb)) continue
                    val mbps = measure(connections, chunkMb)
                    if (mbps >= bestMbps * continueBar() && mbps > bestMbps) {
                        bestMbps = mbps
                        bestConnections = connections
                        bestChunkMb = chunkMb
                        improved = true
                        break
                    }
                }
            }

            // Stage 5 - below-target cross-check. Coordinate ascent can
            // miss cross combinations (e.g. 3/32, 4/16) whose path runs
            // through a non-improving intermediate. When the verdict would
            // otherwise be marginal/cannot-sustain with budget unspent,
            // spend it on the untested standard-count combos against the
            // two strongest chunks measured this session.
            if (belowTarget()) {
                val topChunks = measured
                    .sortedByDescending { it.mbps }
                    .map { it.chunkMb }
                    .distinct()
                    .take(2)
                val crossConfigs = topChunks
                    .flatMap { chunk -> listOf(3 to chunk, 4 to chunk) }
                    .filter { it !in ranConfigs && allowed(it.first, it.second) }
                    .sortedBy { overheadMb(it.first, it.second) }
                for ((connections, chunkMb) in crossConfigs) {
                    if (!mayContinue()) break
                    val mbps = measure(connections, chunkMb)
                    if (mbps > bestMbps) {
                        bestMbps = mbps
                        bestConnections = connections
                        bestChunkMb = chunkMb
                    }
                    if (targetMbps != null && mbps >= targetMbps) break
                }
            }

            // Verdict: cheapest config that BOTH meets the 2x target AND fits the
            // safe native-memory budget, so the tool never recommends a configuration
            // the memory-usage indicator would flag. If none of the sufficient configs
            // fit the safe budget, fall back to the cheapest sufficient one regardless
            // (a working recommendation beats none on a very memory-constrained device).
            if (targetMbps != null) {
                val sufficient = measured.filter { it.mbps >= targetMbps }
                val sufficientAndSafe = sufficient.filter {
                    overheadMb(it.connections, it.chunkMb) <= safeLimitMb
                }
                val cheapest = (sufficientAndSafe.ifEmpty { sufficient })
                    .minByOrNull { overheadMb(it.connections, it.chunkMb) }
                if (cheapest != null) {
                    return SweepOutcome(
                        verdictKind = VerdictKind.RECOMMEND_CONFIG,
                        verdictText = withPmSuffix(
                            context.getString(
                                R.string.stream_test_verdict_recommend,
                                cheapest.connections,
                                cheapest.chunkMb,
                                "%.1f Mbps".format(cheapest.mbps)
                            ),
                            cheapest.connections
                        ),
                        recommendation = Recommendation(
                            connections = cheapest.connections,
                            chunkMb = cheapest.chunkMb,
                            mbps = cheapest.mbps,
                            meetsTarget = true,
                            fitsSafeBudget = overheadMb(cheapest.connections, cheapest.chunkMb) <= safeLimitMb
                        ),
                        baselineMbps = baseline,
                        targetMbps = targetMbps,
                        measured = measured.toList(),
                        errorText = null
                    )
                }
                val fastest = measured.maxByOrNull { it.mbps }
                if (fastest != null) {
                    // Below 2x is not the same as unplayable: above the
                    // title's own bitrate playback should work with thin
                    // headroom; below it the stream cannot be sustained.
                    val bitrateMbps = targetMbps / 2.0
                    val marginal = fastest.mbps >= bitrateMbps
                    val resId = if (marginal) {
                        R.string.stream_test_verdict_marginal
                    } else {
                        R.string.stream_test_verdict_cannot_sustain
                    }
                    return SweepOutcome(
                        verdictKind = if (marginal) VerdictKind.MARGINAL else VerdictKind.CANNOT_SUSTAIN,
                        verdictText = withPmSuffix(
                            context.getString(
                                resId,
                                fastest.connections,
                                fastest.chunkMb,
                                "%.1f Mbps".format(fastest.mbps),
                                "%.1f Mbps".format(bitrateMbps)
                            ),
                            fastest.connections
                        ),
                        recommendation = Recommendation(
                            connections = fastest.connections,
                            chunkMb = fastest.chunkMb,
                            mbps = fastest.mbps,
                            meetsTarget = false,
                            fitsSafeBudget = overheadMb(fastest.connections, fastest.chunkMb) <= safeLimitMb
                        ),
                        baselineMbps = baseline,
                        targetMbps = targetMbps,
                        measured = measured.toList(),
                        errorText = null
                    )
                }
                return SweepOutcome(
                    verdictKind = VerdictKind.NONE,
                    verdictText = null,
                    recommendation = null,
                    baselineMbps = baseline,
                    targetMbps = targetMbps,
                    measured = measured.toList(),
                    errorText = null
                )
            }

            val fastest = measured.maxByOrNull { it.mbps }
            return if (fastest != null) {
                SweepOutcome(
                    verdictKind = VerdictKind.FASTEST_NO_BITRATE,
                    verdictText = withPmSuffix(
                        context.getString(
                            R.string.stream_test_verdict_fastest_nobitrate,
                            fastest.connections,
                            fastest.chunkMb,
                            "%.1f Mbps".format(fastest.mbps)
                        ),
                        fastest.connections
                    ),
                    recommendation = Recommendation(
                        connections = fastest.connections,
                        chunkMb = fastest.chunkMb,
                        mbps = fastest.mbps,
                        meetsTarget = false,
                        fitsSafeBudget = overheadMb(fastest.connections, fastest.chunkMb) <= safeLimitMb
                    ),
                    baselineMbps = baseline,
                    targetMbps = targetMbps,
                    measured = measured.toList(),
                    errorText = null
                )
            } else {
                SweepOutcome(
                    verdictKind = VerdictKind.NONE,
                    verdictText = null,
                    recommendation = null,
                    baselineMbps = baseline,
                    targetMbps = targetMbps,
                    measured = measured.toList(),
                    errorText = null
                )
            }
        } catch (e: Exception) {
            return SweepOutcome(
                verdictKind = VerdictKind.NONE,
                verdictText = null,
                recommendation = null,
                baselineMbps = baselineMbps,
                targetMbps = targetMbps,
                measured = measured.toList(),
                errorText = e.localizedMessage ?: context.getString(R.string.error_unknown)
            )
        }
    }
}
