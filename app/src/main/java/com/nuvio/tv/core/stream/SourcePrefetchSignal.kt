package com.nuvio.tv.core.stream

import com.nuvio.tv.core.debrid.DirectDebridStreamFilter

/**
 * UI-facing signal for the details-page source line (fork feature).
 *
 * RANKED: the auto-play winner is chosen; [facts] carry its parsed badges.
 * READY: the winner's link is usable (debrid resolve Success, or a
 * direct-URL source needing no resolve).
 * SEARCHING is not emitted here: the ViewModel derives it from "key set,
 * no matching signal yet".
 */
enum class SourcePrefetchPhase { SEARCHING, RANKED, READY }

data class SourcePrefetchSignal(
    val uiKey: String,
    val phase: SourcePrefetchPhase,
    val facts: DirectDebridStreamFilter.StreamFacts?
)
