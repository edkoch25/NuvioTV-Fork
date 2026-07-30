package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListPaginationDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncIdsDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncMovieDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncShowDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedEpisodeBodyDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedEpisodeDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedMovieDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedShowBodyDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedShowDto
import com.nuvio.tv.domain.model.MDBListSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Fixtures are the exact payloads a live account returned on 2026-07-30.
 */
class MDBListWatchedServiceTest {

    private val fromEpisode = MDBListWatchedEpisodeDto(
        lastWatchedAt = "2026-07-30T21:19:57.000Z",
        episode = MDBListWatchedEpisodeBodyDto(
            season = 1,
            number = 1,
            name = "Long Day's Journey Into Night",
            still = "https://image.tmdb.org/t/p/w200/6VKsznJrIq1q87Hot5EEPuvub1g.jpg",
            ids = MDBListSyncIdsDto(tmdb = 2910462, tvdb = 8808273),
            show = MDBListSyncShowDto(
                title = "FROM",
                year = 2022,
                ids = MDBListSyncIdsDto(imdb = "tt9813792", tmdb = 124364, trakt = 188205, mdblist = "2thgy")
            )
        )
    )

    private val fromShow = MDBListWatchedShowDto(
        lastWatchedAt = "2026-07-30T21:19:57.000Z",
        show = MDBListWatchedShowBodyDto(
            title = "FROM",
            year = 2022,
            ids = MDBListSyncIdsDto(imdb = "tt9813792", tmdb = 124364, trakt = 188205, mdblist = "2thgy"),
            status = "Returning Series",
            releaseDate = "2022-02-20",
            runtime = 2047,
            totalAiredEpisodes = 40
        )
    )

    private val michaelMovie = MDBListWatchedMovieDto(
        lastWatchedAt = "2026-07-30T21:43:11.000Z",
        movie = MDBListSyncMovieDto(
            title = "Michael",
            year = 2026,
            ids = MDBListSyncIdsDto(imdb = "tt11378946", tmdb = 936075, trakt = 751145, mdblist = "2vg3u")
        )
    )

    private fun serviceWith(api: MDBListApi, enabled: Boolean = true, tracking: Boolean = true): MDBListWatchedService {
        val settings = mockk<MDBListSettingsDataStore>()
        every { settings.settings } returns flowOf(
            MDBListSettings(enabled = enabled, apiKey = "k", trackingEnabled = tracking)
        )
        return MDBListWatchedService(mdbListApi = api, settingsDataStore = settings)
    }

    private fun lastPage(
        movies: List<MDBListWatchedMovieDto> = emptyList(),
        shows: List<MDBListWatchedShowDto> = emptyList(),
        episodes: List<MDBListWatchedEpisodeDto> = emptyList()
    ) = Response.success(
        MDBListWatchedResponseDto(
            movies = movies, shows = shows, seasons = emptyList(), episodes = episodes,
            pagination = MDBListPaginationDto(
                offset = 0, limit = PAGE, hasMore = false,
                totalMovies = movies.size, totalShows = shows.size,
                totalSeasons = 0, totalEpisodes = episodes.size
            )
        )
    )

    private val PAGE = MDBListWatchedService.PAGE_SIZE

