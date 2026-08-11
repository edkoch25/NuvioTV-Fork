package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListLastActivitiesDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListPlaybackItemDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleRequestDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedAddResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedRemoveResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedWriteDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListUserDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchlistAddResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchlistPageDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchlistRemoveResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchlistWriteDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MDBListApi {
    /**
     * no-cache is load-bearing: this endpoint returns
     * `Cache-Control: public, max-age=900`, so the shared client's disk
     * cache would serve a request count up to fifteen minutes old - and
     * would also validate an API key revoked within that window.
     */
    @Headers("Cache-Control: no-cache")
    @GET("user")
    suspend fun getUser(
        @Query("apikey") apiKey: String
    ): Response<MDBListUserDto>

    @POST("rating/{mediaType}/{ratingType}")
    suspend fun getRating(
        @Path("mediaType") mediaType: String,
        @Path("ratingType") ratingType: String,
        @Query("apikey") apiKey: String,
        @Body body: MDBListRatingRequestDto
    ): Response<MDBListRatingResponseDto>

    @POST("scrobble/start")
    suspend fun scrobbleStart(
        @Query("apikey") apiKey: String,
        @Body body: MDBListScrobbleRequestDto
    ): Response<MDBListScrobbleResponseDto>

    @POST("scrobble/stop")
    suspend fun scrobbleStop(
        @Query("apikey") apiKey: String,
        @Body body: MDBListScrobbleRequestDto
    ): Response<MDBListScrobbleResponseDto>

    /**
     * Returns a bare JSON array of paused sessions.
     *
     * no-cache is load-bearing: the shared OkHttpClient carries a 50 MB disk
     * cache honouring server TTLs, and MDBList's responses are cacheable -
     * measured 2026-07-30, a forced refresh 2.5s after a successful scrobble
     * stop completed in 11ms and returned the pre-stop session list. Sync
     * state must always revalidate with the origin.
     */
    @Headers("Cache-Control: no-cache")
    @GET("sync/playback")
    suspend fun getPlaybackProgress(
        @Query("apikey") apiKey: String
    ): Response<List<MDBListPlaybackItemDto>>

    /** no-cache for the same reason as [getPlaybackProgress]: a cached gate
     *  read reports "nothing changed" against a stale timestamp. */
    @Headers("Cache-Control: no-cache")
    @GET("sync/last_activities")
    suspend fun getLastActivities(
        @Query("apikey") apiKey: String
    ): Response<MDBListLastActivitiesDto>

    /**
     * Watch history, paged. no-cache for the same reason as the other sync
     * reads: a cached page would report a stale watched set, and a watched
     * set that is wrong in the "not watched" direction is the harmful one.
     *
     * No total is returned - page until `has_more` is false.
     */
    /**
     * Marks items watched. Body and behaviour measured 2026-07-31: a
     * two-episode body returns `updated.episodes: 2`, so a season mark is
     * one request rather than one per episode.
     */
    @POST("sync/watched")
    suspend fun addWatched(
        @Query("apikey") apiKey: String,
        @Body body: MDBListWatchedWriteDto
    ): Response<MDBListWatchedAddResponseDto>

    /**
     * Unmarks items. Takes the identical body to [addWatched], but answers
     * with `removed` only and no `not_found`. The count is not a reliable
     * success signal - verify by re-reading.
     */
    @POST("sync/watched/remove")
    suspend fun removeWatched(
        @Query("apikey") apiKey: String,
        @Body body: MDBListWatchedWriteDto
    ): Response<MDBListWatchedRemoveResponseDto>

    @Headers("Cache-Control: no-cache")
    @GET("sync/watched")
    suspend fun getWatched(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int?,
        @Query("cursor") cursor: String?
    ): Response<MDBListWatchedResponseDto>

    /**
     * The user's watchlist, paged. Returns `{ movies, shows, pagination }`.
     * Page by `offset` (documented) until `pagination.has_more` is false.
     *
     * no-cache for the same reason as the other sync reads: a cached page would
     * report a stale library, and stale in the "not present" direction is the
     * harmful one for membership state.
     */
    @Headers("Cache-Control: no-cache")
    @GET("watchlist/items")
    suspend fun getWatchlistItems(
        @Query("apikey") apiKey: String,
        @Query("limit") limit: Int,
        @Query("offset") offset: Int? = null
    ): Response<MDBListWatchlistPageDto>

    /**
     * Adds items to the watchlist. Idempotent: a title already present is
     * reported under `existing`, never duplicated. Body is the flat
     * `{ movies, shows }` shape; imdb alone is a sufficient identity.
     */
    @POST("watchlist/items/add")
    suspend fun addToWatchlist(
        @Query("apikey") apiKey: String,
        @Body body: MDBListWatchlistWriteDto
    ): Response<MDBListWatchlistAddResponseDto>

    /**
     * Removes items from the watchlist. `removed` is not a reliable success
     * signal - verify by re-reading, as with [removeWatched].
     */
    @POST("watchlist/items/remove")
    suspend fun removeFromWatchlist(
        @Query("apikey") apiKey: String,
        @Body body: MDBListWatchlistWriteDto
    ): Response<MDBListWatchlistRemoveResponseDto>

    /** Accepts either a playback id or the scrobble payload; id is used here. */
    @POST("scrobble/clear")
    suspend fun clearScrobbleSession(
        @Query("apikey") apiKey: String,
        @Body body: Map<String, Long>
    ): Response<Unit>
}
