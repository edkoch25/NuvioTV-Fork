package com.nuvio.tv.data.repository

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListPlaybackItemDto
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads watch progress back from MDBList, the counterpart to
 * [MDBListScrobbleService]'s write path. The [TraktProgressService] analogue,
 * deliberately much smaller: MDBList hands back paused sessions directly from
 * `/sync/playback`, so there is no history reconstruction or episode-number
 * remapping to do.
 *
 * Nothing consumes this yet - it is wired into the repository separately so the
 * client can be proven to compile before anything near Continue Watching moves.
 *
 * **Refresh is event-driven on purpose.** The free tier allows 1,000 requests a
 * day; a five-minute poll would spend 288 of them just asking whether anything
 * changed. Callers invoke [refreshNow] at meaningful moments (app foreground,
 * home refresh, playback end) and every refresh is gated on
 * `/sync/last_activities`, so an unchanged account costs exactly one request.
 */
@Singleton
class MDBListProgressService @Inject constructor(
    private val mdbListApi: MDBListApi,
    private val settingsDataStore: MDBListSettingsDataStore
) {
    companion object {
        private const val TAG = "MDBListProgressSvc"

        /** Matches TraktProgressService's window, so the CW pipeline behaves alike. */
        private const val INITIAL_LOAD_GRACE_PERIOD_MS = 8_000L
    }

    private val remoteProgress = MutableStateFlow<List<WatchProgress>>(emptyList())
    private val hasLoadedRemoteProgress = MutableStateFlow(false)
    private val refreshMutex = Mutex()
    private val serviceStartedAtMs = SystemClock.elapsedRealtime()

    /** Last seen pause-category timestamps, used to skip redundant fetches. */
    private var lastPausedAt: String? = null
    private var lastEpisodePausedAt: String? = null

    /**
     * Continue Watching feed. Mirrors [TraktProgressService.observeAllProgress]'s
     * contract, including suppressing a transient empty emission before the first
     * fetch resolves so the CW pipeline can fall back to its own cache rather
     * than flashing an empty row.
     */
    fun observeAllProgress(): Flow<List<WatchProgress>> {
        val gracePeriodExpired = flow {
            emit(false)
            val remaining = INITIAL_LOAD_GRACE_PERIOD_MS -
                (SystemClock.elapsedRealtime() - serviceStartedAtMs)
            if (remaining > 0) delay(remaining)
            emit(true)
        }.distinctUntilChanged()

        return combine(
            remoteProgress,
            hasLoadedRemoteProgress,
            gracePeriodExpired
        ) { remote, loaded, expired ->
            if (!loaded && !expired && remote.isEmpty()) {
                return@combine null
            }
            remote.sortedByDescending { it.lastWatched }
        }
            .filterNotNull()
            .distinctUntilChanged()
    }

    fun observeRemoteProgressLoaded(): Flow<Boolean> = hasLoadedRemoteProgress

    /**
     * Fetches paused sessions if `/sync/last_activities` says a pause category
     * moved. [force] skips the gate, for an explicit user-driven refresh.
     *
     * Returns true when [remoteProgress] was updated.
     */
    suspend fun refreshNow(force: Boolean = false): Boolean = refreshMutex.withLock {
        val apiKey = activeApiKeyOrNull() ?: return@withLock false

        // Read the change gate without committing it: the stamps advance only
        // after a successful playback fetch, so a transient failure between
        // "changed" and "fetched" can never permanently skip a change. A null
        // snapshot (gate unreadable) degrades to fetching.
        var gateSnapshot: PauseActivitySnapshot? = null
        if (!force) {
            gateSnapshot = fetchPauseActivities(apiKey)
            if (gateSnapshot != null &&
                gateSnapshot.pausedAt == lastPausedAt &&
                gateSnapshot.episodePausedAt == lastEpisodePausedAt
            ) {
                // Nothing paused or resumed since the last successful fetch -
                // one request spent.
                hasLoadedRemoteProgress.value = true
                return@withLock false
            }
        }

        val response = try {
            mdbListApi.getPlaybackProgress(apiKey)
        } catch (e: Exception) {
            Log.w(TAG, "playback fetch failed", e)
            return@withLock false
        }

        if (!response.isSuccessful) {
            if (response.code() == 429) {
                Log.w(TAG, "playback fetch: MDBList daily rate limit exceeded")
            } else {
                Log.w(TAG, "playback fetch failed with code ${response.code()}")
            }
            return@withLock false
        }

        val items = response.body().orEmpty()
        val mapped = items.mapNotNull { mapPlaybackToProgress(it) }
        val skipped = items.size - mapped.size
        if (skipped > 0) {
            // Rows MDBList holds that cannot be keyed the way this app keys watch
            // state. Logged rather than silently dropped so match-rate problems
            // are visible.
            Log.w(TAG, "skipped $skipped of ${items.size} playback rows (no usable IMDb id)")
        }

        remoteProgress.value = mapped
        hasLoadedRemoteProgress.value = true
        // Commit the gate stamps only now that the fetch has succeeded. A
        // forced refresh carries no snapshot; leaving the stamps stale just
        // means the next gated refresh re-fetches once.
        gateSnapshot?.let {
            lastPausedAt = it.pausedAt
            lastEpisodePausedAt = it.episodePausedAt
        }
        return@withLock true
    }

    /** Drops a paused session server-side, removing it from Continue Watching. */
    suspend fun clearPlaybackSession(playbackId: Long): Boolean {
        val apiKey = activeApiKeyOrNull() ?: return false
        val response = try {
            mdbListApi.clearScrobbleSession(apiKey, mapOf("id" to playbackId))
        } catch (e: Exception) {
            Log.w(TAG, "clear session $playbackId failed", e)
            return false
        }
        if (!response.isSuccessful) {
            Log.w(TAG, "clear session $playbackId failed with code ${response.code()}")
            return false
        }
        remoteProgress.value = remoteProgress.value.filterNot { it.mdbListPlaybackId == playbackId }
        return true
    }

    /** Forgets cached state on profile switch or sign-out. */
    fun reset() {
        remoteProgress.value = emptyList()
        hasLoadedRemoteProgress.value = false
        lastPausedAt = null
        lastEpisodePausedAt = null
    }

    private suspend fun activeApiKeyOrNull(): String? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled || !settings.trackingEnabled) return null
        return settings.apiKey.trim().takeIf { it.isNotBlank() }
    }

    /** The pause-category timestamps as read from the gate, uncommitted. */
    private class PauseActivitySnapshot(
        val pausedAt: String?,
        val episodePausedAt: String?
    )

    /**
     * One cheap request that answers "is a fetch worth making?". Returns null
     * when the gate is unreadable, which callers treat as changed so a
     * transient failure degrades to fetching rather than to stale progress.
     */
    private suspend fun fetchPauseActivities(apiKey: String): PauseActivitySnapshot? {
        val response = try {
            mdbListApi.getLastActivities(apiKey)
        } catch (e: Exception) {
            Log.w(TAG, "last_activities failed; assuming changed", e)
            return null
        }
        if (!response.isSuccessful) return null
        val body = response.body() ?: return null
        return PauseActivitySnapshot(
            pausedAt = body.pausedAt,
            episodePausedAt = body.episodePausedAt
        )
    }

    /**
     * Maps one paused session onto [WatchProgress].
     *
     * Returns null when the row cannot be keyed: this app keys watch state on
     * IMDb ids, and although MDBList returned one for every row observed, the
     * field is nullable in their schema.
     */
    internal fun mapPlaybackToProgress(dto: MDBListPlaybackItemDto): WatchProgress? {
        val isEpisode = dto.type?.equals("episode", ignoreCase = true) == true ||
            (dto.episode != null && dto.show != null)

        val contentId = if (isEpisode) dto.show?.ids?.imdb else dto.movie?.ids?.imdb
        if (contentId.isNullOrBlank()) return null

        val percent = dto.progress?.toFloatOrNull()?.coerceIn(0f, 100f) ?: return null

        // runtime arrives in minutes, and is per-episode for episode sessions.
        val durationMs = (dto.runtime ?: 0).coerceAtLeast(0).toLong() * 60_000L
        val positionMs = if (durationMs > 0) {
            (durationMs * (percent / 100f)).toLong()
        } else {
            0L
        }

        val season = dto.episode?.season
        val episodeNumber = dto.episode?.number
        val name = if (isEpisode) dto.show?.title.orEmpty() else dto.movie?.title.orEmpty()

        return WatchProgress(
            contentId = contentId,
            contentType = if (isEpisode) "series" else "movie",
            name = name,
            poster = null,
            backdrop = null,
            logo = null,
            videoId = if (isEpisode && season != null && episodeNumber != null) {
                "$contentId:$season:$episodeNumber"
            } else {
                contentId
            },
            season = season,
            episode = episodeNumber,
            episodeTitle = dto.episode?.title,
            position = positionMs,
            duration = durationMs,
            lastWatched = resolveLastWatchedMs(dto),
            progressPercent = percent,
            source = WatchProgress.SOURCE_MDBLIST_PLAYBACK,
            mdbListPlaybackId = dto.id
        )
    }

    /** Prefers the epoch field; the ISO strings are a fallback. */
    private fun resolveLastWatchedMs(dto: MDBListPlaybackItemDto): Long {
        dto.updatedAtTs?.takeIf { it > 0 }?.let { return it * 1000L }
        parseIsoUtcOrNull(dto.pausedAt)?.let { return it }
        return System.currentTimeMillis()
    }

    private fun parseIsoUtcOrNull(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            runCatching {
                val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                return formatter.parse(value)?.time
            }
        }
        return null
    }
}
