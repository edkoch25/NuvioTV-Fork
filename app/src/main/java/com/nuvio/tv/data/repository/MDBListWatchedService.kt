package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedEpisodeDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedMovieDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedSeasonDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedShowDto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every watched row MDBList holds for the account, pages already joined.
 *
 * Kept as wire types rather than domain models: mapping belongs with the code
 * that reads it, where identity rules and season expansion are decided.
 */
data class MDBListWatchedPages(
    val movies: List<MDBListWatchedMovieDto> = emptyList(),
    val shows: List<MDBListWatchedShowDto> = emptyList(),
    val seasons: List<MDBListWatchedSeasonDto> = emptyList(),
    val episodes: List<MDBListWatchedEpisodeDto> = emptyList(),
    val pagesFetched: Int = 0,
    /** False when the server's own totals disagreed with what was collected. */
    val complete: Boolean = true
) {
    val totalRows: Int get() = movies.size + shows.size + seasons.size + episodes.size
}

/**
 * Reads watch history from MDBList, gated on the same per-profile settings as
 * the other MDBList services so callers stay unconditional.
 *
 * **All-or-nothing on failure.** Any failed page abandons the fetch and returns
 * null rather than a partial set: missing rows read as "not watched", which
 * would clear badges and resurrect finished episodes into next-up. A caller
 * receiving null should keep whatever it already had.
 *
 * Pages by the keyset cursor the API returns, falling back to offset when no
 * cursor is present. Both work; the cursor is stable if a scrobble lands
 * mid-pagination, where an offset window would shift.
 */
@Singleton
class MDBListWatchedService @Inject constructor(
    private val mdbListApi: MDBListApi,
    private val settingsDataStore: MDBListSettingsDataStore
) {
    companion object {
        private const val TAG = "MDBListWatchedSvc"

        /**
         * Rows per request. The limit caps the combined count across all four
         * arrays, and one watched episode yields up to three rows, so this is
         * deliberately large - a heavy history is thousands of rows against a
         * 1,000 requests/day budget.
         */
        internal const val PAGE_SIZE = 1000

        /** Guards a server that never clears `has_more`. */
        internal const val MAX_PAGES = 50
    }

    /** Returns null when tracking is off, or when any page fails. */
    suspend fun fetchAllWatched(): MDBListWatchedPages? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled || !settings.trackingEnabled) {
            Log.d(TAG, "watched fetch: gated (enabled=" + settings.enabled +
                " tracking=" + settings.trackingEnabled + ")")
            return null
        }
        val apiKey = settings.apiKey.trim()
        if (apiKey.isBlank()) {
            Log.d(TAG, "watched fetch: no api key")
            return null
        }

        val movies = mutableListOf<MDBListWatchedMovieDto>()
        val shows = mutableListOf<MDBListWatchedShowDto>()
        val seasons = mutableListOf<MDBListWatchedSeasonDto>()
        val episodes = mutableListOf<MDBListWatchedEpisodeDto>()
        var offset: Int? = 0
        var cursor: String? = null
        var pages = 0
        var lastPage: com.nuvio.tv.data.remote.dto.mdblist.MDBListPaginationDto? = null

        while (pages < MAX_PAGES) {
            val response = try {
                mdbListApi.getWatched(apiKey, limit = PAGE_SIZE, offset = offset, cursor = cursor)
            } catch (e: Exception) {
                Log.w(TAG, "watched fetch failed at page " + pages, e)
                return null
            }
            if (!response.isSuccessful) {
                if (response.code() == 429) {
                    Log.w(TAG, "watched fetch: MDBList daily rate limit exceeded")
                } else {
                    Log.w(TAG, "watched fetch failed at page " + pages +
                        " with code " + response.code() + " body=" + errorBodyOrNull(response))
                }
                return null
            }
            val body = response.body()
            if (body == null) {
                Log.w(TAG, "watched fetch: empty body at page " + pages)
                return null
            }
            pages++
            body.movies?.let { movies.addAll(it) }
            body.shows?.let { shows.addAll(it) }
            body.seasons?.let { seasons.addAll(it) }
            body.episodes?.let { episodes.addAll(it) }
            lastPage = body.pagination

            // A missing pagination block is the last page. Assuming otherwise
            // loops against a server that never says stop and spends the budget.
            if (body.pagination?.hasMore != true) break
            val next = body.pagination?.nextCursor
            if (!next.isNullOrBlank()) {
                cursor = next
                offset = null
            } else {
                cursor = null
                offset = (offset ?: 0) + PAGE_SIZE
            }
        }

        var complete = true
        if (pages >= MAX_PAGES) {
            Log.w(TAG, "watched fetch: stopped at the " + MAX_PAGES + "-page ceiling")
            complete = false
        }
        // The final page reports per-category totals. Checking them turns "the
        // loop ended" into "the server agrees we have everything".
        val expected = lastPage
        if (expected?.totalEpisodes != null) {
            val mismatch = movies.size != (expected.totalMovies ?: movies.size) ||
                shows.size != (expected.totalShows ?: shows.size) ||
                seasons.size != (expected.totalSeasons ?: seasons.size) ||
                episodes.size != (expected.totalEpisodes ?: episodes.size)
            if (mismatch) {
                complete = false
                Log.w(TAG, "watched fetch: totals disagree - collected " +
                    movies.size + "/" + shows.size + "/" + seasons.size + "/" + episodes.size +
                    " but server reports " + expected.totalMovies + "/" + expected.totalShows +
                    "/" + expected.totalSeasons + "/" + expected.totalEpisodes)
            }
        }

        val result = MDBListWatchedPages(
            movies = movies.toList(),
            shows = shows.toList(),
            seasons = seasons.toList(),
            episodes = episodes.toList(),
            pagesFetched = pages,
            complete = complete
        )
        Log.d(TAG, "watched fetch: " + result.totalRows + " row(s) over " + pages +
            " page(s) - movies=" + movies.size + " shows=" + shows.size +
            " seasons=" + seasons.size + " episodes=" + episodes.size +
            " complete=" + complete)
        return result
    }

    private fun errorBodyOrNull(response: retrofit2.Response<*>): String? =
        try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
}
