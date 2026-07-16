package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_STARTUP_AUTO_RETRIES = 2
private const val MAX_AUTO_RETRIES = 2
private const val MAX_DEAD_SOURCE_FAILOVERS = 3

// nt6 fix B: ceiling on TOTAL automatic recoveries for one stream URL, across
// every fallback ladder combined (DV modes, safe audio, PCM, timeout, NPE, 416,
// engine failover, dead-source, auto-retry). Each ladder is individually
// bounded, but chained they observably looped a dead decoder pipeline for
// minutes — ~40 s per cycle on a large moov-at-tail MP4. Five covers the
// deepest legitimate chain while bounding the pathological case.
private const val MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM = 5
private const val RETRY_DELAY_MS = 1_500L
private const val STABLE_PROGRESS_RESET_DELAY_MS = 5_000L

internal fun PlayerRuntimeController.showRecoveryOverlay() {
    _uiState.update { state ->
        state.copy(
            error = null,
            isBuffering = true,
            showLoadingOverlay = true,
            loadingMessage = context.getString(R.string.player_loading_buffering),
            showPauseOverlay = false
        )
    }
}

internal fun PlayerRuntimeController.attemptStartupRecovery(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (hasRenderedFirstFrame) return false
    if (!isRetryablePlaybackError(error)) return false
    if (startupRetryCount >= MAX_STARTUP_AUTO_RETRIES) return false

    handleParsingErrorFallback(error)

    val paused = userPausedManually
    val attempt = startupRetryCount
    startupRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Startup recovery ${attempt + 1}/$MAX_STARTUP_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                isBuffering = true,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(R.string.player_loading_buffering),
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        releasePlayer(flushPlaybackState = false)
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

/**
 * Determines whether the given [PlaybackException] is transient and worth retrying.
 *
 * Retryable errors include source/IO errors, parsing glitches, and unexpected runtime
 * exceptions that commonly occur after pause/resume or seek on flaky streams.
 * Decoder-init and DRM errors are considered fatal.
 */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        // --- Source / IO errors (the 2xxx range) ---
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE, -> true

        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
            val httpCause = error.findCauseOfType<HttpDataSource.InvalidResponseCodeException>()
            if (httpCause != null) {
                val code = httpCause.responseCode
                !(code == 400 || code == 401 || code == 403 || code == 404 || code == 410)
            } else {
                true
            }
        }
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,

        // --- Decoder errors (often transient after pause/resume on some hardware) ---
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        // --- Behind-the-scenes / unexpected errors (often IllegalStateException / NPE) ---
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        else -> false
    }
}

/**
 * Audio-track failures that the safe-audio → audio-disabled fallback ladder can recover from.
 *
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED] (5001): the AudioTrack could not be
 *   created (e.g. the requested passthrough/offload encoding is not actually accepted by the sink).
 * - [PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED] (5002): a write to the AudioTrack
 *   failed, most commonly with `AudioTrack.ERROR_DEAD_OBJECT` (-6) when an HDMI/audio-route
 *   renegotiation invalidates an E-AC-3/AC-3 passthrough or offload track mid-playback.
 *
 * Both are remedied by re-selecting audio with tunneling/passthrough off and the channel count
 * constrained to the device's capabilities (safe-audio mode), or by dropping audio entirely — so
 * a write failure must take the same recovery path as an init failure rather than landing on the
 * fatal error screen.
 *
 * [combinedMessage] is the concatenated exception/cause messages; the string checks are a safety
 * net for devices that surface the same failure under a generic error code.
 */
internal fun isAudioTrackFailure(errorCode: Int, combinedMessage: String): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED) return true
    return combinedMessage.contains("audiotrack init failed", ignoreCase = true) ||
        combinedMessage.contains("audiotrack write failed", ignoreCase = true)
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

internal fun PlaybackException.toDisplayMessage(context: android.content.Context): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val code = responseException.responseCode
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        val providerHint = when (code) {
            400 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
            401 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
            403 -> context.getString(com.nuvio.tv.R.string.player_error_stream_blocked)
            404 -> context.getString(com.nuvio.tv.R.string.player_error_stream_removed)
            410 -> context.getString(com.nuvio.tv.R.string.player_error_stream_expired)
            429 -> context.getString(com.nuvio.tv.R.string.player_error_stream_rate_limited)
            500, 502, 503, 504 -> context.getString(com.nuvio.tv.R.string.player_error_stream_unavailable)
            else -> ""
        }
        return buildString {
            append("HTTP $code")
            statusText?.let { append(" $it") }
            append(" [$errorCodeName]")
            append(providerHint)
        }
    }

    // Check for unrecognized format (provider returned non-video content)
    val isUnrecognizedFormat = findCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
    if (isUnrecognizedFormat) {
        return context.getString(com.nuvio.tv.R.string.player_error_source_invalid_content, errorCodeName)
    }

    // Check for codec/renderer errors
    val isRendererError = errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
    if (isRendererError) {
        val meaningfulMessage = findMostRelevantCauseMessage()
        val decoderHeader = meaningfulMessage ?: context.getString(com.nuvio.tv.R.string.player_error_decoder)
        val unsupported = context.getString(com.nuvio.tv.R.string.player_error_unsupported_format, errorCodeName)
        return "$decoderHeader\n\n$unsupported"
    }

    val meaningfulMessage = findMostRelevantCauseMessage()
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

