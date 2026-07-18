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

    /**
     * Set when the buffer-trade refinement replaced the cheapest 2x config:
     * the chosen config meets only TRADE_BAR_OF_BITRATE x the title bitrate,
     * but leaves chosenBufferMb/S of target buffer on this device's safe
     * budget instead of the overBufferMb/S the 2x config (over*) would have.
     */
    data class BufferTrade(
        val overConnections: Int,
        val overChunkMb: Int,
        val overMbps: Double,
        val chosenBufferMb: Int,
        val chosenBufferS: Int,
        val overBufferMb: Int,
        val overBufferS: Int
    )

    data class Recommendation(
        val connections: Int,
        val chunkMb: Int,
        val mbps: Double,
        val meetsTarget: Boolean,
        val fitsSafeBudget: Boolean,
        val bufferTrade: BufferTrade? = null
    )

    data class SweepOutcome(
        val verdictKind: VerdictKind,
        val verdictText: String?,
        val recommendation: Recommendation?,
        val baselineMbps: Double,
        val targetMbps: Double?,
        val measured: List<MeasuredPass>,
        val errorText: String?,
        /**
         * Median per-pass coefficient of variation of the sub-window Mbps
         * series, across parallel passes with enough windows. Null when
         * fewer than STABILITY_MIN_PASSES qualifying passes ran (including
         * the leave-parallel-off early exit, where no parallel pass runs -
         * the baseline test carries no sub-windows). A link-wobble signal,
         * not a throughput figure.
         */
        val stabilityCoV: Double?,
        val stabilityPassCount: Int
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
        val passStabilityCovs = mutableListOf<Double>()

        suspend fun measure(connections: Int, chunkMb: Int): Double {
            val label = rowLabel(connections, chunkMb)
            ranConfigs += connections to chunkMb
            parallelPasses += 1
            onState(label)
            onPassAdded(label)
            val pass = StreamSpeedTester.runParallelChunkTest(
                streamUrl,
                headers,
                chunkMb * 1024L * 1024L,
                connections
            )
            val mbps = pass.mbps
            coefficientOfVariation(pass.subWindowMbps)?.let { passStabilityCovs += it }
            onPassResult(label, mbps)
            if (mbps > 0) measured += MeasuredPass(connections, chunkMb, mbps)
            if (targetMbps != null && mbps >= targetMbps * SUFFICIENCY_TOLERANCE && passesSinceSufficient < 0) {
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
                    errorText = context.getString(R.string.stream_test_error_connection),
                    stabilityCoV = stabilityCoVOf(passStabilityCovs),
                    stabilityPassCount = passStabilityCovs.size
                )
            }

            if (targetMbps != null && baseline >= targetMbps * SUFFICIENCY_TOLERANCE) {
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
                    errorText = null,
                    stabilityCoV = stabilityCoVOf(passStabilityCovs),
                    stabilityPassCount = passStabilityCovs.size
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
                    errorText = null,
                    stabilityCoV = stabilityCoVOf(passStabilityCovs),
                    stabilityPassCount = passStabilityCovs.size
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
                val sufficient = measured.filter { it.mbps >= targetMbps * SUFFICIENCY_TOLERANCE }
                val sufficientAndSafe = sufficient.filter {
                    overheadMb(it.connections, it.chunkMb) <= safeLimitMb
                }
                val cheapest = (sufficientAndSafe.ifEmpty { sufficient })
                    .minByOrNull { overheadMb(it.connections, it.chunkMb) }
                if (cheapest != null) {
                    // Buffer-trade refinement: the pipe (parallel overhead) and
                    // the tank (target buffer) share one safe budget. Insisting
                    // on 2x can spend so much on the pipe that the tank shrinks
                    // below usefulness on constrained tiers. If a config meeting
                    // TRADE_BAR_OF_BITRATE x buys >= TRADE_MIN_GAIN_S more
                    // seconds of buffer, recommend it instead and say why.
                    val bitrateMbps = targetMbps / 2.0
                    fun bufferMbAt(connections: Int, chunkMb: Int): Int =
                        (((safeLimitMb - overheadMb(connections, chunkMb)) / MemoryBudget.BUFFER_STEP_MB) *
                            MemoryBudget.BUFFER_STEP_MB)
                            .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
                            .coerceAtMost(com.nuvio.tv.data.local.PlayerSettings.LARGE_TARGET_BUFFER_MAX_MB)
                    fun bufferSecondsAt(mb: Int): Int = (mb * 8.0 / bitrateMbps).toInt()
                    val tradeBar = bitrateMbps * TRADE_BAR_OF_BITRATE
                    val nearSufficient = measured.filter { it.mbps >= tradeBar }
                    val nearAndSafe = nearSufficient.filter {
                        overheadMb(it.connections, it.chunkMb) <= safeLimitMb
                    }
                    val cheaper = (nearAndSafe.ifEmpty { nearSufficient })
                        .minByOrNull { overheadMb(it.connections, it.chunkMb) }
                    var chosen = cheapest
                    var trade: BufferTrade? = null
                    if (cheaper != null &&
                        (cheaper.connections != cheapest.connections || cheaper.chunkMb != cheapest.chunkMb)
                    ) {
                        val chosenMb = bufferMbAt(cheaper.connections, cheaper.chunkMb)
                        val overMb = bufferMbAt(cheapest.connections, cheapest.chunkMb)
                        val chosenS = bufferSecondsAt(chosenMb)
                        val overS = bufferSecondsAt(overMb)
                        if (chosenS - overS >= TRADE_MIN_GAIN_S) {
                            chosen = cheaper
                            trade = BufferTrade(
                                overConnections = cheapest.connections,
                                overChunkMb = cheapest.chunkMb,
                                overMbps = cheapest.mbps,
                                chosenBufferMb = chosenMb,
                                chosenBufferS = chosenS,
                                overBufferMb = overMb,
                                overBufferS = overS
                            )
                        }
                    }
                    val verdict = if (trade != null) {
                        context.getString(
                            R.string.stream_test_verdict_recommend_trade,
                            chosen.connections,
                            chosen.chunkMb,
                            "%.1f Mbps".format(chosen.mbps),
                            "%.1f".format(chosen.mbps / bitrateMbps),
                            trade.overConnections,
                            trade.overChunkMb,
                            trade.chosenBufferMb,
                            trade.chosenBufferS,
                            trade.overBufferMb,
                            trade.overBufferS
                        )
                    } else {
                        context.getString(
                            R.string.stream_test_verdict_recommend,
                            chosen.connections,
                            chosen.chunkMb,
                            "%.1f Mbps".format(chosen.mbps)
                        )
                    }
                    return SweepOutcome(
                        verdictKind = VerdictKind.RECOMMEND_CONFIG,
                        verdictText = withPmSuffix(verdict, chosen.connections),
                        recommendation = Recommendation(
                            connections = chosen.connections,
                            chunkMb = chosen.chunkMb,
                            mbps = chosen.mbps,
                            meetsTarget = chosen.mbps >= targetMbps * SUFFICIENCY_TOLERANCE,
                            fitsSafeBudget = overheadMb(chosen.connections, chosen.chunkMb) <= safeLimitMb,
                            bufferTrade = trade
                        ),
                        baselineMbps = baseline,
                        targetMbps = targetMbps,
                        measured = measured.toList(),
                        errorText = null,
                        stabilityCoV = stabilityCoVOf(passStabilityCovs),
                        stabilityPassCount = passStabilityCovs.size
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
                        errorText = null,
                        stabilityCoV = stabilityCoVOf(passStabilityCovs),
                        stabilityPassCount = passStabilityCovs.size
                    )
                }
                return SweepOutcome(
                    verdictKind = VerdictKind.NONE,
                    verdictText = null,
                    recommendation = null,
                    baselineMbps = baseline,
                    targetMbps = targetMbps,
                    measured = measured.toList(),
                    errorText = null,
                    stabilityCoV = stabilityCoVOf(passStabilityCovs),
                    stabilityPassCount = passStabilityCovs.size
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
                    errorText = null,
                    stabilityCoV = stabilityCoVOf(passStabilityCovs),
                    stabilityPassCount = passStabilityCovs.size
                )
            } else {
                SweepOutcome(
                    verdictKind = VerdictKind.NONE,
                    verdictText = null,
                    recommendation = null,
                    baselineMbps = baseline,
                    targetMbps = targetMbps,
                    measured = measured.toList(),
                    errorText = null,
                    stabilityCoV = stabilityCoVOf(passStabilityCovs),
                    stabilityPassCount = passStabilityCovs.size
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
                errorText = e.localizedMessage ?: context.getString(R.string.error_unknown),
                stabilityCoV = stabilityCoVOf(passStabilityCovs),
                stabilityPassCount = passStabilityCovs.size
            )
        }
    }

    // Minimum sub-windows for a pass to yield a CoV, and minimum qualifying
    // passes for the sweep-level median. [inferred] initial values.
    private const val STABILITY_MIN_WINDOWS = 4
    private const val STABILITY_MIN_PASSES = 2

    // Buffer-trade refinement. [inferred] initial values: a config meeting
    // >= 1.5x the title bitrate refills the buffer at ~0.5 s of content per
    // real second - enough to outpace ordinary dips - so when it leaves at
    // least TRADE_MIN_GAIN_S more seconds of target buffer than the cheapest
    // 2x config on this device's safe budget, tank depth beats refill rate.
    // On large budgets the gain never reaches the gate and 2x keeps winning.
    private const val TRADE_BAR_OF_BITRATE = 1.5
    private const val TRADE_MIN_GAIN_S = 5

    // Sufficiency is judged with a near-miss tolerance: single-sample passes
    // on links measuring CoV ~0.4 make a 1-2% shortfall against the 2x bar
    // indistinguishable from passing, and a hard cliff turned one such miss
    // (189.8 vs 192.0) into a 64 MB-costlier recommendation. [inferred]
    // initial value, like the trade constants above; TRADE_MIN_GAIN_S was
    // lowered 8 -> 5 on two runs of field data (6 s gain wrongly blocked,
    // 2 s gain correctly blocked).
    private const val SUFFICIENCY_TOLERANCE = 0.95

    private fun coefficientOfVariation(samples: List<Double>): Double? {
        if (samples.size < STABILITY_MIN_WINDOWS) return null
        val mean = samples.average()
        if (mean <= 0.0) return null
        val variance = samples.sumOf { val d = it - mean; d * d } / samples.size
        return kotlin.math.sqrt(variance) / mean
    }

    private fun stabilityCoVOf(passCovs: List<Double>): Double? {
        if (passCovs.size < STABILITY_MIN_PASSES) return null
        val sorted = passCovs.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
