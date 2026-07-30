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
        mdbListProgressService = mockk(relaxed = true),
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
    fun `progress is truncated to two decimals for the wire`() {
        val movie = TraktScrobbleItem.Movie(
            title = "28 Years Later: The Bone Temple",
            year = 2026,
            ids = TraktIdsDto(imdb = "tt32141377")
        )
        // The measured failing value from the 2026-07-30 device log.
        assertEquals(3.06f, service().buildRequestBody(movie, 3.0654762f).progress)
        // Clean values pass through unchanged.
        assertEquals(0f, service().buildRequestBody(movie, 0f).progress)
        assertEquals(45.0f, service().buildRequestBody(movie, 45.0f).progress)
        assertEquals(100.0f, service().buildRequestBody(movie, 100.0f).progress)
    }

    @Test
    fun `truncation never crosses the watched threshold`() {
        val movie = TraktScrobbleItem.Movie(title = "T", year = 2026, ids = TraktIdsDto(imdb = "tt1"))
        var below = 80f
        repeat(100) {
            below = Math.nextDown(below)
            val wire = service().buildRequestBody(movie, below).progress
            org.junit.Assert.assertTrue("$below must stay below 80, got $wire", wire < 80f)
        }
        var above = 80f
        repeat(100) {
            val wire = service().buildRequestBody(movie, above).progress
            org.junit.Assert.assertTrue("$above must stay at/above 80, got $wire", wire >= 80f)
            above = Math.nextUp(above)
        }
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
