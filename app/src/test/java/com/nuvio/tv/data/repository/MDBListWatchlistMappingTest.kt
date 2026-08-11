package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.mdblist.MDBListSyncIdsDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchlistItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.domain.model.LibraryEntryInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MDBListWatchlistMappingTest {

    private fun item(
        mediatype: String? = "movie",
        imdb: String? = "tt0111161",
        tmdb: Int? = 278,
        trakt: Int? = 389,
        title: String? = "The Shawshank Redemption",
        year: Int? = 1994,
        watchlistAt: String? = "2026-06-25T10:20:41.000Z"
    ) = MDBListWatchlistItemDto(
        id = tmdb,
        mediatype = mediatype,
        imdbId = imdb,
        ids = MDBListSyncIdsDto(imdb = imdb, tmdb = tmdb, trakt = trakt),
        title = title,
        releaseYear = year,
        watchlistAt = watchlistAt
    )

    private fun input(
        itemId: String,
        itemType: String,
        imdbId: String? = null,
        tmdbId: Int? = null
    ) = LibraryEntryInput(
        itemId = itemId,
        itemType = itemType,
        title = "x",
        imdbId = imdbId,
        tmdbId = tmdbId
    )

    // --- The load-bearing test: MDBList entries must key identically to Trakt's. ---

    @Test
    fun `content id equals the shared resolver for the same ids`() {
        val entry = item(imdb = "tt0111161", tmdb = 278, trakt = 389).toLibraryEntry()!!
        val expected = normalizeContentId(TraktIdsDto(imdb = "tt0111161", tmdb = 278, trakt = 389))
        assertEquals(expected, entry.id)
        assertEquals("tt0111161", entry.id) // imdb is preferred
    }

    @Test
    fun `imdb is preferred over tmdb for the id`() {
        val entry = item(imdb = "tt0111161", tmdb = 278).toLibraryEntry()!!
        assertEquals("tt0111161", entry.id)
        assertEquals("tt0111161", entry.imdbId)
        assertEquals(278, entry.tmdbId)
    }

    @Test
    fun `tmdb-only item keys on tmdb prefix`() {
        val entry = item(imdb = null, tmdb = 603, trakt = null).toLibraryEntry()!!
        assertEquals("tmdb:603", entry.id)
    }

    @Test
    fun `show maps to series, movie stays movie, unknown is dropped`() {
        assertEquals("series", item(mediatype = "show").toLibraryEntry()!!.type)
        assertEquals("movie", item(mediatype = "movie").toLibraryEntry()!!.type)
        assertNull(item(mediatype = "person").toLibraryEntry())
    }

    @Test
    fun `listedAt is parsed from the ISO watchlist timestamp`() {
        val entry = item(watchlistAt = "2026-06-25T10:20:41.000Z").toLibraryEntry()!!
        // 2026-06-25T10:20:41Z in epoch millis.
        assertEquals(1782382841000L, entry.listedAt)
        assertEquals(setOf(MDBLIST_WATCHLIST_KEY), entry.listKeys)
    }

    // --- Write plan: match, skip, dedup, split. ---

    @Test
    fun `imdb-only library item produces a write item`() {
        val write = input(itemId = "tt0111161", itemType = "movie", imdbId = "tt0111161")
            .toMDBListWriteItem()!!
        assertEquals("tt0111161", write.imdb)
    }

    @Test
    fun `item with no resolvable id is unmatched`() {
        assertNull(input(itemId = "kitsu:42", itemType = "series").toMDBListWriteItem())
    }

    @Test
    fun `write plan dedups by canonical id and counts unmatched`() {
        val plan = buildWatchlistWritePlan(
            listOf(
                input(itemId = "tt0111161", itemType = "movie", imdbId = "tt0111161"),
                input(itemId = "tt0111161", itemType = "movie"), // same title, id in itemId
                input(itemId = "tt0068646", itemType = "movie", imdbId = "tt0068646"),
                input(itemId = "tt9999999", itemType = "series", imdbId = "tt9999999"),
                input(itemId = "mal:1", itemType = "series") // no resolvable id
            )
        )
        assertEquals(2, plan.moviesCount) // the duplicate collapsed
        assertEquals(1, plan.showsCount)
        assertEquals(1, plan.skippedUnmatched)
        assertEquals(2, plan.body.movies?.size)
        assertEquals(1, plan.body.shows?.size)
    }

    @Test
    fun `empty and all-unmatched batch yields an empty plan`() {
        val plan = buildWatchlistWritePlan(listOf(input(itemId = "anidb:5", itemType = "series")))
        assertTrue(plan.isEmpty)
        assertEquals(1, plan.skippedUnmatched)
        assertNull(plan.body.movies)
        assertNull(plan.body.shows)
    }
}
