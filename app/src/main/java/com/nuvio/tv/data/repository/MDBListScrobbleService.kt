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
    private val profileManager: ProfileManager
) {
    companion object {
        private const val TAG = "MDBListScrobbleSvc"
    }

    private data class ScrobbleStamp(
        val profileId: Int,
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long
    )

    private var lastScrobbleStamp: ScrobbleStamp? = null
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f
    private val maxRetries = 2
    private val retryDelayMs = 1_500L
    private val serverOverloadedRetryDelayMs = 5_000L

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "start", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "stop", item = item, progressPercent = progressPercent)
    }

    private suspend fun sendScrobble(
        action: String,
        item: TraktScrobbleItem,
        progressPercent: Float
    ) {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled || !settings.trackingEnabled) return
        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) return

        val activeProfileId = profileManager.activeProfileId.value
        val clampedProgress = progressPercent.coerceIn(0f, 100f)
        if (shouldSkip(activeProfileId, action, item.itemKey, clampedProgress)) return

        val requestBody = buildRequestBody(item, clampedProgress)

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
                lastScrobbleStamp = ScrobbleStamp(
                    profileId = activeProfileId,
                    action = action,
                    itemKey = item.itemKey,
                    progress = clampedProgress,
                    timestampMs = System.currentTimeMillis()
                )
                return
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
        return when (item) {
            is TraktScrobbleItem.Movie -> MDBListScrobbleRequestDto(
                movie = MDBListScrobbleMovieDto(ids = toIds(item.ids)),
                progress = clampedProgress,
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
                progress = clampedProgress,
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

    private fun shouldSkip(profileId: Int, action: String, itemKey: String, progress: Float): Boolean {
        val last = lastScrobbleStamp ?: return false
        val now = System.currentTimeMillis()
        val isSameWindow = now - last.timestampMs < minSendIntervalMs
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