private inline fun <reified T : Throwable> Throwable.findCauseOfType(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

internal fun Throwable.toDisplayMessage(context: android.content.Context, fallback: String? = null): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage
        ?: message?.takeIf { it.isNotBlank() }
        ?: fallback
        ?: context.getString(com.nuvio.tv.R.string.player_error_playback_fallback)
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}

/**
 * Attempts an automatic retry of the current stream, preserving the playback position.
 *
 * The first retry re-prepares the current player, and the second retry fully rebuilds it,
 * so recovery stays on the loading overlay until playback succeeds or finally fails.
 *
 * Returns `true` if a retry was scheduled, `false` if the error should be shown to the user.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (!isRetryablePlaybackError(error)) return false
    // Dead URLs (non-media body, 404/410) never benefit from same-URL retries;
    // they are handled by attemptDeadSourceFailover before this is reached.
    if (isDeadSourcePlaybackError(error)) return false
    if (errorRetryCount >= MAX_AUTO_RETRIES) return false

    handleParsingErrorFallback(error)

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    // Capture the current position so we can resume after re-init.
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
    val isFirstAttempt = attempt == 0

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = if (isFirstAttempt) false else it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        if (isFirstAttempt) {
            // Lightweight recovery: re-prepare the same source without destroying the player.
            val player = _exoPlayer
            if (player != null) {
                if (savedPosition > 0L) {
                    player.seekTo((savedPosition - 1).coerceAtLeast(0L))
                }
                player.prepare()
                // Only resume playback if the user hadn't paused.
                player.playWhenReady = !paused
            } else {
                releasePlayer(flushPlaybackState = false)
                if (savedPosition > 0L) {
                    _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
                }
                initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
            }
        } else {
            // Full teardown — clears any corrupt decoder/internal state.
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        }
    }
    return true
}

/**
 * Resets the retry counter. Call this whenever playback enters a healthy state
 * (first frame rendered, or user-initiated retry).
 */
internal fun PlayerRuntimeController.resetErrorRetryState() {
    startupRetryCount = 0
    errorRetryCount = 0
    deadSourceFailoverCount = 0
    hasRetriedAfterMimeOverrideClear = false
    pendingAudioPcmFallbackRebuild = false
    errorRetryJob?.cancel()
    errorRetryJob = null
}

internal fun PlayerRuntimeController.scheduleStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = scope.launch {
        delay(STABLE_PROGRESS_RESET_DELAY_MS)
        val player = _exoPlayer ?: return@launch
        if (player.playbackState == Player.STATE_READY && player.isPlaying) {
            resetErrorRetryState()
        }
    }
}

internal fun PlayerRuntimeController.cancelStableProgressReset() {
    stableProgressResetJob?.cancel()
    stableProgressResetJob = null
}

internal fun PlayerRuntimeController.refreshStableProgressResetGate() {
    if (!hasRenderedFirstFrame) return
    val player = _exoPlayer ?: return
    val healthy = player.playbackState == Player.STATE_READY && player.isPlaying
    if (healthy) {
        if (stableProgressResetJob?.isActive != true) {
            scheduleStableProgressReset()
        }
    } else {
        cancelStableProgressReset()
    }
}

