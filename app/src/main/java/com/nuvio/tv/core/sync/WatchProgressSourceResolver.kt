package com.nuvio.tv.core.sync

import com.nuvio.tv.data.local.MDBListSettingsDataStore
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
 * The resolution rule is that a *stored preference* is only honoured when its
 * backend is actually usable, otherwise it degrades to
 * [WatchProgressSource.NUVIO_SYNC] (which is also the local-storage path when
 * the user has no Nuvio account). Selecting a backend that later becomes
 * unusable - Trakt signed out, an MDBList key removed - falls back rather than
 * emptying Continue Watching.
 *
 * Deliberately does no debouncing. The repository debounces the false->true
 * edge of its own Trakt boolean to ride out transient auth unavailability
 * during profile switches; the sync services do not, and must not inherit it.
 */
@Singleton
class WatchProgressSourceResolver @Inject constructor(
    private val traktSettingsDataStore: TraktSettingsDataStore,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore
) {

    /**
     * The stored preference resolved against backend availability, recomputed
     * whenever any input changes.
     */
    fun effectiveSource(): Flow<WatchProgressSource> = combine(
        traktSettingsDataStore.watchProgressSource,
        traktAuthDataStore.isEffectivelyAuthenticated,
        mdbListSettingsDataStore.settings
    ) { stored, traktAuthenticated, mdbListSettings ->
        resolve(stored, traktAuthenticated, mdbListSettings.trackingReady)
    }.distinctUntilChanged()

    /**
     * One-shot read. Reads each input independently rather than taking the
     * first value of [effectiveSource], matching how the sync services
     * previously read these data stores.
     */
    suspend fun currentEffectiveSource(): WatchProgressSource = resolve(
        stored = traktSettingsDataStore.watchProgressSource.first(),
        traktAuthenticated = traktAuthDataStore.isEffectivelyAuthenticated.first(),
        mdbListTrackingReady = mdbListSettingsDataStore.settings.first().trackingReady
    )

    /**
     * Pure resolution rule, exposed for test. Resolving to TRAKT is equivalent
     * to the predicate this class replaced:
     * `resolve(...) == TRAKT` iff `stored == TRAKT && traktAuthenticated`.
     */
    internal fun resolve(
        stored: WatchProgressSource,
        traktAuthenticated: Boolean,
        mdbListTrackingReady: Boolean
    ): WatchProgressSource = when (stored) {
        WatchProgressSource.TRAKT ->
            if (traktAuthenticated) WatchProgressSource.TRAKT else WatchProgressSource.NUVIO_SYNC

        WatchProgressSource.MDBLIST ->
            if (mdbListTrackingReady) WatchProgressSource.MDBLIST else WatchProgressSource.NUVIO_SYNC

        WatchProgressSource.NUVIO_SYNC -> WatchProgressSource.NUVIO_SYNC
    }
}
