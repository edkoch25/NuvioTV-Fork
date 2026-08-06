package com.nuvio.tv.ui.screens.player

/**
 * Scrub / seek step sizes for remote D-pad and media keys.
 *
 * Android TV remotes fire repeated ACTION_DOWN events while a direction
 * key is held. The step size is derived from how long the key has been
 * held (KeyEvent eventTime - downTime), not from the repeat count, so the
 * ramp is independent of the remote's key-repeat cadence: 10 s steps for
 * the first three seconds of a hold, 20 s steps beyond that. A single tap
 * (hold duration 0) is one 10 s step.
 */
object PlayerScrubRates {
    const val STEP_SHORT_MS = 10_000L
    const val STEP_MEDIUM_MS = 20_000L

    /** Hold duration at which the scrub step doubles. */
    const val LONG_HOLD_THRESHOLD_MS = 3_000L

    /**
     * Returns the seek delta magnitude (always positive) for a key that
     * has been held for [holdDurationMs] milliseconds (0 for the initial
     * press, so a tap gets the base step).
     */
    fun stepMsForHold(holdDurationMs: Long): Long {
        return if (holdDurationMs >= LONG_HOLD_THRESHOLD_MS) STEP_MEDIUM_MS else STEP_SHORT_MS
    }

    /** Signed delta for a left/rewind (negative) or right/forward (positive) scrub. */
    fun deltaMsForHold(holdDurationMs: Long, forward: Boolean): Long {
        val step = stepMsForHold(holdDurationMs)
        return if (forward) step else -step
    }
}
