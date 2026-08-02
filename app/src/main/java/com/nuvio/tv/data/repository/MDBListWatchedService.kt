package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedAddResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedEpisodeDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedMovieDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedSeasonDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedShowDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchedWriteDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWriteEpisodeDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWriteIdsDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWriteMovieDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWriteSeasonDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWriteShowDto
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
/** One thing to mark or unmark. Movies leave season and episode null. */
data class MDBListWatchedWriteItem(
    val imdbId: String,
    val season: Int? = null,
    val episode: Int? = null
)

data class MDBListWatchedState(
    val watchedMovieIds: Set<String> = emptySet(),
    /**
     * Sibling ids per watched show, keyed by the canonical IMDb id. Values
     * use the app's catalogue conventions (`tmdb:N`, `tvdb:N`), matching
     * upstream's Simkl provider, so tmdb-keyed browsers can join on them.
     */
    val showSiblingIds: Map<String, Set<String>> = emptyMap(),
    val watchedEpisodes: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
    /** Show IMDb id to title, for seeds that must render a row label. */
    val showTitles: Map<String, String> = emptyMap(),
    /** Per-show, per-episode watch timestamps in epoch ms; 0 when unparseable. */
    val episodeWatchedAtMs: Map<String, Map<Pair<Int, Int>, Long>> = emptyMap()
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

    /** Show titles by IMDb id. */
    fun observeShowTitles(): Flow<Map<String, String>> =
        watchedState.map { it.showTitles }.distinctUntilChanged()

    /** Sibling join keys per watched show, for tmdb/tvdb-keyed browsers. */
    fun observeShowSiblingIds(): Flow<Map<String, Set<String>>> =
        watchedState.map { it.showSiblingIds }.distinctUntilChanged()

    /** Per-episode watch timestamps, for seed ordering by recency. */
    fun observeEpisodeWatchedAt(): Flow<Map<String, Map<Pair<Int, Int>, Long>>> =
        watchedState.map { it.episodeWatchedAtMs }.distinctUntilChanged()

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
            " titles=" + derived.showTitles.size +
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
        // The full alias set goes in, not just imdb. The watched checkmark
        // join across the app is literal set membership (`item.id in set`),
        // and tmdb-sourced browsers key items as "tmdb:N" - imdb alone can
        // never match there. Convention mirrors upstream's Simkl provider.
        val movieIds = mutableSetOf<String>()
        for (entry in pages.movies) {
            val ids = entry.movie?.ids ?: continue
            val imdb = ids.imdb?.trim()
            if (imdb.isNullOrEmpty()) continue
            movieIds.addAll(aliasesOf(imdb, ids.tmdb, ids.tvdb))
        }

        val episodes = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
        val watchedAt = mutableMapOf<String, MutableMap<Pair<Int, Int>, Long>>()
        for (entry in pages.episodes) {
            val body = entry.episode ?: continue
            val showImdb = body.show?.ids?.imdb?.trim()
            if (showImdb.isNullOrEmpty()) continue
            val season = body.season ?: continue
            val number = body.number ?: continue
            episodes.getOrPut(showImdb) { mutableSetOf() }.add(season to number)
            watchedAt.getOrPut(showImdb) { mutableMapOf() }[season to number] =
                parseIsoUtcOrNull(entry.lastWatchedAt) ?: 0L
        }

        // Titles come from both row kinds: the show rows carry one, and the
        // show nested inside each episode row carries the same. Either is
        // enough, and a show with watched episodes always has the latter.
        val titles = mutableMapOf<String, String>()
        val showSiblings = mutableMapOf<String, MutableSet<String>>()
        for (entry in pages.shows) {
            val body = entry.show ?: continue
            val showImdb = body.ids?.imdb?.trim()
            if (showImdb.isNullOrEmpty()) continue
            val title = body.title?.trim()
            if (!title.isNullOrEmpty()) titles[showImdb] = title
            showSiblings.getOrPut(showImdb) { mutableSetOf() }
                .addAll(aliasesOf(showImdb, body.ids?.tmdb, body.ids?.tvdb))
        }
        // Episode rows carry the same nested show ids, and the show rollup
        // row lags behind them, so a freshly watched show would otherwise
        // have no siblings until the server catches up.
        for (entry in pages.episodes) {
            val show = entry.episode?.show ?: continue
            val showImdb = show.ids?.imdb?.trim()
            if (showImdb.isNullOrEmpty()) continue
            val title = show.title?.trim()
            if (!title.isNullOrEmpty()) titles.putIfAbsent(showImdb, title)
            showSiblings.getOrPut(showImdb) { mutableSetOf() }
                .addAll(aliasesOf(showImdb, show.ids?.tmdb, show.ids?.tvdb))
        }

        return MDBListWatchedState(
            watchedMovieIds = movieIds.toSet(),
            watchedEpisodes = episodes.mapValues { it.value.toSet() },
            showSiblingIds = showSiblings.mapValues { it.value.toSet() },
            showTitles = titles.toMap(),
            episodeWatchedAtMs = watchedAt.mapValues { it.value.toMap() }
        )
    }

    /** One item's join keys: canonical imdb plus prefixed tmdb/tvdb forms. */
    private fun aliasesOf(imdb: String, tmdb: Int?, tvdb: Int?): Set<String> =
        buildSet {
            add(imdb)
            tmdb?.let { add("tmdb:" + it) }
            tvdb?.let { add("tvdb:" + it) }
        }

    /** The watched-category stamps as read from the gate, uncommitted. */
    private class WatchedActivitySnapshot(
        val watchedAt: String?,
        val seasonWatchedAt: String?,
        val episodeWatchedAt: String?
    )


    /**
     * Marks items watched. Shapes measured 2026-07-31: movies and episodes ride
     * one Trakt-shaped body, and a multi-episode body is honoured in full, so a
     * season mark costs one request.
     *
     * Called unconditionally by the repository, like
     * [MDBListProgressService.clearSessionsFor]: watch state is written whenever
     * tracking is on, so gating on the read source would leave a Trakt-source
     * user's MDBList history silently stale. Self-gates on the api key.
     *
     * Returns true when the server reported at least one update. Refreshes the
     * holder on success so the next read reflects the write rather than the
     * pre-write cache.
     */
    suspend fun markWatched(items: List<MDBListWatchedWriteItem>): Boolean {
        val body = buildWriteBody(items) ?: return false
        val apiKey = activeApiKeyOrNull() ?: return false
        Log.d(TAG, "markWatched: " + describe(items))
        val response = try {
            mdbListApi.addWatched(apiKey, body)
        } catch (e: Exception) {
            Log.w(TAG, "markWatched failed", e)
            return false
        }
        if (!response.isSuccessful) {
            Log.w(TAG, "markWatched failed with code " + response.code() +
                " body=" + errorBodyOrNull(response))
            return false
        }
        val updated = response.body()?.updated
        val notFound = response.body()?.notFound
        val count = (updated?.movies ?: 0) + (updated?.shows ?: 0) +
            (updated?.seasons ?: 0) + (updated?.episodes ?: 0)
        val missing = (notFound?.movies?.size ?: 0) + (notFound?.shows?.size ?: 0) +
            (notFound?.seasons?.size ?: 0) + (notFound?.episodes?.size ?: 0)
        Log.d(TAG, "markWatched: updated=" + count + " not_found=" + missing)
        if (count > 0) refreshNow(force = true)
        return count > 0
    }

    /**
     * Unmarks items. Takes the same body as [markWatched].
     *
     * **The server's `removed` count is deliberately not returned or logged as a
     * result.** A removal reporting zero was observed to have succeeded, so the
     * count is unreliable in both directions; this reports only whether the
     * request itself was accepted, and forces a refresh so the next read is the
     * verification.
     */
    suspend fun unmarkWatched(items: List<MDBListWatchedWriteItem>): Boolean {
        val body = buildWriteBody(items) ?: return false
        val apiKey = activeApiKeyOrNull() ?: return false
        Log.d(TAG, "unmarkWatched: " + describe(items))
        val response = try {
            mdbListApi.removeWatched(apiKey, body)
        } catch (e: Exception) {
            Log.w(TAG, "unmarkWatched failed", e)
            return false
        }
        if (!response.isSuccessful) {
            Log.w(TAG, "unmarkWatched failed with code " + response.code() +
                " body=" + errorBodyOrNull(response))
            return false
        }
        Log.d(TAG, "unmarkWatched: accepted; re-reading to verify")
        refreshNow(force = true)
        return true
    }

    /**
     * Groups items into the measured body. Returns null when nothing is
     * writable: identity is IMDb because that is what was measured, so ids the
     * app carries for other trackers (kitsu:, mal:, tmdb:) are dropped rather
     * than guessed at.
     */
    internal fun buildWriteBody(items: List<MDBListWatchedWriteItem>): MDBListWatchedWriteDto? {
        val usable = items.filter { it.imdbId.startsWith("tt") }
        val skipped = items.size - usable.size
        if (skipped > 0) Log.d(TAG, "write: skipped " + skipped + " item(s) with no IMDb id")
        if (usable.isEmpty()) return null

        val movies = usable
            .filter { it.season == null || it.episode == null }
            .map { it.imdbId }
            .distinct()
            .map { MDBListWriteMovieDto(ids = MDBListWriteIdsDto(imdb = it)) }

        val shows = usable
            .filter { it.season != null && it.episode != null }
            .groupBy { it.imdbId }
            .map { (imdb, forShow) ->
                val seasons = forShow
                    .groupBy { it.season!! }
                    .toSortedMap()
                    .map { (season, forSeason) ->
                        MDBListWriteSeasonDto(
                            number = season,
                            episodes = forSeason
                                .map { it.episode!! }
                                .distinct()
                                .sorted()
                                .map { MDBListWriteEpisodeDto(number = it) }
                        )
                    }
                MDBListWriteShowDto(ids = MDBListWriteIdsDto(imdb = imdb), seasons = seasons)
            }

        if (movies.isEmpty() && shows.isEmpty()) return null
        return MDBListWatchedWriteDto(
            movies = movies.takeIf { it.isNotEmpty() },
            shows = shows.takeIf { it.isNotEmpty() }
        )
    }

    private fun describe(items: List<MDBListWatchedWriteItem>): String =
        items.size.toString() + " item(s): " + items.take(5).joinToString(", ") {
            if (it.season != null && it.episode != null) {
                it.imdbId + " s" + it.season + "e" + it.episode
            } else {
                it.imdbId
            }
        }

    /** Mirrors MDBListProgressService's parser; the watched rows use the same format. */
    private fun parseIsoUtcOrNull(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            runCatching {
                val formatter = java.text.SimpleDateFormat(pattern, java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }
                return formatter.parse(value)?.time
            }
        }
        return null
    }

    private fun errorBodyOrNull(response: retrofit2.Response<*>): String? =
        try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
}
