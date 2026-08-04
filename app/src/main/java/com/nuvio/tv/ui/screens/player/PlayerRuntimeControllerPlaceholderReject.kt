package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.player.PlaceholderStreamPolicy
import kotlinx.coroutines.flow.update

/**
 * 5c: act on a placeholder verdict from [PlaceholderStreamPolicy].
 *
 * 5b probed and only logged; this stops the stream the policy rejected. Two entry
 * points share it: the byte floor at STATE_READY (content-length is stable there)
 * and the duration backstop on the progress tick (decoded duration is only
 * trustworthy once playback is under way, so a provisional READY duration cannot
 * cause a false reject). The policy's runtime guard is the fail-safe -- with no
 * >= 20 min runtime it returns Accept, so this is never reached for a real stream
 * or a no-runtime transition.
 *
 * Halting by setting [error] is load-bearing beyond stopping playback: every
 * watch-state consumer derives hasFatalError from a non-blank error, so a rejected
 * placeholder cannot mark the title watched, save progress, or arm next-episode
 * auto-play.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
internal fun PlayerRuntimeController.rejectPlaceholderStream(
    verdict: PlaceholderStreamPolicy.Verdict.Reject
) {
    Log.w(
        PlayerRuntimeController.TAG,
        "PLACEHOLDER_REJECT reason=${verdict.reason} detail=${verdict.detail} " +
            "host=${currentStreamUrl.safeHost()}"
    )
    runCatching { _exoPlayer?.stop() }
    cancelNextEpisodeAutoPlayOnFatalError()
    _uiState.update {
        it.copy(
            error = context.getString(R.string.player_error_placeholder_stream),
            showLoadingOverlay = false
        )
    }
}
