package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListLastActivitiesDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListPlaybackItemDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleRequestDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListUserDto
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

    /** Accepts either a playback id or the scrobble payload; id is used here. */
    @POST("scrobble/clear")
    suspend fun clearScrobbleSession(
        @Query("apikey") apiKey: String,
        @Body body: Map<String, Long>
    ): Response<Unit>
}
