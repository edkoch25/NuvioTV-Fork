package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.player.PlaceholderStreamPolicy

/**
 * File-local. PlayerRuntimeController.TAG lives in a companion object and is not
 * in scope for a top-level extension function, while a top-level private TAG in
 * PlayerRuntimeControllerTorrent.kt is a resolution candidate for this package and
 * then fails the visibility check. Declaring our own is the pattern that file uses.
 * The string is deliberately unchanged so existing logcat filters keep working.
 */
private const val TAG = "PlayerViewModel"

/**
 * 5b step 2: measure the placeholder gate before letting it act.
 *
 * PlaceholderStreamPolicy needs an expected runtime, and runtime is only present
 * when the stream screen resolved one before the press. On the direct-autoplay
 * path the overlay may hand off before loadMetadataIfNeeded completes, which would
 * leave the policy inert on exactly the flow that matters most -- silently, since
 * a null runtime means "do not judge".
 *
 * Rather than reason about four different entry paths (direct autoplay, cached
 * link reuse, manual pick, binge prefetch), this evaluates the policy for real and
 * logs its inputs and verdict WITHOUT acting on it. One play answers whether the
 * runtime arrives, and whether the verdict would have been correct.
 *
 * Nothing here changes playback. It runs once per play session, reads two values
 * that are already computed, and writes one log line.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
internal fun PlayerRuntimeController.probePlaceholderStream(player: ExoPlayer): PlaceholderStreamPolicy.Verdict {
    if (placeholderProbeDone) return PlaceholderStreamPolicy.Verdict.Accept
    placeholderProbeDone = true

    val contentLengthBytes = PlaybackByteCounter.contentLengthFor(currentStreamUrl)
    val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
    val expectedRuntimeMs = expectedRuntimeMinutes?.let { it * 60_000L }

    val verdict = PlaceholderStreamPolicy.evaluate(
        contentLengthBytes = contentLengthBytes,
        durationMs = durationMs,
        expectedRuntimeMs = expectedRuntimeMs
    )

    val outcome = when (verdict) {
        is PlaceholderStreamPolicy.Verdict.Accept ->
            if (expectedRuntimeMs == null) "ACCEPT(no-runtime: gate inert)" else "ACCEPT"
        is PlaceholderStreamPolicy.Verdict.Reject ->
            "WOULD_REJECT(${verdict.reason}: ${verdict.detail})"
    }

    Log.i(
        TAG,
        "PLACEHOLDER_PROBE verdict=$outcome " +
            "contentLength=${contentLengthBytes ?: -1} " +
            "durationMs=${durationMs ?: -1} " +
            "expectedRuntimeMin=${expectedRuntimeMinutes ?: -1}"
    )

    return verdict
}
