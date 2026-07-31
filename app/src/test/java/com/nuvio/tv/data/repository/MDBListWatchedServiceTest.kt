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
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.MDBListSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
        val profiles = mockk<ProfileManager>()
        every { profiles.activeProfileId } returns MutableStateFlow(1)
        return MDBListWatchedService(
            mdbListApi = api,
            settingsDataStore = settings,
            profileManager = profiles
        )
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

    // ----- derivation (7a) -----
    // deriveState is pure, so these need no network stub. Fixtures are the same
    // live payloads the paging tests use.

    @Test
    fun `derivation keys episodes by show imdb, not by episode ids`() {
        val state = serviceWith(mockk()).deriveState(
            MDBListWatchedPages(episodes = listOf(fromEpisode))
        )
        assertEquals(setOf(1 to 1), state.watchedEpisodes["tt9813792"])
        assertEquals(1, state.watchedEpisodes.size)
    }

    @Test
    fun `derivation collects watched movie ids by imdb`() {
        val state = serviceWith(mockk()).deriveState(
            MDBListWatchedPages(movies = listOf(michaelMovie))
        )
        assertEquals(setOf("tt11378946"), state.watchedMovieIds)
    }

    @Test
    fun `derivation drops rows carrying no imdb id rather than keying on tmdb`() {
        val noImdb = MDBListWatchedEpisodeDto(
            lastWatchedAt = "2026-07-30T21:19:57.000Z",
            episode = MDBListWatchedEpisodeBodyDto(
                season = 2, number = 3,
                ids = MDBListSyncIdsDto(tmdb = 1, tvdb = 2),
                show = MDBListSyncShowDto(title = "No Imdb", year = 2020, ids = MDBListSyncIdsDto(tmdb = 9))
            )
        )
        val state = serviceWith(mockk()).deriveState(
            MDBListWatchedPages(episodes = listOf(fromEpisode, noImdb))
        )
        assertEquals(1, state.watchedEpisodes.size)
        assertTrue(state.watchedEpisodes.containsKey("tt9813792"))
    }

    @Test
    fun `derivation keeps season zero because specials are genuinely watched`() {
        val special = MDBListWatchedEpisodeDto(
            lastWatchedAt = "2026-07-30T21:19:57.000Z",
            episode = MDBListWatchedEpisodeBodyDto(
                season = 0, number = 1,
                ids = MDBListSyncIdsDto(tmdb = 5),
                show = MDBListSyncShowDto(title = "FROM", year = 2022, ids = MDBListSyncIdsDto(imdb = "tt9813792"))
            )
        )
        val state = serviceWith(mockk()).deriveState(
            MDBListWatchedPages(episodes = listOf(special))
        )
        assertEquals(setOf(0 to 1), state.watchedEpisodes["tt9813792"])
    }

    @Test
    fun `derivation merges episodes of one show across pages into a single key`() {
        val second = MDBListWatchedEpisodeDto(
            lastWatchedAt = "2026-07-30T22:19:57.000Z",
            episode = MDBListWatchedEpisodeBodyDto(
                season = 1, number = 2,
                ids = MDBListSyncIdsDto(tmdb = 7),
                show = MDBListSyncShowDto(title = "FROM", year = 2022, ids = MDBListSyncIdsDto(imdb = "tt9813792"))
            )
        )
        val state = serviceWith(mockk()).deriveState(
            MDBListWatchedPages(episodes = listOf(fromEpisode, second))
        )
        assertEquals(setOf(1 to 1, 1 to 2), state.watchedEpisodes["tt9813792"])
    }

    // ----- titles and timestamps (needed by next-up seeds) -----

    @Test
    fun `derivation keeps the show title from the show rows`() {
        val state = serviceWith(mockk()).deriveState(
            MDBListWatchedPages(shows = listOf(fromShow))
        )
        assertEquals("FROM", state.showTitles["tt9813792"])
    }

    @Test
    fun `derivation falls back to the title nested in an episode row`() {
        val state = serviceWith(mockk()).deriveState(
            MDBListWatchedPages(episodes = listOf(fromEpisode))
        )
        assertEquals("FROM", state.showTitles["tt9813792"])
    }

    @Test
    fun `derivation parses the episode watch timestamp`() {
        val state = serviceWith(mockk()).deriveState(
            MDBListWatchedPages(episodes = listOf(fromEpisode))
        )
        val ms = state.episodeWatchedAtMs["tt9813792"]?.get(1 to 1)
        assertEquals(1785446397000L, ms)
    }

    // ----- write body, against the shapes measured 2026-07-31 -----

    @Test
    fun `write body nests episodes under show and season`() {
        val body = serviceWith(mockk()).buildWriteBody(
            listOf(MDBListWatchedWriteItem("tt9813792", 1, 2))
        )
        assertNull(body?.movies)
        val show = body?.shows?.single()
        assertEquals("tt9813792", show?.ids?.imdb)
        assertEquals(1, show?.seasons?.single()?.number)
        assertEquals(listOf(2), show?.seasons?.single()?.episodes?.map { it.number })
    }

    @Test
    fun `write body groups several episodes of one season into a single request`() {
        val body = serviceWith(mockk()).buildWriteBody(
            listOf(
                MDBListWatchedWriteItem("tt9813792", 1, 3),
                MDBListWatchedWriteItem("tt9813792", 1, 2)
            )
        )
        val season = body?.shows?.single()?.seasons?.single()
        assertEquals(listOf(2, 3), season?.episodes?.map { it.number })
    }

    @Test
    fun `write body puts an item with no season or episode in movies`() {
        val body = serviceWith(mockk()).buildWriteBody(
            listOf(MDBListWatchedWriteItem("tt11378946"))
        )
        assertNull(body?.shows)
        assertEquals("tt11378946", body?.movies?.single()?.ids?.imdb)
    }

    @Test
    fun `write body drops ids that are not imdb rather than guessing a mapping`() {
        val svc = serviceWith(mockk())
        assertNull(svc.buildWriteBody(listOf(MDBListWatchedWriteItem("kitsu:123", 1, 1))))
        val mixed = svc.buildWriteBody(
            listOf(
                MDBListWatchedWriteItem("kitsu:123", 1, 1),
                MDBListWatchedWriteItem("tt9813792", 1, 1)
            )
        )
        assertEquals(1, mixed?.shows?.size)
    }

    @Test
    fun `write body is null when there is nothing writable`() {
        assertNull(serviceWith(mockk()).buildWriteBody(emptyList()))
    }
}
