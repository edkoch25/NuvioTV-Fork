package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.mdblist.MDBListPlaybackItemDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncEpisodeDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncIdsDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncMovieDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncShowDto
import com.nuvio.tv.domain.model.WatchProgress
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixtures are the exact payloads a live MDBList account returned on 2026-07-30,
 * including the string-typed progress and minute-based runtime that the
 * published spec gets wrong.
 */
class MDBListProgressServiceTest {

    private fun service() = MDBListProgressService(
        mdbListApi = mockk(relaxed = true),
        settingsDataStore = mockk(relaxed = true)
    )

    private val shawshank = MDBListPlaybackItemDto(
        id = 12522705,
        progress = "45.00",
        pausedAt = "2026-07-30T00:50:56.000Z",
        updatedAtTs = 1785372656,
        runtime = 142,
        isManual = false,
        type = "movie",
        movie = MDBListSyncMovieDto(
            title = "The Shawshank Redemption",
            year = 1994,
            ids = MDBListSyncIdsDto(imdb = "tt0111161", tmdb = 278, trakt = 234, mdblist = "a0")
        )
    )

    private val breakingBad = MDBListPlaybackItemDto(
        id = 12522949,
        progress = "40.00",
        pausedAt = "2026-07-30T00:55:03.000Z",
        updatedAtTs = 1785372903,
        runtime = 49,
        type = "episode",
        episode = MDBListSyncEpisodeDto(
            season = 1,
            number = 2,
            title = "Cat's in the Bag...",
            ids = MDBListSyncIdsDto(tmdb = 62086, tvdb = 349233)
        ),
        show = MDBListSyncShowDto(
            title = "Breaking Bad",
            year = 2008,
            ids = MDBListSyncIdsDto(imdb = "tt0903747", tmdb = 1396, trakt = 1388, tvdb = 81189)
        )
    )

    @Test
    fun `movie session maps to imdb-keyed progress`() {
        val p = service().mapPlaybackToProgress(shawshank)!!
        assertEquals("tt0111161", p.contentId)
        assertEquals("movie", p.contentType)
        assertEquals("The Shawshank Redemption", p.name)
        assertEquals("tt0111161", p.videoId)
        assertNull(p.season)
        assertNull(p.episode)
        assertEquals(45.0f, p.progressPercent)
        assertEquals(WatchProgress.SOURCE_MDBLIST_PLAYBACK, p.source)
        assertEquals(12522705L, p.mdbListPlaybackId)
    }

    @Test
    fun `runtime is minutes and position derives from percent`() {
        val p = service().mapPlaybackToProgress(shawshank)!!
        assertEquals(142 * 60_000L, p.duration)
        // 45% of 142 minutes
        assertEquals((142 * 60_000L * 0.45f).toLong(), p.position)
    }

    @Test
    fun `episode session keys on the show imdb id with a composite videoId`() {
        val p = service().mapPlaybackToProgress(breakingBad)!!
        assertEquals("tt0903747", p.contentId)
        assertEquals("series", p.contentType)
        assertEquals("Breaking Bad", p.name)
        assertEquals(1, p.season)
        assertEquals(2, p.episode)
        assertEquals("tt0903747:1:2", p.videoId)
        assertEquals("Cat's in the Bag...", p.episodeTitle)
        assertEquals(49 * 60_000L, p.duration)
    }

    @Test
    fun `lastWatched prefers the epoch field over the iso string`() {
        val p = service().mapPlaybackToProgress(breakingBad)!!
        assertEquals(1785372903L * 1000L, p.lastWatched)
    }

    @Test
    fun `lastWatched falls back to parsing the iso string`() {
        val p = service().mapPlaybackToProgress(breakingBad.copy(updatedAtTs = null))!!
        assertEquals(1785372903L * 1000L, p.lastWatched)
    }

    @Test
    fun `progress is parsed from a string not a number`() {
        // The published spec shows an unquoted number here; the live API returns
        // a string. A Float-typed field would throw before reaching this mapper.
        val p = service().mapPlaybackToProgress(shawshank.copy(progress = "7.25"))!!
        assertEquals(7.25f, p.progressPercent)
    }

    @Test
    fun `rows without a usable imdb id are dropped rather than mis-keyed`() {
        val noImdb = shawshank.copy(
            movie = MDBListSyncMovieDto(
                title = "Untracked",
                year = 2020,
                ids = MDBListSyncIdsDto(tmdb = 999)
            )
        )
        assertNull(service().mapPlaybackToProgress(noImdb))
    }

    @Test
    fun `unparseable progress drops the row`() {
        assertNull(service().mapPlaybackToProgress(shawshank.copy(progress = "not-a-number")))
        assertNull(service().mapPlaybackToProgress(shawshank.copy(progress = null)))
    }

    @Test
    fun `missing runtime yields zero duration and position instead of dividing by zero`() {
        val p = service().mapPlaybackToProgress(shawshank.copy(runtime = null))!!
        assertEquals(0L, p.duration)
        assertEquals(0L, p.position)
        // The percentage still survives, so the CW row can render a bar.
        assertEquals(45.0f, p.progressPercent)
    }

    @Test
    fun `episode type is inferred when the type field is absent`() {
        val p = service().mapPlaybackToProgress(breakingBad.copy(type = null))!!
        assertEquals("series", p.contentType)
        assertEquals("tt0903747:1:2", p.videoId)
    }
}
