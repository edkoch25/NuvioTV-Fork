package com.nuvio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Read-side shapes for MDBList's sync endpoints (https://api.mdblist.com/docs/).
 *
 * These were derived by probing a live account rather than from the published
 * spec, which is five months stale and wrong in several places that matter here:
 *
 *  - `progress` is a JSON **string** (`"45.00"`), not a number. Typed as Float,
 *    Moshi throws at parse time.
 *  - `runtime` is present (the spec omits it) and is in **minutes**.
 *  - Id keys on the read side are `imdb`/`tmdb`/`trakt`/`tvdb`/`mdblist` - the
 *    same as the write side, not the `imdbid`/`tmdbid` the spec shows.
 *  - `updated_at_ts`, `progress_at_update`, `expires_at` and `is_manual` are
 *    undocumented but returned.
 *
 * `/sync/playback` returns a bare JSON array, so it is consumed as List<T>.
 */
@JsonClass(generateAdapter = true)
data class MDBListPlaybackItemDto(
    /** Playback session id. Accepted directly by POST /scrobble/clear. */
    @Json(name = "id") val id: Long? = null,
    /** Percentage 0-100, serialised as a string. */
    @Json(name = "progress") val progress: String? = null,
    @Json(name = "paused_at") val pausedAt: String? = null,
    /** Epoch seconds. Preferred over the ISO strings when present. */
    @Json(name = "updated_at_ts") val updatedAtTs: Long? = null,
    /** Minutes, not milliseconds. Per-episode for episode sessions. */
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "is_manual") val isManual: Boolean? = null,
    /** "movie" or "episode". */
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: MDBListSyncMovieDto? = null,
    @Json(name = "episode") val episode: MDBListSyncEpisodeDto? = null,
    @Json(name = "show") val show: MDBListSyncShowDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListSyncMovieDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListSyncShowDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListSyncEpisodeDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListSyncIdsDto(
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Int? = null,
    @Json(name = "trakt") val trakt: Int? = null,
    @Json(name = "tvdb") val tvdb: Int? = null,
    @Json(name = "mdblist") val mdblist: String? = null
)

/**
 * Per-category change timestamps. Clients call this first and fetch only the
 * categories whose timestamp moved - the mechanism that makes tracking viable
 * inside the free tier's 1,000 requests/day.
 *
 * Verified live: the timestamps are type-split. A movie scrobble moves
 * `paused_at`; an episode scrobble moves `episode_paused_at`; marking an
 * episode watched moves `episode_watched_at` while leaving `watched_at` null.
 * `journal_at` is undocumented but returned.
 */
@JsonClass(generateAdapter = true)
data class MDBListLastActivitiesDto(
    @Json(name = "watchlisted_at") val watchlistedAt: String? = null,
    @Json(name = "watched_at") val watchedAt: String? = null,
    @Json(name = "season_watched_at") val seasonWatchedAt: String? = null,
    @Json(name = "episode_watched_at") val episodeWatchedAt: String? = null,
    @Json(name = "rated_at") val ratedAt: String? = null,
    @Json(name = "journal_at") val journalAt: String? = null,
    @Json(name = "collected_at") val collectedAt: String? = null,
    @Json(name = "dropped_at") val droppedAt: String? = null,
    @Json(name = "paused_at") val pausedAt: String? = null,
    @Json(name = "episode_paused_at") val episodePausedAt: String? = null,
    @Json(name = "list_updated_at") val listUpdatedAt: String? = null
)
