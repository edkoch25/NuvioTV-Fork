package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream

/**
 * R2: rank during the prefetch instead of after the Play press.
 *
 * Measured 24 Jul 2026 (Xiaomi S905X5M, 4K DV, auto-play "Best quality"):
 * APPLY_SPLIT select= was 398/341/379/367 ms across four runs, 94% of it inside
 * DirectDebridStreamFilter.factsFor. StreamPrefetchCache already hands the
 * stream list over 2.6-7.6 s before the click and AutoPlaySelection.select is
 * pure, so the whole segment can leave the critical path rather than be shrunk.
 *
 * This file is the safety argument, and it is deliberately pure: no Android, no
 * coroutines, no ViewModel. The gate is the only thing standing between "the
 * pick is the same" and "the pick silently changed", so it is the one part that
 * must be covered by executable assertions rather than by a commit message.
 *
 * Why a snapshot rather than a re-check: the winner is decided by the settings
 * read at rank time. If any of them changed between the prefetch and the press,
 * the cached winner is not what this play would have chosen, so it is discarded
 * and the rank is redone live. Divergence is not detected after the fact, it is
 * structurally excluded.
 *
 * ⚠ [installedAddonOrder] is part of the snapshot even though
 * [AutoPlaySelection.Inputs] already carries installedAddonNames as a Set.
 * StreamAutoPlaySelector.orderAddonStreams consumes the ORDERED list, and
 * StreamQualityRank.rank is a stable sort, so incoming order decides ties. An
 * addon reordered between prefetch and press would change the winner while the
 * Set compared equal.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */

/** Everything that decides the auto-play winner, captured at rank time. */
data class SelectionSnapshot(
    val inputs: AutoPlaySelection.Inputs,
    val installedAddonOrder: List<String>,
    val preferences: DebridStreamPreferences?
)

/** A winner computed during the prefetch, with the snapshot it was computed under. */
data class PrefetchedSelection(
    val snapshot: SelectionSnapshot,
    val winner: Stream
)

/** Outcome of consulting a prefetched selection at press time. */
sealed interface SelectionOutcome {
    /** Use this stream; it is the instance from the live list, not the cached twin. */
    data class Hit(val stream: Stream) : SelectionOutcome

    /** Rank live. [reason] is logged so a silent fallback cannot read as a win. */
    data class Live(val reason: String) : SelectionOutcome
}

object PrefetchedSelectionGate {

    const val REASON_NO_ENTRY = "no-entry"
    const val REASON_INPUTS_CHANGED = "inputs-changed"
    const val REASON_KEY_MISS = "key-miss"

    /**
     * Resolves a prefetched winner against the list actually being presented.
     *
     * [identityOf] must be badge-independent. applySuccess badge-merges via
     * stream.copy(badges = ...) before the ranking pass, so the cached winner
     * can be a badge-less twin of the instance in [streams]; data-class equality
     * would miss it, and returning the cached instance would drop badges the UI
     * has already computed. StreamScreenViewModel.badgeMergeKey is exactly this
     * identity and is what the badge merge itself keys on.
     */
    fun resolve(
        prefetched: PrefetchedSelection?,
        snapshot: SelectionSnapshot,
        streams: List<Stream>,
        identityOf: (Stream) -> String
    ): SelectionOutcome {
        if (prefetched == null) return SelectionOutcome.Live(REASON_NO_ENTRY)
        if (prefetched.snapshot != snapshot) return SelectionOutcome.Live(REASON_INPUTS_CHANGED)
        val wantedKey = identityOf(prefetched.winner)
        val match = streams.firstOrNull { identityOf(it) == wantedKey }
            ?: return SelectionOutcome.Live(REASON_KEY_MISS)
        return SelectionOutcome.Hit(match)
    }
}
