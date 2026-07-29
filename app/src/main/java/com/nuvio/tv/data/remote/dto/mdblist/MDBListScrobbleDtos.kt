package com.nuvio.tv.data.remote.dto.mdblist

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Request/response shapes for MDBList's scrobble endpoints
 * (https://api.mdblist.com/docs/). Unlike Trakt's payload, the episode is
 * nested inside show.season.episode rather than being a sibling object.
 * Matching is by ids only; title/year are not part of the request.
 */
@JsonClass(generateAdapter = true)
data class MDBListScrobbleRequestDto(
    @Json(name = "movie") val movie: MDBListScrobbleMovieDto? = null,
    @Json(name = "show") val show: MDBListScrobbleShowDto? = null,
    @Json(name = "progress") val progress: Float,
    @Json(name = "app_version") val appVersion: String? = null
)

@JsonClass(generateAdapter = true)
data class MDBListScrobbleMovieDto(
    @Json(name = "ids") val ids: MDBListScrobbleIdsDto
)

@JsonClass(generateAdapter = true)
data class MDBListScrobbleShowDto(
    @Json(name = "ids") val ids: MDBListScrobbleIdsDto,
    @Json(name = "season") val season: MDBListScrobbleSeasonDto
)

@JsonClass(generateAdapter = true)
data class MDBListScrobbleSeasonDto(
    @Json(name = "number") val number: Int,
    @Json(name = "episode") val episode: MDBListScrobbleEpisodeDto
)

@JsonClass(generateAdapter = true)
data class MDBListScrobbleEpisodeDto(
    @Json(name = "number") val number: Int
)

@JsonClass(generateAdapter = true)
data class MDBListScrobbleIdsDto(
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "tmdb") val tmdb: Int? = null,
    @Json(name = "trakt") val trakt: Int? = null
)

@JsonClass(generateAdapter = true)
data class MDBListScrobbleResponseDto(
    @Json(name = "action") val action: String? = null
)
