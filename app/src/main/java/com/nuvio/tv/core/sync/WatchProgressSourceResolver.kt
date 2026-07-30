package com.nuvio.tv.core.sync

import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the question "which backend currently owns watch state?".
 *
 * Before this existed, three call sites each re-derived the answer with the same
 * expression - `source == TRAKT && traktAuthenticated` - in
 * [WatchProgressSyncService], [WatchedItemsSyncService] and
 * WatchProgressRepositoryImpl. That triplication was fine while there were only
 * two possible sources, but it does not survive a third: every new source would
 * need the same predicate widened in three places, in the Continue Watching path,
 * with no single place to test it.
 *
 * The resolution rule is that a *stored preference* is only honoured when its
 * backend is actually usable, otherwise it degrades to [WatchProgressSource.NUVIO_SYNC]
 * (which is also the local-storage path when the user has no Nuvio account). This
 * reproduces the previous behaviour exactly: selecting Trakt without a working Trakt
 * connection has always fallen back rather than showing an empty Continue Watching row.
 *
 * Deliberately does no debouncing. The repository debounces the false->true edge of
 * its own derived boolean to ride out transient auth unavailability during profile
 * switches; the sync services do not, and must not inherit it.
 */
@Singleton
class WatchProgressSourceResolver @Inject constructor(
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val traktAuthDataStore: TraktAuthDataStore
) {

    /**
     * The stored preference resolved against backend availability, recomputed
     * whenever either input changes.
     */
    fun effectiveSource(): Flow<WatchProgressSource> = combine(
        traktSettingsDataStore.watchProgressSource,
        traktAuthDataStore.isEffectivelyAuthenticated
    ) { stored, traktAuthenticated ->
        resolve(stored, traktAuthenticated)
    }.distinctUntilChanged()

    /**
     * One-shot read. Reads each input independently rather than taking the first
     * value of [effectiveSource], matching how the sync services previously read
     * these two data stores.
     */
    suspend fun currentEffectiveSource(): WatchProgressSource = resolve(
        stored = traktSettingsDataStore.watchProgressSource.first(),
        traktAuthenticated = traktAuthDataStore.isEffectivelyAuthenticated.first()
    )

    /**
     * Pure resolution rule, exposed for test. For the two pre-existing sources this
     * is equivalent to the expression it replaced:
     * `resolve(stored, authed) == TRAKT` iff `stored == TRAKT && authed`.
     */
    internal fun resolve(
        stored: WatchProgressSource,
        traktAuthenticated: Boolean
    ): WatchProgressSource = when (stored) {
        WatchProgressSource.TRAKT ->
            if (traktAuthenticated) WatchProgressSource.TRAKT else WatchProgressSource.NUVIO_SYNC

        // MDBList has a scrobble (write) path but no progress-read client yet, so it
        // cannot own watch state. Falling back keeps Continue Watching populated
        // instead of silently emptying it. Revisit when the read client lands.
        WatchProgressSource.MDBLIST -> WatchProgressSource.NUVIO_SYNC

        WatchProgressSource.NUVIO_SYNC -> WatchProgressSource.NUVIO_SYNC
    }
}
