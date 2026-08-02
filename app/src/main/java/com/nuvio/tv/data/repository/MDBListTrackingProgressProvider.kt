package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * MDBList as a [TrackingProgressProvider]: the fork's MDBList read model
 * (nt19–nt28) relocated onto the upstream provider architecture.
 *
 * Union model, unchanged from the fork: MDBList watch history is newly adopted
 * and sparse, while the local store may hold years — taking MDBList alone
 * would silently unmark everything the user has ever watched the moment they
 * pick it as a source. Watched reads union local in; the repository unions
 * local progress rows and watched episodes via [retainsLocalProgress] /
 * [retainsLocalWatchedEpisode], and NuvioSync stays the backing store (the
 * sync services never hand MDBList exclusive ownership).
 */
@Singleton
class MDBListTrackingProgressProvider @Inject constructor(
    private val progressService: MDBListProgressService,
    private val watchedService: MDBListWatchedService,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val layoutPreferences: LayoutPreferenceDataStore,
    settingsDataStore: MDBListSettingsDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.MDBLIST

    override val isAuthenticated = settingsDataStore.settings
        .map { settings -> settings.trackingReady }
        .distinctUntilChanged()

    // MDBList playback sessions carry no artwork and matching is ids-only, so
    // enrich each remote row from the local copy of the same item (poster,
    // names, addon base URL) and fill duration from local when MDBList had no
    // runtime. Local-only rows whose IDs an external tracker can never return
    // (kitsu:, mal:, ...) are unioned in by the repository via
    // retainsLocalProgress. Genuinely cross-device items with no local copy
    // render without artwork until metadata hydration catches up.
    override val allProgress: Flow<List<WatchProgress>> = combine(
        progressService.observeAllProgress().onStart { emit(emptyList()) },
        watchProgressPreferences.allProgress
    ) { remoteItems, localItems ->
        val localByKey = localItems.associateBy { entryKey(it) }
        remoteItems.map { remote ->
            val local = localByKey[entryKey(remote)] ?: return@map remote
            val duration = if (remote.duration > 0) remote.duration else local.duration
            val position = if (remote.duration > 0) {
                remote.position
            } else {
                val fraction = (remote.progressPercent ?: 0f) / 100f
                (duration * fraction).toLong()
            }
            remote.copy(
                name = remote.name.ifBlank { local.name },
                poster = remote.poster ?: local.poster,
                backdrop = remote.backdrop ?: local.backdrop,
                logo = remote.logo ?: local.logo,
                episodeTitle = remote.episodeTitle ?: local.episodeTitle,
                addonBaseUrl = remote.addonBaseUrl ?: local.addonBaseUrl,
                duration = duration,
                position = position
            )
        }.sortedByDescending { it.lastWatched }
    }.distinctUntilChanged()

    override val remoteProgressLoaded: Flow<Boolean> =
        progressService.observeRemoteProgressLoaded()

    // Union projection of the watched store, mirroring the fork's rule that
    // watched state is MDBList OR local. This is NOT optional: the home
    // pipeline reconciles fully-watched series status from this flow whenever
    // the active provider doesn't own the completed-history projection, and
    // the Continue Watching pipeline builds seeds and badges from it. Leaving
    // it at the interface default (an empty list) would clear the
    // fully-watched marker on home rows under MDBList.
    override val watchedItems: Flow<List<WatchedItem>> = combine(
        watchedService.observeWatchedEpisodes(),
        watchedService.observeShowTitles(),
        watchedService.observeEpisodeWatchedAt(),
        watchedService.observeWatchedMovieIds(),
        watchedItemsPreferences.allItems
    ) { byShow, titles, watchedAt, movieIds, localItems ->
        val merged = LinkedHashMap<Triple<String, Int?, Int?>, WatchedItem>()
        localItems.forEach { item ->
            merged[Triple(item.contentId, item.season, item.episode)] = item
        }
        byShow.forEach { (showId, episodes) ->
            val times = watchedAt[showId].orEmpty()
            episodes.forEach { (season, episode) ->
                merged.getOrPut(Triple(showId, season, episode)) {
                    WatchedItem(
                        contentId = showId,
                        contentType = "series",
                        title = titles[showId].orEmpty(),
                        season = season,
                        episode = episode,
                        watchedAt = times[season to episode] ?: 0L
                    )
                }
            }
        }
        movieIds.forEach { movieId ->
            merged.getOrPut(Triple(movieId, null, null)) {
                WatchedItem(
                    contentId = movieId,
                    contentType = "movie",
                    title = "",
                    watchedAt = 0L
                )
            }
        }
        merged.values.toList()
    }.distinctUntilChanged()

    // Mirrors the fork's local seed builder, from MDBList's watched history
    // instead of the local store. A seed is the furthest (or most recently)
    // watched episode of a show; the next episode itself is computed
    // downstream, where the season-0, unaired and availability guards already
    // live and are source-agnostic.
    //
    // source is left at its default, as the local branch leaves it: the seed
    // source ranking maps unknown sources to the worst rank, so tagging these
    // would silently deprioritise them wherever seeds from different sources
    // meet.
    override val nextUpSeeds: Flow<List<WatchProgress>> = combine(
        watchedService.observeWatchedEpisodes(),
        watchedService.observeShowTitles(),
        watchedService.observeEpisodeWatchedAt(),
        layoutPreferences.nextUpFromFurthestEpisode
    ) { byShow, titles, watchedAt, useFurthest ->
        byShow.mapNotNull { (showId, episodes) ->
            if (isMalformedSeedContentId(showId)) return@mapNotNull null
            val candidates = episodes.filter { it.first != 0 }
            if (candidates.isEmpty()) return@mapNotNull null
            val times = watchedAt[showId].orEmpty()
            val latest = candidates.maxWithOrNull(
                if (useFurthest) {
                    compareBy<Pair<Int, Int>> { it.first }
                        .thenBy { it.second }
                        .thenBy { times[it] ?: 0L }
                } else {
                    compareBy<Pair<Int, Int>> { times[it] ?: 0L }
                        .thenBy { it.first }
                        .thenBy { it.second }
                }
            ) ?: return@mapNotNull null
            WatchProgress(
                contentId = showId,
                contentType = "series",
                name = titles[showId].orEmpty(),
                poster = null,
                backdrop = null,
                logo = null,
                videoId = showId,
                season = latest.first,
                episode = latest.second,
                episodeTitle = null,
                position = 1L,
                duration = 1L,
                lastWatched = times[latest] ?: 0L,
                progressPercent = 100f
            )
        }
    }.flowOn(Dispatchers.Default)

    // Union, not replacement (see class KDoc).
    @OptIn(FlowPreview::class)
    override val watchedMovieIds: Flow<Set<String>> = combine(
        watchedService.observeWatchedMovieIds(),
        localWatchedMovieIdsFlow()
    ) { fromMdbList, fromLocal -> fromMdbList + fromLocal }
        .distinctUntilChanged()

    // Watched history rendered as completed entries, mirroring the Trakt
    // snapshot: the detail screen's episode ticks, next-to-watch and the
    // player's next-episode state all read this map and treat isCompleted()
    // as "watched". Sessions alone answered only "paused", so every finished
    // episode read as unwatched under MDBList. A live paused session wins
    // over a synthesised completed entry, so a rewatch in progress is never
    // masked by history.
    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        combine(
            progressService.observeAllProgress(),
            watchedService.observeWatchedEpisodes()
        ) { items, watchedByShow ->
            val live = items
                .filter { it.contentId == contentId && it.season != null && it.episode != null }
                .associateBy { (it.season ?: 0) to (it.episode ?: 0) }
            val merged = live.toMutableMap()
            watchedByShow[contentId].orEmpty().forEach { (season, episode) ->
                merged.getOrPut(season to episode) {
                    WatchProgress(
                        contentId = contentId,
                        contentType = "series",
                        name = "",
                        poster = null,
                        backdrop = null,
                        logo = null,
                        videoId = "$contentId:$season:$episode",
                        season = season,
                        episode = episode,
                        episodeTitle = null,
                        position = 0L,
                        duration = 0L,
                        // The watched read reduces to (season, episode) sets and
                        // keeps no timestamps, so 0 is honest; next-to-watch under
                        // the furthest-episode preference orders by number, not time.
                        lastWatched = 0L,
                        progressPercent = 100f,
                        source = WatchProgress.SOURCE_MDBLIST_HISTORY
                    )
                }
            }
            merged.toMap()
        }.distinctUntilChanged()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> =
        flowOf(emptyList())

    override fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean> = combine(
        if (season != null && episode != null) {
            watchedService.observeWatchedEpisodes()
                .map { byShow -> byShow[contentId]?.contains(season to episode) == true }
        } else {
            watchedService.observeWatchedMovieIds()
                .map { ids -> ids.contains(contentId) }
        },
        localIsWatchedFlow(contentId, season, episode)
    ) { fromMdbList, fromLocal -> fromMdbList || fromLocal }
        .distinctUntilChanged()

    // A cold state holder would answer "nothing watched", which reads as
    // unwatched rather than unknown, so resolve it before answering. The gate
    // makes an already-fresh account cost one request. Local episodes are
    // unioned in by the repository via retainsLocalWatchedEpisode.
    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        if (!watchedService.observeWatchedLoaded().first()) {
            watchedService.refreshNow()
        }
        return watchedService.currentState().watchedEpisodes
    }

    // Serves the alias map the watched service now derives (imdb -> {tmdb:N,
    // tvdb:N}), matching how Trakt's provider serves its own. Continue
    // Watching uses these to resolve ambiguous show ids. The service's state
    // flow always has a current value, so first() is a non-blocking snapshot.
    override suspend fun showIdSiblings(): Map<String, Set<String>> =
        watchedService.observeShowSiblingIds().first()

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        val force = intent != TrackingRefreshIntent.AUTOMATIC
        progressService.refreshNow(force = force)
        watchedService.refreshNow(force = force)
    }

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        progressService.clearSessionsFor(contentId, season, episode)
    }

    // No optimistic overlay (Simkl precedent): the scrobble service forces a
    // progress refresh after every stop, and the local row the repository
    // saves alongside keeps the UI current between refreshes.
    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit

    override fun clearOptimistic() = Unit

    // The fork's local-only union filter: rows whose IDs an external tracker
    // can never return stay visible (and survive a provider-scoped clear).
    override fun retainsLocalProgress(contentId: String): Boolean =
        !isTraktCompatibleId(contentId)

    override fun retainsLocalWatchedEpisode(item: WatchedItem): Boolean =
        shouldRetainTraktLocalWatchedEpisode(item)

    override fun isHiddenFromProgress(contentId: String): Boolean = false

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress

    /** The fork's unchanged local watched-movie derivation. */
    @OptIn(FlowPreview::class)
    private fun localWatchedMovieIdsFlow(): Flow<Set<String>> {
        return combine(
            watchProgressPreferences.allProgress,
            watchedItemsPreferences.allItems
        ) { progressList, watchedItems ->
            val completedIds = mutableSetOf<String>()
            val replayingIds = mutableSetOf<String>()
            for (progress in progressList) {
                if (progress.isCompleted()) {
                    completedIds.add(progress.contentId)
                } else if (progress.position > 0L ||
                    progress.progressPercent?.let { it > 0f } == true
                ) {
                    replayingIds.add(progress.contentId)
                }
            }
            val watchedItemIds = watchedItems
                .filter { it.season == null && it.episode == null }
                .map { it.contentId }
                .toSet()
            (completedIds + watchedItemIds) - replayingIds
        }.debounce(500)
    }

    /** The fork's unchanged local watched-check leg of the union. */
    private fun localIsWatchedFlow(contentId: String, season: Int?, episode: Int?): Flow<Boolean> {
        val progressFlow = if (season != null && episode != null) {
            watchProgressPreferences.getEpisodeProgress(contentId, season, episode)
        } else {
            watchProgressPreferences.getProgress(contentId)
        }
        return combine(
            progressFlow,
            watchedItemsPreferences.isWatched(contentId, season, episode)
        ) { progressEntry, itemWatched ->
            val hasStartedReplay = progressEntry?.let { entry ->
                !entry.isCompleted() &&
                    (entry.position > 0L || entry.progressPercent?.let { it > 0f } == true)
            } == true

            if (hasStartedReplay) {
                false
            } else {
                (progressEntry?.isCompleted() == true) || itemWatched
            }
        }
    }

    private fun entryKey(progress: WatchProgress): String {
        val season = progress.season
        val episode = progress.episode
        return if (season != null && episode != null) {
            "${progress.contentId}_s${season}e${episode}"
        } else {
            progress.contentId
        }
    }

    private fun isMalformedSeedContentId(contentId: String?): Boolean {
        val trimmed = contentId?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        val lowered = trimmed.lowercase()
        return lowered == "tmdb" ||
            lowered == "imdb" ||
            lowered == "trakt" ||
            lowered == "tmdb:" ||
            lowered == "imdb:" ||
            lowered == "trakt:"
    }
}