/**
 * Silent PCM audio fallback for ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001).
 *
 * When the decoder is set to EXTENSION_RENDERER_MODE_ON (decoderPriority == 1,
 * the default) and tunneling is NOT active, audio passthrough may fail on certain devices/formats.
 * Instead of tearing down and re-building the entire player, we apply an
 * imperceptible speed change (1.00001×) which forces ExoPlayer to decode audio
 * through the software PCM pipeline — identical to what happens when the user
 * manually changes playback speed.
 *
 * This is a one-shot attempt per stream; if it fails again the normal retry
 * logic takes over.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAudioTrackPcmFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    if (cachedDecoderPriority != 1) return false // Only for EXTENSION_RENDERER_MODE_ON
    if (_uiState.value.tunnelingEnabled) return false

    hasTriedAudioPcmFallback = true
    pendingAudioPcmFallbackRebuild = true

    val player = _exoPlayer ?: return false
    val savedPosition = player.currentPosition.takeIf { it > 0L } ?: 0L
    val paused = userPausedManually

    Log.d(PlayerRuntimeController.TAG, "Audio track init failed (5001) — rebuilding player with PCM forcing, position=${savedPosition}ms")
    showRecoveryOverlay()

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }

    return true
}

/**
 * DV7-to-HEVC decoder fallback for ERROR_CODE_DECODER_INIT_FAILED (4003).
 *
 * When decoderPriority == 1 (EXTENSION_RENDERER_MODE_ON) and the decoder
 * fails to initialise, this is often caused by Dolby Vision profile 7
 * content on devices without a DV decoder.  Enabling the DV7-to-HEVC
 * mapping allows the HEVC decoder to handle the stream instead.
 *
 * Unlike the PCM fallback this requires a full player rebuild because
 * the mapping is baked into the renderers factory at build time.
 * Tunneling state does not matter for this fallback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDv7HevcFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (hasTriedDv7HevcFallback) return false
    if (cachedDecoderPriority != 1) return false
    // Skip if DV7-to-HEVC is already active — nothing more we can do.
    if (forceDv7ToHevc) return false

    hasTriedDv7HevcFallback = true
    forceDv7ToHevc = true

    val paused = userPausedManually
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4003) — retrying with DV7-to-HEVC mapping, position=${savedPosition}ms"
    )

    resetErrorRetryState()

    // Show loading overlay with fallback info instead of error screen.
    errorRetryJob = scope.launch {
        showRecoveryOverlay()

        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

/** @return true if a mimeType override was present and has been cleared. */
private fun PlayerRuntimeController.handleParsingErrorFallback(error: PlaybackException): Boolean {
    if (error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
        error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
    ) {
        if (currentStreamMimeType != null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "Parsing error [${error.errorCode}] detected with mimeType=$currentStreamMimeType. " +
                        "Clearing mimeType override for fallback."
            )
            currentStreamMimeType = null
            currentStreamResponseHeaders = emptyMap()
            return true
        }
    }
    return false
}

/**
 * Dead-source classification (community 3003 report).
 *
 * A container-sniff failure where the content is NOT malformed media - media3's
 * [androidx.media3.exoplayer.source.UnrecognizedInputFormatException], surfaced as
 * ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED (3003) - means the body is not media at
 * all: an HTML error page behind HTTP 200, or a .rar/.zip payload from a torrent.
 * HTTP 404/410 are equally permanent for the URL. Same-URL retries only make the
 * user sit through doomed rebuild cycles.
 *
 * Deliberately NOT classified dead: HTTP 429 and timeouts - auto-advancing on a
 * debrid rate limit (TorBox parallel-connection 429s are transient) would wrongly
 * burn perfectly good sources.
 */
internal fun PlayerRuntimeController.isDeadSourcePlaybackError(error: PlaybackException): Boolean {
    val http = error.findInvalidResponseCodeException()
    if (http != null && (http.responseCode == 404 || http.responseCode == 410)) return true
    return error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED &&
        error.findCauseOfType<androidx.media3.exoplayer.source.UnrecognizedInputFormatException>() != null
}

/**
 * Handles a dead-source error: one narrowly-scoped same-URL retry if a mimeType
 * override was steering the extractor (the only genuinely recoverable sub-case),
 * otherwise mark the URL dead for the session and auto-advance to the next source
 * in the user's existing sort order. Returns false when out of options (failover
 * cap reached or no live sources left) so the caller surfaces the error screen.
 */
internal fun PlayerRuntimeController.attemptDeadSourceFailover(
    error: PlaybackException,
    detailedError: String
): Boolean {
    // The override is baked into the MediaItem, so only a FULL re-init applies the
    // clear - a bare prepare() retry cannot (which is why the old auto-retry's first
    // attempt never fixed this case). One full retry, then the URL is treated as dead.
    if (handleParsingErrorFallback(error) && !hasRetriedAfterMimeOverrideClear) {
        hasRetriedAfterMimeOverrideClear = true
        val paused = userPausedManually
        val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead-source check: mimeType override cleared; one full re-init for: $detailedError"
        )
        errorRetryJob?.cancel()
        errorRetryJob = scope.launch {
            _uiState.update {
                it.copy(error = null, showLoadingOverlay = it.loadingOverlayEnabled, showPauseOverlay = false)
            }
            delay(RETRY_DELAY_MS)
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
        }
        return true
    }

    return advanceToNextLiveSource(detailedError)
}

