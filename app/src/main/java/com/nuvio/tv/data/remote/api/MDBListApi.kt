package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingRequestDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListLastActivitiesDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListPlaybackItemDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleRequestDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListScrobbleResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MDBListApi {
    @GET("user")
    suspend fun getUser(
        @Query("apikey") apiKey: String
    ): Response<Unit>

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

    /** Returns a bare JSON array of paused sessions. */
    @GET("sync/playback")
    suspend fun getPlaybackProgress(
        @Query("apikey") apiKey: String
    ): Response<List<MDBListPlaybackItemDto>>

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
