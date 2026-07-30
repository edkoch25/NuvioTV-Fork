package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedEpisodeDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedMovieDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedSeasonDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedShowDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every watched row MDBList holds for the account, pages already joined.
 *
 * Kept as wire types rather than domain models: mapping belongs with the code
 * that reads it, where identity rules and season expansion are decided.
 */
/**
 * Watched history reduced to what consumers actually join against.
 *
 * Derived once per fetch and held instead of the raw pages: a heavy account is
 * thousands of DTOs, while these three structures are what every call site
 * needs. Identity is IMDb throughout - episode ids carry only tmdb and tvdb, so
 * an episode is its show's IMDb id plus season and number.
 *
 * Season 0 is **kept** here. Specials are genuinely watched and `isWatched`
 * must say so; excluding them is a next-up concern, not a state concern.
 */
data class MDBListWatchedState(
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedEpisodes: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
    val showAiredEpisodeTotals: Map<String, Int> = emptyMap()
)

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
    private val settingsDataStore: MDBListSettingsDataStore,
    profileManager: ProfileManager
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
        // When the final page carries per-category totals, checking them turns
        // "the loop ended" into "the server agrees we have everything". They are
        // not always present - observed on one has_more:false response and absent
        // on another, with no understood trigger - so the check is opportunistic
        // and every field stays null-guarded.
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


    private val watchedState = MutableStateFlow(MDBListWatchedState())
    private val hasLoadedWatched = MutableStateFlow(false)
    private val refreshMutex = Mutex()

    /** Gate stamps, committed only after a fetch succeeds. */
    private var lastWatchedAt: String? = null
    private var lastSeasonWatchedAt: String? = null
    private var lastEpisodeWatchedAt: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Singleton service, per-profile state: forget everything on profile
        // switch so one profile's history never marks another's episodes seen,
        // or suppresses them from next-up. Mirrors MDBListProgressService,
        // including skipping the initial emission on cold start.
        scope.launch {
            var isInitialEmission = true
            profileManager.activeProfileId.collectLatest {
                if (isInitialEmission) {
                    isInitialEmission = false
                    return@collectLatest
                }
                reset()
                refreshNow(force = true)
            }
        }
    }

    /** Watched movie ids, by IMDb id. */
    fun observeWatchedMovieIds(): Flow<Set<String>> =
        watchedState.map { it.watchedMovieIds }.distinctUntilChanged()

    /** Watched episodes per show, keyed by show IMDb id. */
    fun observeWatchedEpisodes(): Flow<Map<String, Set<Pair<Int, Int>>>> =
        watchedState.map { it.watchedEpisodes }.distinctUntilChanged()

    /** Aired-episode totals per show, free with the show rows. */
    fun observeShowAiredTotals(): Flow<Map<String, Int>> =
        watchedState.map { it.showAiredEpisodeTotals }.distinctUntilChanged()

    /** False until the first successful fetch resolves. */
    fun observeWatchedLoaded(): Flow<Boolean> = hasLoadedWatched

    /** Snapshot for callers that cannot collect. */
    fun currentState(): MDBListWatchedState = watchedState.value

    /**
     * Re-reads history when `/sync/last_activities` says a watched category
     * moved. [force] skips the gate's verdict - for a profile switch, or a
     * write-back that must be verified by re-reading rather than trusted.
     *
     * **The gate is read even when forced**, which diverges from
     * [MDBListProgressService.refreshNow] deliberately. That service refetches
     * a single request, so leaving its stamps stale costs one request later.
     * This one paginates, so an uncommitted stamp costs a whole history walk at
     * the next gated refresh. One request now is the cheaper trade.
     *
     * Returns true when the state was replaced.
     */
    suspend fun refreshNow(force: Boolean = false): Boolean = refreshMutex.withLock {
        val apiKey = activeApiKeyOrNull()
        if (apiKey == null) {
            Log.d(TAG, "watched refresh: gated")
            return@withLock false
        }

        // Read the gate without committing it: stamps advance only after a
        // successful fetch, so a transient failure between "changed" and
        // "fetched" can never permanently skip a change. A null snapshot
        // (gate unreadable) degrades to fetching.
        val gate = fetchWatchedActivities(apiKey)
        if (!force && gate != null &&
            gate.watchedAt == lastWatchedAt &&
            gate.seasonWatchedAt == lastSeasonWatchedAt &&
            gate.episodeWatchedAt == lastEpisodeWatchedAt
        ) {
            Log.d(TAG, "watched refresh: gate unchanged, skipping fetch")
            hasLoadedWatched.value = true
            return@withLock false
        }

        val pages = fetchAllWatched() ?: return@withLock false
        val derived = deriveState(pages)
        watchedState.value = derived
        hasLoadedWatched.value = true
        if (gate != null) {
            lastWatchedAt = gate.watchedAt
            lastSeasonWatchedAt = gate.seasonWatchedAt
            lastEpisodeWatchedAt = gate.episodeWatchedAt
        }
        Log.d(TAG, "watched refresh: movies=" + derived.watchedMovieIds.size +
            " shows=" + derived.watchedEpisodes.size +
            " episodes=" + derived.watchedEpisodes.values.sumOf { it.size } +
            " totals=" + derived.showAiredEpisodeTotals.size +
            " (force=" + force + ")")
        return@withLock true
    }

    /** Forgets cached state on profile switch or sign-out. */
    fun reset() {
        watchedState.value = MDBListWatchedState()
        hasLoadedWatched.value = false
        lastWatchedAt = null
        lastSeasonWatchedAt = null
        lastEpisodeWatchedAt = null
    }

    private suspend fun activeApiKeyOrNull(): String? {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled || !settings.trackingEnabled) return null
        return settings.apiKey.trim().takeIf { it.isNotBlank() }
    }

    private suspend fun fetchWatchedActivities(apiKey: String): WatchedActivitySnapshot? {
        val response = try {
            mdbListApi.getLastActivities(apiKey)
        } catch (e: Exception) {
            Log.w(TAG, "last_activities failed; assuming changed", e)
            return null
        }
        if (!response.isSuccessful) return null
        val body = response.body() ?: return null
        return WatchedActivitySnapshot(
            watchedAt = body.watchedAt,
            seasonWatchedAt = body.seasonWatchedAt,
            episodeWatchedAt = body.episodeWatchedAt
        )
    }

    /**
     * Reduces raw pages to the join structures. Pure, so it is unit-testable
     * without a network stub.
     *
     * Rows with no IMDb id are dropped rather than keyed on tmdb: the rest of
     * the app joins on IMDb, and a tmdb-keyed row would silently never match.
     */
    internal fun deriveState(pages: MDBListWatchedPages): MDBListWatchedState {
        val movieIds = mutableSetOf<String>()
        for (entry in pages.movies) {
            val imdb = entry.movie?.ids?.imdb?.trim()
            if (!imdb.isNullOrEmpty()) movieIds.add(imdb)
        }

        val episodes = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
        for (entry in pages.episodes) {
            val body = entry.episode ?: continue
            val showImdb = body.show?.ids?.imdb?.trim()
            if (showImdb.isNullOrEmpty()) continue
            val season = body.season ?: continue
            val number = body.number ?: continue
            episodes.getOrPut(showImdb) { mutableSetOf() }.add(season to number)
        }

        val totals = mutableMapOf<String, Int>()
        for (entry in pages.shows) {
            val body = entry.show ?: continue
            val showImdb = body.ids?.imdb?.trim()
            if (showImdb.isNullOrEmpty()) continue
            val aired = body.totalAiredEpisodes ?: continue
            totals[showImdb] = aired
        }

        return MDBListWatchedState(
            watchedMovieIds = movieIds.toSet(),
            watchedEpisodes = episodes.mapValues { it.value.toSet() },
            showAiredEpisodeTotals = totals.toMap()
        )
    }

    /** The watched-category stamps as read from the gate, uncommitted. */
    private class WatchedActivitySnapshot(
        val watchedAt: String?,
        val seasonWatchedAt: String?,
        val episodeWatchedAt: String?
    )

    private fun errorBodyOrNull(response: retrofit2.Response<*>): String? =
        try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
}
