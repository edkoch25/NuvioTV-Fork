package com.nuvio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Write-side shapes for `POST /sync/watched` and `POST /sync/watched/remove`.
 *
 * Measured live on 2026-07-31 against account `pwr-tz20fa`, not taken from the
 * published spec. Both endpoints accept the identical Trakt-shaped body:
 *
 * ```
 * {"movies":[{"ids":{"imdb":"tt0111161"}}]}
 * {"shows":[{"ids":{"imdb":"tt9813792"},
 *            "seasons":[{"number":1,"episodes":[{"number":2},{"number":3}]}]}]}
 * ```
 *
 * A two-episode body returned `updated.episodes: 2`, so a season mark is one
 * request rather than one per episode. Identity is IMDb: that is what was
 * measured, and tmdb was not, so the client sends imdb only.
 */
@JsonClass(generateAdapter = true)
data class MDBListWatchedWriteDto(
    @Json(name = "movies") val movies: List<MDBListWriteMovieDto>? = null,
    @Json(name = "shows") val shows: List<MDBListWriteShowDto>? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWriteIdsDto(
    @Json(name = "imdb") val imdb: String
)

@JsonClass(generateAdapter = true)
data class MDBListWriteMovieDto(
    @Json(name = "ids") val ids: MDBListWriteIdsDto
)

@JsonClass(generateAdapter = true)
data class MDBListWriteShowDto(
    @Json(name = "ids") val ids: MDBListWriteIdsDto,
    @Json(name = "seasons") val seasons: List<MDBListWriteSeasonDto>
)

@JsonClass(generateAdapter = true)
data class MDBListWriteSeasonDto(
    @Json(name = "number") val number: Int,
    @Json(name = "episodes") val episodes: List<MDBListWriteEpisodeDto>
)

@JsonClass(generateAdapter = true)
data class MDBListWriteEpisodeDto(
    @Json(name = "number") val number: Int
)

/** Per-category counts, returned by both write endpoints. */
@JsonClass(generateAdapter = true)
data class MDBListWatchedCountsDto(
    @Json(name = "movies") val movies: Int? = null,
    @Json(name = "shows") val shows: Int? = null,
    @Json(name = "seasons") val seasons: Int? = null,
    @Json(name = "episodes") val episodes: Int? = null
)

/**
 * `POST /sync/watched`. `not_found` is per-category and usable as a match-rate
 * signal; `updated` was observed accurate on both single and batch writes.
 */
@JsonClass(generateAdapter = true)
data class MDBListWatchedAddResponseDto(
    @Json(name = "updated") val updated: MDBListWatchedCountsDto? = null,
    @Json(name = "not_found") val notFound: MDBListWatchedNotFoundDto? = null
)

@JsonClass(generateAdapter = true)
data class MDBListWatchedNotFoundDto(
    @Json(name = "movies") val movies: List<Any>? = null,
    @Json(name = "shows") val shows: List<Any>? = null,
    @Json(name = "seasons") val seasons: List<Any>? = null,
    @Json(name = "episodes") val episodes: List<Any>? = null
)

/**
 * `POST /sync/watched/remove`. Asymmetric with the add: no `not_found` block.
 *
 * **`removed` must never be surfaced as a result.** A removal reporting
 * `movies: 0` was observed to have succeeded (2026-07-30), so the count is not
 * a reliable success signal in either direction. Verify by re-reading.
 */
@JsonClass(generateAdapter = true)
data class MDBListWatchedRemoveResponseDto(
    @Json(name = "removed") val removed: MDBListWatchedCountsDto? = null
)