    @Test
    fun `the live payload survives the round trip with identity intact`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), any(), any()) } returns
            lastPage(movies = listOf(michaelMovie), shows = listOf(fromShow), episodes = listOf(fromEpisode))

        val result = serviceWith(api).fetchAllWatched()!!
        assertEquals(3, result.totalRows)
        assertTrue(result.complete)
        // Episode identity is the show's IMDb id plus season and number.
        val ep = result.episodes.single()
        assertEquals("tt9813792", ep.episode?.show?.ids?.imdb)
        assertEquals(1, ep.episode?.season)
        assertEquals(1, ep.episode?.number)
        // The title field is `name` - mapping it as `title` silently lost it.
        assertEquals("Long Day's Journey Into Night", ep.episode?.name)
        assertNull(ep.episode?.ids?.imdb)
        // Episode counts arrive with the show, saving a metadata lookup.
        assertEquals(40, result.shows.single().show?.totalAiredEpisodes)
        assertEquals("tt11378946", result.movies.single().movie?.ids?.imdb)
    }

    @Test
    fun `paging follows the cursor when one is offered`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), offset = 0, cursor = null) } returns Response.success(
            MDBListWatchedResponseDto(
                episodes = listOf(fromEpisode),
                pagination = MDBListPaginationDto(hasMore = true, nextCursor = "CURSOR1")
            )
        )
        coEvery { api.getWatched(any(), any(), offset = null, cursor = "CURSOR1") } returns
            lastPage(episodes = listOf(fromEpisode))

        val result = serviceWith(api).fetchAllWatched()!!
        assertEquals(2, result.pagesFetched)
        assertEquals(2, result.episodes.size)
        // The cursor page must not also carry an offset, or the server sees both.
        coVerify(exactly = 1) { api.getWatched(any(), PAGE, null, "CURSOR1") }
    }

    @Test
    fun `paging falls back to offset when no cursor is returned`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), offset = 0, cursor = null) } returns Response.success(
            MDBListWatchedResponseDto(
                episodes = listOf(fromEpisode),
                pagination = MDBListPaginationDto(hasMore = true, nextCursor = null)
            )
        )
        coEvery { api.getWatched(any(), any(), offset = PAGE, cursor = null) } returns
            lastPage(episodes = listOf(fromEpisode))

        assertEquals(2, serviceWith(api).fetchAllWatched()!!.pagesFetched)
        coVerify(exactly = 1) { api.getWatched(any(), PAGE, PAGE, null) }
    }

    @Test
    fun `totals that disagree with what was collected mark the fetch incomplete`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), any(), any()) } returns Response.success(
            MDBListWatchedResponseDto(
                episodes = listOf(fromEpisode),
                pagination = MDBListPaginationDto(
                    hasMore = false, totalMovies = 0, totalShows = 0,
                    totalSeasons = 0, totalEpisodes = 7
                )
            )
        )

        val result = serviceWith(api).fetchAllWatched()!!
        // Rows are still returned - the caller decides what to do with a set
        // the server says is short.
        assertEquals(1, result.episodes.size)
        assertFalse(result.complete)
    }

    @Test
    fun `a failure on a later page discards the whole fetch`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), offset = 0, cursor = null) } returns Response.success(
            MDBListWatchedResponseDto(
                episodes = listOf(fromEpisode),
                pagination = MDBListPaginationDto(hasMore = true, nextCursor = "CURSOR1")
            )
        )
        coEvery { api.getWatched(any(), any(), offset = null, cursor = "CURSOR1") } returns
            Response.error(500, "".toResponseBody(null))

        assertNull(serviceWith(api).fetchAllWatched())
    }

    @Test
    fun `a missing pagination block is treated as the last page`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), any(), any()) } returns Response.success(
            MDBListWatchedResponseDto(episodes = listOf(fromEpisode), pagination = null)
        )

        assertEquals(1, serviceWith(api).fetchAllWatched()!!.pagesFetched)
        coVerify(exactly = 1) { api.getWatched(any(), any(), any(), any()) }
    }

    @Test
    fun `a server that never clears has_more stops at the page ceiling`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), any(), any()) } returns Response.success(
            MDBListWatchedResponseDto(
                episodes = listOf(fromEpisode),
                pagination = MDBListPaginationDto(hasMore = true, nextCursor = "C")
            )
        )

        val result = serviceWith(api).fetchAllWatched()!!
        assertEquals(MDBListWatchedService.MAX_PAGES, result.pagesFetched)
        assertFalse(result.complete)
    }

    @Test
    fun `tracking off costs no request`() = runTest {
        val api = mockk<MDBListApi>()
        assertNull(serviceWith(api, tracking = false).fetchAllWatched())
        assertNull(serviceWith(api, enabled = false).fetchAllWatched())
        coVerify(exactly = 0) { api.getWatched(any(), any(), any(), any()) }
    }
}
