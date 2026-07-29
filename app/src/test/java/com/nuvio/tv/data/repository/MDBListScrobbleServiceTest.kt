package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MDBListScrobbleServiceTest {

    private fun service() = MDBListScrobbleService(
        mdbListApi = mockk(relaxed = true),
        settingsDataStore = mockk(relaxed = true),
        profileManager = mockk(relaxed = true)
    )

    @Test
    fun `movie body carries ids and progress and no show`() {
        val body = service().buildRequestBody(
            TraktScrobbleItem.Movie(
                title = "The Shawshank Redemption",
                year = 1994,
                ids = TraktIdsDto(imdb = "tt0111161", tmdb = 278, trakt = 389)
            ),
            clampedProgress = 15.5f
        )
        assertNull(body.show)
        assertEquals("tt0111161", body.movie?.ids?.imdb)
        assertEquals(278, body.movie?.ids?.tmdb)
        assertEquals(389, body.movie?.ids?.trakt)
        assertEquals(15.5f, body.progress)
    }

    @Test
    fun `episode body nests season and episode inside show`() {
        val body = service().buildRequestBody(
            TraktScrobbleItem.Episode(
                showTitle = "Twin Peaks",
                showYear = 1990,
                showIds = TraktIdsDto(imdb = "tt0098936", tmdb = 1920),
                season = 2,
                number = 7,
                episodeTitle = "Lonely Souls"
            ),
            clampedProgress = 81.0f
        )
        assertNull(body.movie)
        assertEquals("tt0098936", body.show?.ids?.imdb)
        assertEquals(2, body.show?.season?.number)
        assertEquals(7, body.show?.season?.episode?.number)
        assertEquals(81.0f, body.progress)
    }
}
