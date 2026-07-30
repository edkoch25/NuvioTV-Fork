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
 * Every watched row MDBList holds for the account, with the pages already
 * joined up.
 *
 * Kept as wire types rather than domain models: this class has no consumer yet,
 * and mapping onto WatchedItem belongs with the code that reads it, where the
 * identity rules and the season expansion can be decided together.
 */
data class MDBListWatchedPages(
    val movies: List<MDBListWatchedMovieDto> = emptyList(),
    val shows: List<MDBListWatchedShowDto> = emptyList(),
    val seasons: List<MDBListWatchedSeasonDto> = emptyList(),
    val episodes: List<MDBListWatchedEpisodeDto> = emptyList(),
    val pagesFetched: Int = 0
) {
    val totalRows: Int get() = movies.size + shows.size + seasons.size + episodes.size
}

/**
 * Reads watch history from MDBList.
 *
 * Gated on the same per-profile settings as the other MDBList services, so
 * callers stay unconditional.
 *
 * **All-or-nothing by design.** Any failed page abandons the whole fetch and
 * returns null rather than a partial set. A partially fetched watched set is
 * worse than none: the missing rows read as "not watched", which would clear
 * badges and resurrect finished episodes into next-up. A caller that gets null
 * should keep whatever it already had.
 */
@Singleton
class MDBListWatchedService @Inject constructor(
    private val mdbListApi: MDBListApi,
    private val settingsDataStore: MDBListSettingsDataStore
) {
    companion object {
        private const val TAG = "MDBListWatchedSvc"

        /** Rows per request. */
        internal const val PAGE_SIZE = 100

        /**
         * Ceiling on requests per fetch. Guards against a server that never
         * clears `has_more`; at [PAGE_SIZE] this still covers 5,000 rows, and
         * the daily budget is 1,000 requests total.
         */
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
        var offset = 0
        var pages = 0

        while (pages < MAX_PAGES) {
            val response = try {
                mdbListApi.getWatched(apiKey, offset = offset, limit = PAGE_SIZE)
            } catch (e: Exception) {
                Log.w(TAG, "watched fetch failed at offset " + offset, e)
                return null
            }
            if (!response.isSuccessful) {
                if (response.code() == 429) {
                    Log.w(TAG, "watched fetch: MDBList daily rate limit exceeded")
                } else {
                    Log.w(TAG, "watched fetch failed at offset " + offset +
                        " with code " + response.code() + " body=" + errorBodyOrNull(response))
                }
                return null
            }
            val body = response.body()
            if (body == null) {
                Log.w(TAG, "watched fetch: empty body at offset " + offset)
                return null
            }
            pages++
            body.movies?.let { movies.addAll(it) }
            body.shows?.let { shows.addAll(it) }
            body.seasons?.let { seasons.addAll(it) }
            body.episodes?.let { episodes.addAll(it) }

            // A missing pagination block is treated as the last page. The
            // alternative - assuming more - loops against a server that never
            // says stop, and burns the daily budget doing it.
            if (body.pagination?.hasMore != true) break
            offset += PAGE_SIZE
        }

        if (pages >= MAX_PAGES) {
            Log.w(TAG, "watched fetch: stopped at the " + MAX_PAGES +
                "-page ceiling; the set may be incomplete")
        }
        val result = MDBListWatchedPages(
            movies = movies.toList(),
            shows = shows.toList(),
            seasons = seasons.toList(),
            episodes = episodes.toList(),
            pagesFetched = pages
        )
        Log.d(TAG, "watched fetch: " + result.totalRows + " row(s) over " + pages +
            " page(s) - movies=" + movies.size + " shows=" + shows.size +
            " seasons=" + seasons.size + " episodes=" + episodes.size)
        return result
    }

    private fun errorBodyOrNull(response: retrofit2.Response<*>): String? =
        try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
}
