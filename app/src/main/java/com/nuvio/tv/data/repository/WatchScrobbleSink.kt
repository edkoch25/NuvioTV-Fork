package com.nuvio.tv.data.repository

/**
 * A destination for playback scrobbles.
 *
 * Extracted once two real implementations existed - [TraktScrobbleService] and
 * [MDBListScrobbleService] - so the player fan-out iterates over sinks instead
 * of naming each service twice per event. Both implementations self-gate:
 * Trakt on auth state, MDBList on its integration and tracking toggles plus a
 * non-blank key. Call sites therefore stay unconditional, and adding a sink
 * cannot introduce a new branch at the call site.
 *
 * [TraktScrobbleItem] is the input shape for both, because both key on the
 * same imdb/tmdb/trakt ids. The name is historical - it predates the second
 * sink - and is left alone here to keep this extraction behaviourally inert.
 *
 * Not implemented by the external-player return path: StreamScreenViewModel
 * and ExternalPlaybackTracker still call Trakt directly and send nothing to
 * MDBList. That is a pre-existing gap, not a consequence of this extraction,
 * and is left in place because it cannot be exercised on an in-app playback
 * setup. Routing those two sites through this interface is the fix.
 */
interface WatchScrobbleSink {

    /** Human-readable sink name, used in fan-out log messages. */
    val sinkName: String

    /** Reports that playback has begun, or resumed after a seek. */
    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float)

    /**
     * Reports that playback stopped, paused, finished or was exited. Both
     * backends treat a stop at or above their watched threshold as a completed
     * view, and anything below it as a resumable position.
     */
    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float)
}
