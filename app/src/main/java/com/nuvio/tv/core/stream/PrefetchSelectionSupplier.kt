package com.nuvio.tv.core.stream

import android.os.SystemClock
import com.nuvio.tv.core.debrid.DirectDebridResolveResult
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.player.AutoPlaySelection
import com.nuvio.tv.core.player.PrefetchedSelection
import com.nuvio.tv.core.player.SelectionSnapshot
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.data.local.BingeGroupCacheDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single implementation of "work out what the stream screen will auto-play,
 * and get it ready", run during a prefetch rather than after the Play press.
 *
 * Two call sites need this and there will be a third:
 *   - HomeViewModel, for the top Continue Watching entry (S4a-3).
 *   - MetaDetailsViewModel, for the hero button's target -- the movie itself,
 *     or a series' resume/next episode (S4a).
 *   - the episode list, once focus is observed (S4a-2).
 *
 * It exists as one class rather than a copy per ViewModel for the same reason
 * AutoPlaySelection does. A second copy of "predict what the stream screen will
 * pick" can drift from the first silently, and here the drift is not merely a
 * wrong prediction: preResolve spends the user's debrid account. One
 * implementation cannot diverge from itself.
 *
 * Measured on the Continue Watching path (25 Jul 2026, Xiaomi S905X5M, 4K DV,
 * auto-play "Best quality", 101 streams, TorBox):
 *   - ranking:  374 ms at the press -> 0.4 ms  (R2, nt24)
 *   - resolve:  1,049 ms at the press -> 34 ms (S4b-lite, nt25)
 *   - click -> PLAYER_START_REQUEST: 1,674 ms -> 720 ms
 * Neither number got smaller. Both moved off the critical path into the seconds
 * the user spends looking at a screen.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
