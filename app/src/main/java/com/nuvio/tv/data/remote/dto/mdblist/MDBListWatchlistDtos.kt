package com.nuvio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Read/write shapes for MDBList's watchlist endpoints, probed live on
 * 2026-08-10 against account `pwr` rather than taken from the published spec.
 *
 * Two things the spec got wrong that matter here:
 *  - The write body is **flat** (`{"movies":[{"imdb":..,"tmdb":..}]}`), NOT the
 *    nested `{"ids":{..}}` + `seasons` shape the `/sync/watched` writes use.
 *  - `tmdb` is NOT required: a movie added by `imdb` alone resolved and came
 *    back with a full id block, so imdb is a sufficient identity.
 *
 * Reused from elsewhere in this package: the read item's `ids` block is
 * [MDBListSyncIdsDto], and the page cursor is [MDBListPaginationDto] (defined in
 * MDBListWatchedDtos.kt). The watchlist response also returns a scalar `total`,
 * which that shared type does not model; Moshi ignores it and paging relies on
 * `has_more` / `next_cursor` / `offset`, so counts are taken locally.
 */

// ---- Read: GET /watchlist/items -> { movies:[], shows:[], pagination:{} } ----

@JsonClass(generateAdapter = true)
data class MDBListWatchlistItemDto(
    /** tmdb id (mirrors ids.tmdb). */
    @Json(name = "id") val id: Int? = null,
    /** "movie" | "show". */
    @Json(name = "mediatype") val mediatype: String? = null,
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "ids") val ids: MDBListSyncIdsDto? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "release_year") val releaseYear: Int? = null,
    /** ISO-8601 with millis + Z, e.g. "2026-06-25T10:20:41.000Z". */
    @Json(name = "watchlist_at") val watchlistAt: String? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchlistPageDto(
    @Json(name = "movies") val movies: List<MDBListWatchlistItemDto>? = null,
    @Json(name = "shows") val shows: List<MDBListWatchlistItemDto>? = null,
    @Json(name = "pagination") val pagination: MDBListPaginationDto? = null
)

// ---- Write: POST /watchlist/items/add | /watchlist/items/remove ----

@JsonClass(generateAdapter = true)
data class MDBListWatchlistWriteItemDto(
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Int? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchlistWriteDto(
    @Json(name = "movies") val movies: List<MDBListWatchlistWriteItemDto>? = null,
    @Json(name = "shows") val shows: List<MDBListWatchlistWriteItemDto>? = null
)

/** Per-category counts, `{ "movies": Int, "shows": Int }`. */
@JsonClass(generateAdapter = true)
data class MDBListWatchlistCountsDto(
    @Json(name = "movies") val movies: Int? = null,
    @Json(name = "shows") val shows: Int? = null
)

/**
 * POST /watchlist/items/add -> `{ added, existing, not_found }`.
 * `existing` reports titles already on the watchlist (add is idempotent);
 * `not_found` reports ids MDBList could not resolve (the match-rate signal).
 */
@JsonClass(generateAdapter = true)
data class MDBListWatchlistAddResponseDto(
    @Json(name = "added") val added: MDBListWatchlistCountsDto? = null,
    @Json(name = "existing") val existing: MDBListWatchlistCountsDto? = null,
    @Json(name = "not_found") val notFound: MDBListWatchlistCountsDto? = null
)

/**
 * POST /watchlist/items/remove -> `{ removed, not_found }`.
 * `removed` is NOT a reliable success signal (same rule as /sync/watched/remove)
 * - verify by re-reading the watchlist.
 */
@JsonClass(generateAdapter = true)
data class MDBListWatchlistRemoveResponseDto(
    @Json(name = "removed") val removed: MDBListWatchlistCountsDto? = null,
    @Json(name = "not_found") val notFound: MDBListWatchlistCountsDto? = null
)
