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

    /** N3d-a3 zero floor, as a fraction of the single-connection baseline. */
    private const val ZERO_FLOOR_OF_BASELINE = 0.05

    /** N3d-b pause ladder and whole-sweep budget, in milliseconds. */
    private const val RETRY_PAUSE_BASE_MS = 15_000L
    private const val RETRY_PAUSE_MAX_MS = 45_000L
    private const val RETRY_PAUSE_BUDGET_MS = 90_000L

    data class MeasuredPass(val connections: Int, val chunkMb: Int, val mbps: Double)

    /**
     * Crash-hardening leg 1 (19 Jul 2026 incident): a cell that died or was
     * refused mid-sweep, recorded so the sweep can continue and still report
     * what happened. Failed cells never enter [MeasuredPass] candidates.
     */
    data class FailedCell(val label: String, val reason: String)

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
        val stabilityPassCount: Int,
        /** Cells that died or were refused mid-sweep; see [FailedCell]. */
        val failedCells: List<FailedCell> = emptyList()
    )

    suspend fun run(
        context: Context,
        streamUrl: String,
        headers: Map<String, String>,
        estimatedBitrate: Long?,
        onState: (String) -> Unit,
        onPassAdded: (String) -> Unit,
        /**
         * N3d: the Double is nullable because a cell can now finish without
         * producing a measurement - refused by the budget gate, or discarded
         * because the source rate-limited it. Null renders as "---" (both
         * screens already had that branch for pending rows); 0.0 would read
         * to a user as "this configuration achieved zero throughput", which
         * is a different and false claim.
         */
        onPassResult: (String, Double?, String?) -> Unit
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

        // Crash-hardening leg 2 (19 Jul 2026 incident): the depth the tester
        // will actually be allowed to schedule with, budget-derived from the
        // SAME single-source-of-truth function — null means the cell can not
        // be memory-bounded on this device (or busts the absolute concurrent
        // cap) and must never run. The old gate checked the (conn+2)*chunk
        // display model against the WARNING limit while the tester ran an
        // unconditional conn*4 window: model said 320 MB, reality permitted
        // ~1 GB, and the S905X5M (250 MB safe / 325 MB warning) died on the
        // 3 conn / 64 MB cell.
        fun cellDepth(connections: Int, chunkMb: Int): Int? =
            MemoryBudget.sweepCellPrefetchDepth(connections, chunkMb, safeLimitMb)

        // N3c: the gate now explains itself. A refused cell previously
        // produced NO output of any kind - no log line, no row, no reason -
        // which is why two full sweeps (21 Jul 2026) truncated the chunk
        // ladder at 32 MB on a 250 MB-budget box with nothing on screen, and
        // why confirming the gate worked at all took inference from an
        // absence. Clause order is deliberate: the absolute concurrent cap
        // is tested before the depth floor so the more specific reason wins
        // (both would otherwise surface as a bare null depth).
        fun rejectReason(connections: Int, chunkMb: Int): String? = when {
            chunkMb !in minChunkMb..maxChunkMb ->
                "chunk outside the supported ${minChunkMb}-${maxChunkMb} MB range"
            overheadMb(connections, chunkMb) > warningLimitMb ->
                "estimated ${overheadMb(connections, chunkMb)} MB over the " +
                    "${warningLimitMb} MB warning limit"
            connections * chunkMb > MemoryBudget.SWEEP_CELL_MAX_CONCURRENT_MB ->
                "${connections * chunkMb} MB concurrent over the " +
                    "${MemoryBudget.SWEEP_CELL_MAX_CONCURRENT_MB} MB cap"
            cellDepth(connections, chunkMb) == null ->
                "cannot be bounded within the ${safeLimitMb} MB budget"
            else -> null
        }

        fun allowed(connections: Int, chunkMb: Int) = rejectReason(connections, chunkMb) == null

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
        val failedCells = mutableListOf<FailedCell>()
        // Declared here rather than beside the baseline stage because
        // measure()'s zero floor (N3d-a3) reads it, and a Kotlin local
        // function cannot capture a local declared after it.
        var baselineMbps = 0.0
        // N3d-b: total time this sweep has spent waiting out rate limits.
        var pauseSpentMs = 0L
        // N3c: labels already reported as skipped, so re-evaluating the same
        // config in a later stage does not repeat the message.
        val skippedLabels = mutableSetOf<String>()
        // N3b: cells discarded because the source rate-limited them.
        var clampedCells = 0
        // N3d-b: how many cells have been retried this sweep (drives the
        // doubling pause).
        var retriesAttempted = 0

        fun noteSkipped(connections: Int, chunkMb: Int) {
            val reason = rejectReason(connections, chunkMb) ?: return
            val label = rowLabel(connections, chunkMb)
            if (skippedLabels.add(label)) {
                onState(context.getString(R.string.stream_test_cell_skipped, label, reason))
                // The a2 note plumbing was added to the budget-gate fallback
                // INSIDE measure(), which its own comment says is unreachable
                // in normal operation - while this, the path that actually
                // fires, still only wrote a status line the next cell
                // overwrote. Four sweeps (21 Jul) refused the 64 MB rung and
                // not one of them left anything on screen to prove it. A
                // refusal now leaves a row like any other cell.
                onPassAdded(label)
                onPassResult(
                    label, null,
                    context.getString(R.string.stream_test_note_skipped, reason)
                )
            }
        }

        // N3b: one clamp can be noise; two is the source telling us it will
        // not serve this many connections. Continuing to climb wastes cells,
        // provokes further throttling, and - as the 21 Jul TorBox sweep
        // showed - degrades the LATER base-ladder cells too, because the
        // limiter applies to the account, not to the app session.
        fun sourceIsThrottling() = clampedCells >= 2

        // N3d-a3: a cell that transferred essentially nothing did not measure
        // its configuration - it measured a refusal. Observed 21 Jul on
        // TorBox: 3c/16, 4c/16 and 8c/16 each ran WITHOUT tripping the clamp
        // threshold, because 429 backoffs consumed the whole window, and each
        // reported 0.0 Mbps. Those rows then fed the climb logic exactly as
        // the void cells N3d-a1 had just stopped feeding it. The floor is
        // relative to the baseline so it is link-agnostic (about 3.7 Mbps on
        // that TorBox run, 7.3 on Emby), with an absolute guard against a
        // pathological baseline. [inferred initial value, field-tunable]
        fun zeroFloorMbps() = maxOf(1.0, baselineMbps * ZERO_FLOOR_OF_BASELINE)

        // N3d-b: 15 s, doubling per prior wait, capped. Biased LOW on purpose
        // - too short fails informatively (the retry clamps again and the
        // cell is voided as it would have been anyway), too long wastes time
        // silently. The only evidence available: on the 21 Jul TorBox sweep
        // the source served normally again after a 29 s quiet gap, so a first
        // guess well under 30 s is the right neighbourhood.
        // [inferred initial values, field-tunable - the instrumentation below
        // is what turns them into measured ones]
        fun nextPauseMs(): Long =
            (RETRY_PAUSE_BASE_MS shl retriesAttempted).coerceAtMost(RETRY_PAUSE_MAX_MS)

        /**
         * Returns null when the cell produced NO measurement. N3d: previously
         * these paths returned 0.0, which the climb logic read as a
         * catastrophic throughput regression and the user read as a broken
         * configuration - neither true. The 21 Jul nt2 run ended its
         * connection climb on exactly this: one discarded cell scored 0.0 and
         * tripped the regression break, so the sweep stopped on a
         * non-measurement rather than on evidence. Every caller now decides
         * explicitly what a void cell means for its own dimension.
         */
        suspend fun measure(connections: Int, chunkMb: Int): Double? {
            val label = rowLabel(connections, chunkMb)
            ranConfigs += connections to chunkMb
            parallelPasses += 1
            onState(label)
            onPassAdded(label)
            // allowed() gates every caller, so the depth is present; the
            // fallback exists only so a future call-site slip degrades to a
            // skipped cell instead of an unbounded one.
            val depth = cellDepth(connections, chunkMb) ?: run {
                failedCells += FailedCell(label, "budget gate")
                onPassResult(
                    label, null,
                    context.getString(
                        R.string.stream_test_note_skipped,
                        rejectReason(connections, chunkMb)
                            ?: context.getString(R.string.stream_test_note_skipped_generic)
                    )
                )
                return null
            }
            // Crash-hardening leg 1 (19 Jul 2026 incident): a cell may die —
            // OutOfMemoryError included — and the sweep must survive it, keep
            // its measurements, and continue. The tester already contains its
            // own failures; this catch is the sweep-level backstop for
            // anything that still escapes. CancellationException stays
            // transparent so aborting the sweep keeps working.
            suspend fun runCell(): StreamSpeedTester.ParallelPassResult = try {
                StreamSpeedTester.runParallelChunkTest(
                    streamUrl,
                    headers,
                    chunkMb * 1024L * 1024L,
                    connections,
                    depth
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                StreamSpeedTester.ParallelPassResult(
                    0.0,
                    emptyList(),
                    failureReason = t.javaClass.simpleName
                )
            }

            // N3d-a3: the two ways a cell can finish without measuring its
            // own configuration. Clamped means it ran on one connection, not
            // the labelled count; starved means 429 backoffs ate the window.
            // Both are refusals by the source, and both are equally useless
            // as comparison points.
            fun voidReasonFor(p: StreamSpeedTester.ParallelPassResult): String? = when {
                p.clampTrips > 0 -> "rate-limited"
                p.mbps < zeroFloorMbps() -> "no usable transfer"
                else -> null
            }

            var pass = runCell()
            var voidReason = voidReasonFor(pass)

            // N3d-b: one retry after a pause. A refusal is often transient -
            // discarding the cell outright throws away a data point that a
            // short wait might have recovered. Bounded three ways: one retry
            // per cell, a doubling pause, and a whole-sweep pause budget so a
            // thoroughly hostile source cannot stretch the run indefinitely.
            if (voidReason != null && pauseSpentMs + nextPauseMs() <= RETRY_PAUSE_BUDGET_MS) {
                val waitMs = nextPauseMs()
                retriesAttempted += 1
                pauseSpentMs += waitMs
                onState(
                    context.getString(
                        R.string.stream_test_cell_retry_wait, label, (waitMs / 1000L).toInt()
                    )
                )
                kotlinx.coroutines.delay(waitMs)
                pass = runCell()
                val afterReason = voidReasonFor(pass)
                // Instrumentation is the point, not a nicety: shipping the
                // retry with its own record is what turns the pause constants
                // above from invented numbers into measured ones.
                android.util.Log.i(
                    "StreamSweepEngine",
                    "N3d-b retry: cell=${connections}c/${chunkMb}MB waited=${waitMs}ms " +
                        "before=$voidReason after=${afterReason ?: "recovered"} " +
                        "budgetUsed=${pauseSpentMs}/${RETRY_PAUSE_BUDGET_MS}ms"
                )
                voidReason = afterReason
            }

            pass.failureReason?.let { reason ->
                failedCells += FailedCell(label, reason)
                onState(context.getString(R.string.stream_test_cell_failed, label, reason))
            }
            // N3b: a clamped cell completed and returned a real number, but
            // it ran on ONE connection, not the labelled count. It must not
            // enter `measured` (it would compete for the recommendation
            // against honestly-labelled cells), must not contribute a
            // stability CoV (contaminated sub-windows), and must not satisfy
            // the sufficiency target (a fast clamped cell would end the
            // search on false evidence). Reported at 0.0 so the row is
            // visible without pretending to be a result.
            if (voidReason != null) {
                clampedCells += 1
                failedCells += FailedCell(label, voidReason)
                val note = if (voidReason == "rate-limited") {
                    context.getString(R.string.stream_test_note_rate_limited)
                } else {
                    context.getString(R.string.stream_test_note_no_transfer)
                }
                onState(context.getString(R.string.stream_test_cell_rate_limited, label))
                onPassResult(label, null, note)
                return null
            }
            val mbps = pass.mbps
            coefficientOfVariation(pass.subWindowMbps)?.let { passStabilityCovs += it }
            onPassResult(label, mbps, null)
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

        try {
            // Stage 1 - baseline.
            val baselineLabel = context.getString(R.string.stream_test_label_baseline)
            onState(baselineLabel)
            onPassAdded(baselineLabel)
            val baseline = StreamSpeedTester.runBaselineTest(
                streamUrl,
                headers
            )
            onPassResult(baselineLabel, baseline, null)
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
                    stabilityPassCount = passStabilityCovs.size,
                    failedCells = failedCells.toList()
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
                    stabilityPassCount = passStabilityCovs.size,
                    failedCells = failedCells.toList()
                )
            }

            // Stage 2 - chunk climb at 2 connections.
            var bestConnections = 2
            var bestChunkMb = -1
            var bestMbps = -1.0
            var prevMbps = -1.0
            var grace = true // one pass through a single regression while below target
            for (chunkMb in chunkLadderMb) {
                if (!mayContinue()) break
                // Split from mayContinue() so a budget-exhausted stop is not
                // mis-reported as a gate refusal. Note: this break ends the
                // ladder, so rungs ABOVE the refused one are never evaluated
                // and are deliberately not reported - claiming they were
                // considered would be false.
                if (!allowed(2, chunkMb)) { noteSkipped(2, chunkMb); break }
                // N3d: a void cell says nothing about THIS chunk size, so
                // carry on up the ladder without touching prevMbps, grace or
                // best. Larger chunks mean fewer requests per second, so a
                // clamp here does not predict one at the next rung.
                val mbps = measure(2, chunkMb) ?: continue
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
                    stabilityPassCount = passStabilityCovs.size,
                    failedCells = failedCells.toList()
                )
            }

            // Stage 3 - connection climb at the best chunk.
            prevMbps = bestMbps
            grace = true
            for (connections in connLadder) {
                if (connections <= 2) continue
                if (!mayContinue()) break
                if (sourceIsThrottling()) break
                if (!allowed(connections, bestChunkMb)) {
                    noteSkipped(connections, bestChunkMb); break
                }
                if (connections to bestChunkMb in ranConfigs) continue
                // N3d, tier 1 of the stop rule: a void cell in the CONNECTION
                // climb ends the climb outright - one clamp is enough here.
                // Rate limiting tracks request rate, so a refusal at N
                // connections all but guarantees one at N+1; climbing further
                // buys no information and provokes the source. (Tier 2,
                // sourceIsThrottling(), still governs the cross-config and
                // neighbour escalations.) This makes deliberate what the nt2
                // run did by accident via the 0.0 regression break.
                val mbps = measure(connections, bestChunkMb) ?: break
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
                val connUp =
                    if (sourceIsThrottling()) null
                    else connLadder.firstOrNull { it > bestConnections }
                val neighbours = listOfNotNull(
                    chunkUp?.let { bestConnections to it },
                    connUp?.let { it to bestChunkMb },
                    chunkDown?.let { bestConnections to it }
                )
                for ((connections, chunkMb) in neighbours) {
                    if (!mayContinue()) break
                    if (connections to chunkMb in ranConfigs) continue
                    if (!allowed(connections, chunkMb)) {
                        noteSkipped(connections, chunkMb); continue
                    }
                    val mbps = measure(connections, chunkMb) ?: continue
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
                    .filter { it !in ranConfigs }
                    .filter { cfg ->
                        allowed(cfg.first, cfg.second)
                            .also { if (!it) noteSkipped(cfg.first, cfg.second) }
                    }
                    .sortedBy { overheadMb(it.first, it.second) }
                for ((connections, chunkMb) in crossConfigs) {
                    if (!mayContinue()) break
                    // N3d-a3: the N3b commit message claimed the escalation
                    // stop covered this loop. It did not - the guard was only
                    // in stage 3 and the stage-4 connUp neighbour, which is
                    // why 3c/8 and 4c/8 still ran and clamped on 21 Jul after
                    // three cells had already been refused. Added here now.
                    if (sourceIsThrottling()) break
                    val mbps = measure(connections, chunkMb) ?: continue
                    if (mbps > bestMbps) {
                        bestMbps = mbps
                        bestConnections = connections
                        bestChunkMb = chunkMb
                    }
                    if (targetMbps != null && mbps >= targetMbps) break
                }
            }

            // Crash-hardening leg 3 (19 Jul 2026 incident): on the <= 2 GB
            // native tier (safe budget <= 250 MB) the recommendation layer
            // never surfaces chunk sizes above RECOMMEND_CHUNK_CAP_LOW_TIER_MB,
            // even if a bounded probe measured one. Probe-to-warning vs
            // recommend-to-safe asymmetry stands; this is a second, tier-keyed
            // asymmetry on the chunk axis. Candidate selection only — measured
            // rows are reported as run.
            val recommendChunkCapMb =
                if (safeLimitMb <= LOW_TIER_SAFE_LIMIT_MB) RECOMMEND_CHUNK_CAP_LOW_TIER_MB else maxChunkMb
            val recommendable = measured.filter { it.chunkMb <= recommendChunkCapMb }

            // Verdict: cheapest config that BOTH meets the 2x target AND fits the
            // safe native-memory budget, so the tool never recommends a configuration
            // the memory-usage indicator would flag. If none of the sufficient configs
            // fit the safe budget, fall back to the cheapest sufficient one regardless
            // (a working recommendation beats none on a very memory-constrained device).
            if (targetMbps != null) {
                val sufficient = recommendable.filter { it.mbps >= targetMbps * SUFFICIENCY_TOLERANCE }
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
                    val nearSufficient = recommendable.filter { it.mbps >= tradeBar }
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
                        stabilityPassCount = passStabilityCovs.size,
                        failedCells = failedCells.toList()
                    )
                }
                val fastest = recommendable.maxByOrNull { it.mbps }
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
                        stabilityPassCount = passStabilityCovs.size,
                        failedCells = failedCells.toList()
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
                    stabilityPassCount = passStabilityCovs.size,
                    failedCells = failedCells.toList()
                )
            }

            val fastest = recommendable.maxByOrNull { it.mbps }
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
                    stabilityPassCount = passStabilityCovs.size,
                    failedCells = failedCells.toList()
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
                    stabilityPassCount = passStabilityCovs.size,
                    failedCells = failedCells.toList()
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Aborting the sweep must propagate, not masquerade as an error verdict.
            throw e
        } catch (e: Throwable) {
            // Crash-hardening leg 1 (19 Jul 2026 incident): Throwable, not
            // Exception — an Error escaping the per-cell isolation must still
            // end as a structured outcome, never a process death.
            return SweepOutcome(
                verdictKind = VerdictKind.NONE,
                verdictText = null,
                recommendation = null,
                baselineMbps = baselineMbps,
                targetMbps = targetMbps,
                measured = measured.toList(),
                errorText = e.localizedMessage ?: context.getString(R.string.error_unknown),
                stabilityCoV = stabilityCoVOf(passStabilityCovs),
                stabilityPassCount = passStabilityCovs.size,
                failedCells = failedCells.toList()
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

    // Crash-hardening leg 3 (19 Jul 2026 incident). LOW_TIER matches
    // getSafeNativeMemoryLimitMb's <= 2 GB rungs (150/200/250); the chunk cap
    // is the largest size with any recommendation history on that tier.
    // [inferred] initial values.
    private const val LOW_TIER_SAFE_LIMIT_MB = 250
    private const val RECOMMEND_CHUNK_CAP_LOW_TIER_MB = 32

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