@Singleton
class PrefetchSelectionSupplier @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val addonRepository: AddonRepository,
    private val bingeGroupCacheDataStore: BingeGroupCacheDataStore,
    private val debridSettingsDataStore: DebridSettingsDataStore,
    private val directDebridResolver: DirectDebridResolver
) {

    /**
     * Rank [groups] as the stream screen would, then pre-resolve the winner.
     *
     * Returns the winner plus the settings snapshot it was chosen under, for
     * StreamPrefetchCache to hold and PrefetchedSelectionGate to verify at the
     * press. Null when nothing would be auto-played.
     */
    suspend fun rankAndPreResolve(
        groups: List<AddonStreams>,
        contentId: String?,
        season: Int?,
        episode: Int?
    ): PrefetchedSelection? {
        val rankT0 = SystemClock.elapsedRealtime()
        val selection = rank(groups, contentId)
        // §2.7(a): the cache's total_ms covers this call AND preResolve.
        // Logged here so the rank component needs no subtraction.
        val winnerLabel = if (selection == null) "none" else "yes"
        android.util.Log.i(
            TAG,
            "PREFETCH rank_only winner=$winnerLabel " +
                "ms=${SystemClock.elapsedRealtime() - rankT0}"
        )
        if (selection == null) return null
        preResolve(selection.winner, season, episode)
        return selection
    }

    /**
     * Every input is read HERE and snapshotted; the stream screen compares its
     * own snapshot before consuming the winner, so a setting that moved in
     * between discards the cached pick rather than acting on a stale one.
     *
     * The ordering step is not optional: StreamQualityRank.rank is a stable
     * sort, so incoming order decides ties. Ranking the raw scrape output would
     * break ties differently from the presented list.
     */
    private suspend fun rank(
        groups: List<AddonStreams>,
        contentId: String?
    ): PrefetchedSelection? {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) return null

        val installedAddonOrder = addonRepository.getInstalledAddons()
            .first()
            .enabledAddons()
            .map { it.displayName }

        val preferredBingeGroup = if (
            settings.streamAutoPlayPreferBingeGroupForNextEpisode &&
            settings.streamAutoPlayReuseBingeGroup
        ) {
            contentId?.let { bingeGroupCacheDataStore.get(it) }
        } else {
            null
        }

        val preferences = debridSettingsDataStore.settings.first().streamPreferences

        val inputs = AutoPlaySelection.Inputs(
            mode = settings.streamAutoPlayMode,
            regexPattern = settings.streamAutoPlayRegex,
            source = settings.streamAutoPlaySource,
            installedAddonNames = installedAddonOrder.toSet(),
            selectedAddons = settings.streamAutoPlaySelectedAddons,
            selectedPlugins = settings.streamAutoPlaySelectedPlugins,
            preferredBingeGroup = preferredBingeGroup
        )

        val ordered = StreamAutoPlaySelector.orderAddonStreams(groups, installedAddonOrder)
        val allStreams = ordered.flatMap { it.streams }
        val winner = AutoPlaySelection.select(
            streams = allStreams,
            inputs = inputs,
            debridStreamPreferences = preferences
        ) ?: return null

        return PrefetchedSelection(
            snapshot = SelectionSnapshot(
                inputs = inputs,
                installedAddonOrder = installedAddonOrder,
                preferences = preferences
            ),
            winner = winner
        )
    }

    /**
     * Nothing is cached here. DirectDebridResolver is a @Singleton holding its
     * own in-memory resolvedCache (15 min TTL, keyed by content rather than
     * object identity) plus in-flight dedup, so the press-time resolve of the
     * same winner returns from that cache, and a press landing mid-resolve
     * joins rather than starting a second one.
     *
     * ⚠ Account cost. resolve() runs createTorrent(addOnlyIfCached=true), which
     * adds an entry to the user's debrid account. add_only_if_cached means an
     * uncached torrent is refused (409 -> NotCached) and nothing is added, so
     * this can never start a real download. On TorBox a cached add falls under
     * the 300/minute limit rather than the 60/hour uncached one, does not touch
     * the AirLock "permanent storage" allowance, and expires after 30 days of
     * inactivity.
     *
     * The waste case is a screen opened and never played. It is naturally
     * bounded: this runs only after the scrape has completed and the rank has
     * finished, so roughly 2-6 s of dwell is needed before a resolve starts at
     * all, and a new prefetch target cancels the previous job before then.
     * ⚠ But once started it is NOT cancellable from here -- DirectDebridResolver
     * runs it on its own CoroutineScope, so navigating away after that point
     * still costs one add.
     *
     * Every guard lives inside resolve() already: shouldResolveToPlayableStream
     * returns false for a stream carrying a direct URL (Emby via the NAS
     * bridge, and any source the addon resolved server-side), for an
     * unsupported provider, and for a provider that is not the active resolver.
     * Those return Stale without an API call.
     */
    private suspend fun preResolve(winner: Stream, season: Int?, episode: Int?) {
        val resolveT0 = SystemClock.elapsedRealtime()
        val result = try {
            directDebridResolver.resolve(winner, season, episode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "PREFETCH resolve failed: ${e.message}")
            return
        }
        // result::class.simpleName was R8-minified in release builds (observed as
        // "result=f0" for Success and "result=e0" for Stale, 25 Jul 2026). An
        // explicit `when` over the sealed type is stable under minification.
        // Press-time cost is still measured by TTFF_STAGE's
        // resolve_start -> resolve_done; this ms= is the prefetch-time cost.
        val resultLabel = when (result) {
            is DirectDebridResolveResult.Success -> "Success"
            DirectDebridResolveResult.MissingApiKey -> "MissingApiKey"
            DirectDebridResolveResult.NotCached -> "NotCached"
            DirectDebridResolveResult.Stale -> "Stale"
            DirectDebridResolveResult.Error -> "Error"
        }
        android.util.Log.i(
            TAG,
            "PREFETCH resolve result=$resultLabel " +
                "ms=${SystemClock.elapsedRealtime() - resolveT0}"
        )

        // Patch B (26 Jul capture): warm the playback connection HERE, not at
        // the press.
        //
        // nt2 fired the prewarm from StreamScreen once the press-path resolve
        // returned. The capture showed why that cannot work: the prewarm was
        // enqueued at 16:02:37.096 and took 1,408 ms (cold DNS 285 + connect
        // 330 + TTFB 791), while the datasource probe opened at 16:02:38.023 --
        // 926 ms later, and so 482 ms too early to find anything pooled. The
        // probe paid a full cold connect anyway; only DNS was saved.
        //
        // A prefetch resolve typically completes seconds before the press, so
        // firing here gives the warm-up the headroom it never had. When the
        // press arrives inside the prefetch (PREFETCH join), this simply fires
        // as early as the URL can possibly be known, which is no worse than
        // nt2. StreamScreen's call stays as the fallback for paths that never
        // prefetched; on a warmed pool it costs one pooled round trip.
        //
        // Deliberately fire-and-forget and outside the resultLabel branch's
        // logging, so a resolver change cannot silently drop the warm.
        if (result is DirectDebridResolveResult.Success) {
            com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
                .prewarmPlaybackConnection(result.url, null)
        }
    }

    private companion object {
        // Logged under StreamPrefetch rather than a per-ViewModel tag: these are
        // prefetch-phase events, they belong beside PREFETCH rank in a capture,
        // and it keeps the existing measurement harnesses' tag filter unchanged.
        const val TAG = "StreamPrefetch"
    }
}
