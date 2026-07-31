package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleEpisodeDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleIdsDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleMovieDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleRequestDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleSeasonDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleShowDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Sends playback scrobbles to MDBList (https://api.mdblist.com), mirroring
 * [TraktScrobbleService]'s structure so the two sinks stay behaviourally
 * comparable: same dedup stamp, same retry policy for stop actions, same
 * clamping. Reuses [TraktScrobbleItem] as the input shape because both
 * services key on the same imdb/tmdb/trakt ids; a shared sink interface can
 * be extracted once both implementations have settled.
 *
 * Gated on the per-profile MDBList settings: the integration master toggle,
 * the tracking toggle, and a non-blank API key. All checks happen here so
 * call sites stay unconditional, matching how the Trakt service self-gates
 * on auth state.
 *
 * MDBList semantics (from the published API blueprint): stop at >= 80%
 * marks the item watched; stop below 80% is saved as a paused session, so
 * pause events are routed through scrobbleStop exactly as the Trakt path
 * does. A 404 means the title is not in MDBList's database - logged
 * distinctly and never retried. A 429 is the daily rate limit - logged and
 * never retried within a play.
 */
@Singleton
class MDBListScrobbleService @Inject constructor(
    private val mdbListApi: MDBListApi,
    private val settingsDataStore: MDBListSettingsDataStore,
    private val mdbListProgressService: MDBListProgressService,
    private val profileManager: ProfileManager
) : WatchScrobbleSink {
    companion object {
        private const val TAG = "MDBListScrobbleSvc"

        /** Measured server-side session-commit latency (~1.5s) plus margin. */
        private const val POST_STOP_REFRESH_DELAY_MS = 2_500L
    }

    internal data class ScrobbleStamp(
        val profileId: Int,
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long
    )

    private var lastScrobbleStamp: ScrobbleStamp? = null

    /**
     * The last action actually dispatched, stamped before the request goes out.
     *
     * [lastScrobbleStamp] commits only on a successful response, so for the
     * duration of a start's round trip it still names the action that preceded
     * it. Without this second stamp a stop evaluated inside that window has no
     * way to see the in-flight start.
     */
    private var lastIssuedStamp: ScrobbleStamp? = null
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f
    private val maxRetries = 2
    private val retryDelayMs = 1_500L
    private val serverOverloadedRetryDelayMs = 5_000L

    override val sinkName: String = "MDBList"

    override suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "start", item = item, progressPercent = progressPercent)
    }

    override suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "stop", item = item, progressPercent = progressPercent)
    }

    private suspend fun sendScrobble(
        action: String,
        item: TraktScrobbleItem,
        progressPercent: Float
    ) {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled || !settings.trackingEnabled) {
            Log.d(TAG, "skip $action: gated (enabled=${settings.enabled} tracking=${settings.trackingEnabled})")
            return
        }
        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) {
            Log.d(TAG, "skip $action: no api key")
            return
        }

        val activeProfileId = profileManager.activeProfileId.value
        val clampedProgress = progressPercent.coerceIn(0f, 100f)
        if (shouldSkip(activeProfileId, action, item.itemKey, clampedProgress)) {
            Log.d(TAG, "skip $action: dedup window (${item.itemKey} @ $clampedProgress)")
            return
        }

        val requestBody = buildRequestBody(item, clampedProgress)
        Log.d(TAG, "send $action ${item.itemKey} progress=${requestBody.progress}")

        // Stamped before dispatch, not on success: shouldSkip has to be able to
        // see a start that has been sent but not yet acknowledged.
        lastIssuedStamp = ScrobbleStamp(
            profileId = activeProfileId,
            action = action,
            itemKey = item.itemKey,
            progress = clampedProgress,
            timestampMs = System.currentTimeMillis()
        )

        var lastException: Exception? = null
        val attempts = if (action == "stop") maxRetries + 1 else 1

        for (attempt in 1..attempts) {
            val response = try {
                when (action) {
                    "start" -> mdbListApi.scrobbleStart(apiKey, requestBody)
                    else -> mdbListApi.scrobbleStop(apiKey, requestBody)
                }
            } catch (e: IOException) {
                lastException = e
                if (attempt < attempts) {
                    Log.w(TAG, "Scrobble $action attempt $attempt failed (IO), retrying", e)
                    delay(retryDelayMs * attempt)
                    continue
                }
                null
            }

            if (response == null) {
                if (attempt < attempts) {
                    Log.w(TAG, "Scrobble $action attempt $attempt returned null, retrying")
                    delay(retryDelayMs * attempt)
                    continue
                }
                Log.w(TAG, "Scrobble $action failed after $attempts attempts", lastException)
                return
            }

            if (response.isSuccessful) {
                Log.d(TAG, "$action ok (${response.code()}) ${item.itemKey}")
                lastScrobbleStamp = ScrobbleStamp(
                    profileId = activeProfileId,
                    action = action,
                    itemKey = item.itemKey,
                    progress = clampedProgress,
                    timestampMs = System.currentTimeMillis()
                )
                if (action == "stop") {
                    // A stop changes the paused-session set server-side, but the
                    // row commits asynchronously: measured 2026-07-30, a session's
                    // paused_at postdated its own stop's 200 by ~1.5s, so an
                    // immediate refresh raced it (stale gate -> skip, or stale
                    // list -> the row missed and the fresh stamp wrongly
                    // committed). Wait out the commit, then force - the forced
                    // path reads no gate snapshot and commits no stamps, so the
                    // race cannot suppress later refreshes; the next gated
                    // refresh simply re-fetches once.
                    delay(POST_STOP_REFRESH_DELAY_MS)
                    mdbListProgressService.refreshNow(force = true)
                }
                return
            }

            // The rejection body names the offending field when present; logging
            // only the code discarded that during the 2026-07-30 investigation
            // (this particular 400 arrives with an empty body regardless).
            if (!response.isSuccessful) {
                val errorBody = runCatching { response.errorBody()?.string() }.getOrNull()
                Log.w(TAG, "$action rejected ${response.code()}: ${errorBody?.take(500) ?: "(no body)"}")
            }

            // Title not matched in MDBList's database - not retryable, and worth
            // a distinct log line so match-rate problems are visible in logcat.
            if (response.code() == 404) {
                Log.w(TAG, "Scrobble $action: item not in MDBList database (${item.itemKey})")
                return
            }

            // Daily rate limit - retrying within this play cannot succeed.
            if (response.code() == 429) {
                Log.w(TAG, "Scrobble $action: MDBList daily rate limit exceeded")
                return
            }

            // Server error (5xx) - retry for stop actions
            if (response.code() in 500..599 && attempt < attempts) {
                val delayMs = if (response.code() in 502..504) {
                    serverOverloadedRetryDelayMs
                } else {
                    retryDelayMs * attempt
                }
                Log.w(TAG, "Scrobble $action attempt $attempt got ${response.code()}, retrying in ${delayMs}ms")
                delay(delayMs)
                continue
            }

            // Non-retryable error
            Log.w(TAG, "Scrobble $action failed with code ${response.code()}")
            return
        }
    }

    internal fun buildRequestBody(
        item: TraktScrobbleItem,
        clampedProgress: Float
    ): MDBListScrobbleRequestDto {
        // MDBList rejects progress carrying more than two decimal places with a
        // bare 400 (measured 2026-07-30: 3.0654762 -> 400, 3.07 -> 200 on an
        // otherwise identical payload; the July validation only ever sent 1-2dp
        // literals, which is why it passed). Moshi writes Float.toString - up to
        // seven significant digits - so the wire value is truncated to two
        // decimals. Truncated, not rounded: truncation can never push a sub-80%
        // stop across the server's watched threshold nor a sub-1% value across
        // its ignore floor (proven by exhaustive output-domain and ULP-boundary
        // sweep, 2026-07-30).
        val wireProgress = (clampedProgress * 100f).toInt() / 100f
        return when (item) {
            is TraktScrobbleItem.Movie -> MDBListScrobbleRequestDto(
                movie = MDBListScrobbleMovieDto(ids = toIds(item.ids)),
                progress = wireProgress,
                appVersion = BuildConfig.VERSION_NAME
            )

            is TraktScrobbleItem.Episode -> MDBListScrobbleRequestDto(
                show = MDBListScrobbleShowDto(
                    ids = toIds(item.showIds),
                    season = MDBListScrobbleSeasonDto(
                        number = item.season,
                        episode = MDBListScrobbleEpisodeDto(number = item.number)
                    )
                ),
                progress = wireProgress,
                appVersion = BuildConfig.VERSION_NAME
            )
        }
    }

    private fun toIds(ids: com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto): MDBListScrobbleIdsDto =
        MDBListScrobbleIdsDto(
            imdb = ids.imdb,
            tmdb = ids.tmdb,
            trakt = ids.trakt
        )

    private fun shouldSkip(profileId: Int, action: String, itemKey: String, progress: Float): Boolean =
        shouldSkipDecision(
            last = lastScrobbleStamp,
            issued = lastIssuedStamp,
            nowMs = System.currentTimeMillis(),
            profileId = profileId,
            action = action,
            itemKey = itemKey,
            progress = progress
        )

    /**
     * The dedup decision, split out from the mutable stamps so it can be tested
     * directly instead of through a full request round trip. [nowMs] is passed in
     * for the same reason.
     */
    internal fun shouldSkipDecision(
        last: ScrobbleStamp?,
        issued: ScrobbleStamp?,
        nowMs: Long,
        profileId: Int,
        action: String,
        itemKey: String,
        progress: Float
    ): Boolean {
        // Never skip a stop while a start for the same item is still in flight.
        // Measured 2026-07-31: stop 200 at 21:28:22.832, start dispatched at
        // 21:28:23.986, stop evaluated and skipped at 21:28:24.156, start 201 at
        // 21:28:24.303. The skipped stop was the last of the play, so MDBList kept
        // the item marked playing, its paused session stayed hidden, and the title
        // dropped out of Continue Watching having lost its resume position.
        if (action == "stop" && issued != null && issued.action == "start" &&
            issued.itemKey == itemKey && issued.profileId == profileId
        ) {
            return false
        }

        if (last == null) return false
        val isSameWindow = nowMs - last.timestampMs < minSendIntervalMs
        val isSameProfile = last.profileId == profileId
        val isSameAction = last.action == action
        val isSameItem = last.itemKey == itemKey
        val isNearProgress = abs(last.progress - progress) <= progressWindow

        // Never skip a stop if the last successful action was start - MDBList
        // needs to know playback ended regardless of timing.
        if (action == "stop" && last.action == "start" && isSameItem && isSameProfile) {
            return false
        }

        return isSameWindow && isSameProfile && isSameAction && isSameItem && isNearProgress
    }
}