/**
 * Marks the current stream URL dead for this session (greying it in the source
 * panel), then auto-advances to the next live source in the user's existing
 * sort order. Shared by the dead-source path and the startup-exhausted path;
 * both draw on the same MAX_DEAD_SOURCE_FAILOVERS cap. Returns false when out
 * of options (cap reached or no live sources left) so the caller surfaces the
 * error screen.
 */
internal fun PlayerRuntimeController.advanceToNextLiveSource(detailedError: String): Boolean {
    // Mark dead: both the resolved playback URL and, where identifiable, the
    // original list entry (debrid resolution can make these differ) so the
    // source panel greys it and a manual re-pick is visibly discouraged.
    deadSourceStreamUrls.add(currentStreamUrl)
    val state = _uiState.value
    val streams = state.sourceAllStreams
    val currentIdx = findCurrentStreamIndex(
        streams = streams,
        currentStreamInfoHash = state.currentStreamInfoHash,
        currentStreamFileIdx = state.currentStreamFileIdx,
        currentStreamAddonName = state.currentStreamAddonName,
        currentStreamUrl = state.currentStreamUrl,
        currentStreamName = state.currentStreamName
    )
    if (currentIdx >= 0) {
        streams.getOrNull(currentIdx)?.getStreamUrl()?.let { deadSourceStreamUrls.add(it) }
    }
    _uiState.update { it.copy(deadSourceStreamUrls = deadSourceStreamUrls.toSet()) }

    if (deadSourceFailoverCount >= MAX_DEAD_SOURCE_FAILOVERS) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Dead-source failover cap ($MAX_DEAD_SOURCE_FAILOVERS) reached; surfacing error"
        )
        return false
    }
    if (streams.isEmpty()) return false

    val startIdx = if (currentIdx >= 0) currentIdx + 1 else 0
    val next = (startIdx until streams.size).asSequence()
        .map { streams[it] }
        .firstOrNull { candidate ->
            val url = candidate.getStreamUrl()
            url == null || !deadSourceStreamUrls.contains(url)
        } ?: run {
        Log.w(PlayerRuntimeController.TAG, "Dead source and no live sources after index $currentIdx; surfacing error")
        return false
    }

    deadSourceFailoverCount++
    val attemptNo = deadSourceFailoverCount
    Log.w(
        PlayerRuntimeController.TAG,
        "Dead source ($detailedError) - failing over to next source ($attemptNo/$MAX_DEAD_SOURCE_FAILOVERS): " +
                "host=${next.getStreamUrl()?.safeHost()}"
    )
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
    errorRetryJob?.cancel()
    scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showPauseOverlay = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                loadingMessage = context.getString(
                    com.nuvio.tv.R.string.player_dead_source_failover,
                    attemptNo,
                    MAX_DEAD_SOURCE_FAILOVERS
                ),
                pendingSeekPosition = if (savedPosition > 0L) savedPosition else it.pendingSeekPosition
            )
        }
        switchToSourceStream(next)
    }
    return true
}

/**
 * Task 1.6 (Option A): a startup-phase failure that has exhausted the fallback
 * ladders and both same-URL auto-retries advances to the next source instead
 * of surfacing the error screen (previously: one-shot engine failover to MPV
 * when that toggle was enabled, else the error screen). A URL that could not
 * produce a first frame through every recovery path is treated like a dead
 * source for this session.
 */
internal fun PlayerRuntimeController.attemptStartupExhaustedSourceFailover(detailedError: String): Boolean {
    if (hasRenderedFirstFrame) return false
    Log.w(
        PlayerRuntimeController.TAG,
        "Startup recovery exhausted; attempting next-source failover for: $detailedError"
    )
    return advanceToNextLiveSource(detailedError)
}

/**
 * nt6 fix B: consume one unit of the per-stream auto-recovery budget.
 *
 * Self-keys on the current stream URL — a genuine source switch resets the
 * counter without any external reset call. Returns false once the budget is
 * spent, at which point onPlayerError skips every fallback ladder and surfaces
 * the error to the user instead of silently re-preparing again.
 */
internal fun PlayerRuntimeController.consumeAutoRecoveryBudget(detailedError: String): Boolean {
    val url = currentStreamUrl
    if (url != autoRecoveryBudgetUrl) {
        autoRecoveryBudgetUrl = url
        autoRecoveryCountForCurrentStream = 0
    }
    autoRecoveryCountForCurrentStream += 1
    if (autoRecoveryCountForCurrentStream > MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM) {
        Log.w(
            PlayerRuntimeController.TAG,
            "Auto-recovery budget exhausted ($MAX_TOTAL_AUTO_RECOVERIES_PER_STREAM recoveries) " +
                "for host=${url.safeHost()}; surfacing error instead of retrying: $detailedError"
        )
        return false
    }
    return true
}
