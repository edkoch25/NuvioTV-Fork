package com.nuvio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Read-side shapes for `GET /sync/watched`.
 *
 * Confidence is uneven and worth stating, because the published spec is stale
 * and cannot be trusted here either.
 *
 * **Measured** against a live account on 2026-07-30: the response is an object
 * carrying four arrays - `movies`, `shows`, `seasons`, `episodes` - plus a
 * `pagination` block of `offset`/`limit`/`has_more` and no totals, so a client
 * must page until `has_more` is false. A season entry reads
 * `{last_watched_at, season: {number, name, ids, show: {...}}}` - note the show
 * is nested *inside* the season body, and the season's own ids carry only tmdb,
 * so the IMDb id comes from the nested show. The entry timestamp is
 * `last_watched_at`, not the `season_watched_at` that appears on
 * `/sync/last_activities`.
 *
 * **Unobserved**: the movie, show and episode entry shapes. The account probed
 * had empty arrays for all three, so those types below are modelled on the
 * season entry's convention and on the playback endpoint, which shares
 * [MDBListSyncMovieDto] and [MDBListSyncShowDto] field-for-field. Every field
 * is nullable and the episode entry tolerates the show either nested or as a
 * sibling, so a wrong guess yields an unmapped row that the caller counts and
 * logs rather than a parse-time crash. Confirmation is that counter reading
 * zero against real data.
 */
@JsonClass(generateAdapter = true)
data class MDBListWatchedResponseDto(
    @Json(name = "movies") val movies: List<MDBListWatchedMovieDto>? = null,
    @Json(name = "shows") val shows: List<MDBListWatchedShowDto>? = null,
    @Json(name = "seasons") val seasons: List<MDBListWatchedSeasonDto>? = null,
    @Json(name = "episodes") val episodes: List<MDBListWatchedEpisodeDto>? = null,
    @Json(name = "pagination") val pagination: MDBListPaginationDto? = null
)

/** No total is returned, so `has_more` is the only stop condition. */
@JsonClass(generateAdapter = true)
data class MDBListPaginationDto(
    @Json(name = "offset") val offset: Int? = null,
    @Json(name = "limit") val limit: Int? = null,
    @Json(name = "has_more") val hasMore: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedMovieDto(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "movie") val movie: MDBListSyncMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedShowDto(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "show") val show: MDBListSyncShowDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedSeasonDto(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "season") val season: MDBListWatchedSeasonBodyDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedSeasonBodyDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "name") val name: String? = null,
    /** tmdb only, in the one entry observed - key on [show] instead. */
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null,
    @Json(name = "show") val show: MDBListSyncShowDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedEpisodeDto(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "episode") val episode: MDBListWatchedEpisodeBodyDto? = null,
    /** Sibling placement, tolerated alongside the nested one. */
    @Json(name = "show") val show: MDBListSyncShowDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedEpisodeBodyDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null,
    @Json(name = "show") val show: MDBListSyncShowDto? = null
)
