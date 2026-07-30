package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListPaginationDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncIdsDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncShowDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedSeasonBodyDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedSeasonDto
import com.nuvio.tv.domain.model.MDBListSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

/**
 * The season fixture is the exact payload a live account returned on
 * 2026-07-30 - show nested inside the season body, tmdb-only season ids, and
 * `last_watched_at` rather than `season_watched_at`.
 */
class MDBListWatchedServiceTest {

    private val breakingBadSeason1 = MDBListWatchedSeasonDto(
        lastWatchedAt = "2026-07-30T00:51:16.000Z",
        season = MDBListWatchedSeasonBodyDto(
            number = 1,
            name = "Season 1",
            ids = MDBListSyncIdsDto(tmdb = 3572),
            show = MDBListSyncShowDto(
                title = "Breaking Bad",
                year = 2008,
                ids = MDBListSyncIdsDto(imdb = "tt0903747", tmdb = 1396, trakt = 1388, mdblist = "8plj")
            )
        )
    )

    private fun serviceWith(api: MDBListApi, enabled: Boolean = true, tracking: Boolean = true): MDBListWatchedService {
        val settings = mockk<MDBListSettingsDataStore>()
        every { settings.settings } returns flowOf(
            MDBListSettings(enabled = enabled, apiKey = "k", trackingEnabled = tracking)
        )
        return MDBListWatchedService(mdbListApi = api, settingsDataStore = settings)
    }

    private fun page(seasons: List<MDBListWatchedSeasonDto>, hasMore: Boolean) =
        Response.success(
            MDBListWatchedResponseDto(
                movies = emptyList(),
                shows = emptyList(),
                seasons = seasons,
                episodes = emptyList(),
                pagination = MDBListPaginationDto(offset = 0, limit = 100, hasMore = hasMore)
            )
        )

    @Test
    fun `the live season payload survives the round trip with its show ids`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), any()) } returns page(listOf(breakingBadSeason1), hasMore = false)

        val result = serviceWith(api).fetchAllWatched()!!
        assertEquals(1, result.totalRows)
        assertEquals(1, result.pagesFetched)
        val season = result.seasons.single()
        assertEquals(1, season.season?.number)
        // Identity comes from the nested show, since season ids carry only tmdb.
        assertEquals("tt0903747", season.season?.show?.ids?.imdb)
        assertNull(season.season?.ids?.imdb)
        assertEquals("2026-07-30T00:51:16.000Z", season.lastWatchedAt)
    }

    @Test
    fun `paging follows has_more and advances the offset`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), offset = 0, any()) } returns page(listOf(breakingBadSeason1), hasMore = true)
        coEvery { api.getWatched(any(), offset = 100, any()) } returns page(listOf(breakingBadSeason1), hasMore = false)

        val result = serviceWith(api).fetchAllWatched()!!
        assertEquals(2, result.pagesFetched)
        assertEquals(2, result.seasons.size)
        coVerify(exactly = 1) { api.getWatched(any(), offset = 0, limit = MDBListWatchedService.PAGE_SIZE) }
        coVerify(exactly = 1) { api.getWatched(any(), offset = 100, limit = MDBListWatchedService.PAGE_SIZE) }
    }

    @Test
    fun `a failure on a later page discards the whole fetch`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), offset = 0, any()) } returns page(listOf(breakingBadSeason1), hasMore = true)
        coEvery { api.getWatched(any(), offset = 100, any()) } returns
            Response.error(500, "".toResponseBody(null))

        // Partial data would read as "not watched" for everything missing, so
        // the whole fetch is abandoned rather than returned half-complete.
        assertNull(serviceWith(api).fetchAllWatched())
    }

    @Test
    fun `a missing pagination block is treated as the last page`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), any()) } returns Response.success(
            MDBListWatchedResponseDto(seasons = listOf(breakingBadSeason1), pagination = null)
        )

        val result = serviceWith(api).fetchAllWatched()!!
        assertEquals(1, result.pagesFetched)
        coVerify(exactly = 1) { api.getWatched(any(), any(), any()) }
    }

    @Test
    fun `a server that never clears has_more stops at the page ceiling`() = runTest {
        val api = mockk<MDBListApi>()
        coEvery { api.getWatched(any(), any(), any()) } returns page(listOf(breakingBadSeason1), hasMore = true)

        val result = serviceWith(api).fetchAllWatched()!!
        assertEquals(MDBListWatchedService.MAX_PAGES, result.pagesFetched)
        coVerify(exactly = MDBListWatchedService.MAX_PAGES) { api.getWatched(any(), any(), any()) }
    }

    @Test
    fun `tracking off costs no request`() = runTest {
        val api = mockk<MDBListApi>()
        assertNull(serviceWith(api, tracking = false).fetchAllWatched())
        assertNull(serviceWith(api, enabled = false).fetchAllWatched())
        coVerify(exactly = 0) { api.getWatched(any(), any(), any()) }
    }
}
