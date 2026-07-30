package com.nuvio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Shapes for `GET /sync/watched`, measured live on 2026-07-30. All four entry
 * types have now been observed with real rows, so nothing here is inferred.
 *
 * The response is one stream ordered by `last_watched_at` descending, chunked
 * into four arrays per page - `limit` caps the *combined* row count, not each
 * array. Adding a newer movie was observed pushing an older show row onto the
 * next page, so no category is complete until pagination finishes.
 *
 * One watched episode produces up to three rows: the episode, a season rollup,
 * and a show rollup. A `shows` entry therefore means "has watched activity",
 * NOT "series finished" - FROM appears with 40 aired episodes and one watched.
 */
@JsonClass(generateAdapter = true)
data class MDBListWatchedResponseDto(
    @Json(name = "movies") val movies: List<MDBListWatchedMovieDto>? = null,
    @Json(name = "shows") val shows: List<MDBListWatchedShowDto>? = null,
    @Json(name = "seasons") val seasons: List<MDBListWatchedSeasonDto>? = null,
    @Json(name = "episodes") val episodes: List<MDBListWatchedEpisodeDto>? = null,
    @Json(name = "pagination") val pagination: MDBListPaginationDto? = null
)

/**
 * Totals appear only on the final page (when `has_more` is false); the cursor
 * appears only while more remain. The cursor is a keyset over
 * `{ts, type, id}` - preferred over `offset`, which also works but shifts if a
 * scrobble lands mid-pagination.
 */
@JsonClass(generateAdapter = true)
data class MDBListPaginationDto(
    @Json(name = "offset") val offset: Int? = null,
    @Json(name = "limit") val limit: Int? = null,
    @Json(name = "has_more") val hasMore: Boolean? = null,
    @Json(name = "next_cursor") val nextCursor: String? = null,
    @Json(name = "total_movies") val totalMovies: Int? = null,
    @Json(name = "total_shows") val totalShows: Int? = null,
    @Json(name = "total_seasons") val totalSeasons: Int? = null,
    @Json(name = "total_episodes") val totalEpisodes: Int? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedMovieDto(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "movie") val movie: MDBListSyncMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedShowDto(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "show") val show: MDBListWatchedShowBodyDto? = null
)

/**
 * Richer than [MDBListSyncShowDto], which stays in use for the show nested
 * inside season and episode entries - those carry only title, year and ids.
 * `total_aired_episodes` is the useful one: it gives an episode count without
 * a metadata round-trip per show.
 */
@JsonClass(generateAdapter = true)
data class MDBListWatchedShowBodyDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "total_aired_episodes") val totalAiredEpisodes: Int? = null
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
    /** tmdb only - identity comes from [show]. */
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null,
    @Json(name = "show") val show: MDBListSyncShowDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedEpisodeDto(
    @Json(name = "last_watched_at") val lastWatchedAt: String? = null,
    @Json(name = "episode") val episode: MDBListWatchedEpisodeBodyDto? = null
)

/**
 * The episode title field is `name`, not `title` - the playback endpoint uses
 * `title` for the same concept. Episode ids carry tmdb and tvdb only, so
 * identity is show IMDb plus season and number, taken from [show].
 */
@JsonClass(generateAdapter = true)
data class MDBListWatchedEpisodeBodyDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "name") val name: String? = null,
    /** Absolute TMDB URL, unlike /upnext which returns bare paths. */
    @Json(name = "still") val still: String? = null,
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null,
    @Json(name = "show") val show: MDBListSyncShowDto? = null
)
