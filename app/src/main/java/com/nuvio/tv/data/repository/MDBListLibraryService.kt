package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Reads and writes the MDBList watchlist so it can act as a library source,
 * peer to Trakt and Simkl. Phase 1 exposes the watchlist only (a single
 * membership destination); static MDBList lists are a later slice.
 *
 * Membership is matched on the normalised content id ([normalizeContentId], via
 * [MDBListWatchlistMapping]), so an item keys identically whether it came from
 * MDBList, Trakt, or the local library. Removal is verified by re-reading the
 * watchlist rather than trusting the write's `removed` count.
 */
@Singleton
class MDBListWatchlistDataSource @Inject constructor(
    private val api: MDBListApi,
    private val settingsDataStore: MDBListSettingsDataStore
) {
    /** The active profile's key, or null when tracking is not ready. */
    suspend fun apiKeyOrNull(): String? {
        val settings = settingsDataStore.settings.first()
        return settings.apiKey.takeIf { it.isNotBlank() && settings.trackingReady }
    }

    /** The watchlist change stamp used to gate automatic refreshes. */
    suspend fun lastWatchlistedAt(apiKey: String): String? {
        val response = api.getLastActivities(apiKey)
        return response.body()?.watchlistedAt?.takeIf { response.isSuccessful }
    }

    /** The whole watchlist, paged by offset until the server reports no more. */
    suspend fun fetchAll(apiKey: String): List<LibraryEntry> {
        val entries = mutableListOf<LibraryEntry>()
        var offset = 0
        val limit = 1000
        var guard = 0
        while (guard++ < 50) {
            val response = api.getWatchlistItems(apiKey = apiKey, limit = limit, offset = offset)
            if (!response.isSuccessful) break
            val page = response.body() ?: break
            entries += page.toLibraryEntries()
            val pageCount = (page.movies?.size ?: 0) + (page.shows?.size ?: 0)
            if (page.pagination?.hasMore != true || pageCount == 0) break
            offset += limit
        }
        return entries.distinctBy { it.id }
    }

    suspend fun add(apiKey: String, item: LibraryEntryInput) {
        val plan = buildWatchlistWritePlan(listOf(item))
        if (plan.isEmpty) return
        api.addToWatchlist(apiKey, plan.body)
    }

    suspend fun remove(apiKey: String, item: LibraryEntryInput) {
        val plan = buildWatchlistWritePlan(listOf(item))
        if (plan.isEmpty) return
        api.removeFromWatchlist(apiKey, plan.body)
    }
}

@Singleton
class MDBListLibraryService @Inject constructor(
    private val dataSource: MDBListWatchlistDataSource,
    private val settingsDataStore: MDBListSettingsDataStore,
    @ApplicationContext private val appContext: Context
) : TrackingLibraryProvider {

    private data class State(
        val entries: List<LibraryEntry> = emptyList(),
        val watchlistIds: Set<String> = emptySet(),
        val isLoading: Boolean = false,
        val loaded: Boolean = false,
        val lastWatchlistedAt: String? = null
    )

    private val state = MutableStateFlow(State())
    private val refreshMutex = Mutex()

    private val watchlistTab = LibraryListTab(
        key = MDBLIST_WATCHLIST_KEY,
        title = appContext.getString(R.string.library_watchlist),
        type = LibraryListTab.Type.WATCHLIST,
        trackingProviderId = TrackingProviderId.MDBLIST.storageId,
        isMembershipDestination = true
    )

    override val providerId = TrackingProviderId.MDBLIST

    override val isAuthenticated = settingsDataStore.settings
        .map { settings -> settings.trackingReady }
        .distinctUntilChanged()

    override val isRefreshing = state.map { it.isLoading }.distinctUntilChanged()

    override val items = state.map { it.entries }
        .onStart { refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override val tabs = isAuthenticated.map { authenticated ->
        if (authenticated) listOf(watchlistTab) else emptyList()
    }.distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean = key == MDBLIST_WATCHLIST_KEY

    override fun observeMembership(itemId: String, itemType: String) =
        state.map { snapshot ->
            if (contentKey(itemId) in snapshot.watchlistIds) setOf(MDBLIST_WATCHLIST_KEY) else emptySet()
        }.onStart { refresh(TrackingRefreshIntent.AUTOMATIC) }.distinctUntilChanged()

    override fun toggledDefaultMembership(
        currentMembership: Map<String, Boolean>
    ): Map<String, Boolean> =
        currentMembership + (MDBLIST_WATCHLIST_KEY to (currentMembership[MDBLIST_WATCHLIST_KEY] != true))

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        ensureLoaded()
        val member = contentKey(item.itemId) in state.value.watchlistIds
        return ListMembershipSnapshot(mapOf(MDBLIST_WATCHLIST_KEY to member))
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        val apiKey = dataSource.apiKeyOrNull()
            ?: throw IllegalStateException("MDBList API key is required")
        val desired = changes.desiredMembership[MDBLIST_WATCHLIST_KEY] ?: return
        ensureLoaded()
        val current = contentKey(item.itemId) in state.value.watchlistIds
        if (desired == current) return
        if (desired) dataSource.add(apiKey, item) else dataSource.remove(apiKey, item)
        // Re-read the authoritative watchlist: the write's count is not trusted.
        refresh(TrackingRefreshIntent.USER_INITIATED)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) {
        val apiKey = dataSource.apiKeyOrNull()
        if (apiKey == null) {
            state.value = State()
            return
        }
        refreshMutex.withLock {
            val current = state.value
            if (intent == TrackingRefreshIntent.AUTOMATIC && current.loaded) {
                val stamp = runCatching { dataSource.lastWatchlistedAt(apiKey) }.getOrNull()
                if (stamp != null && stamp == current.lastWatchlistedAt) return
            }
            state.value = current.copy(isLoading = true)
            val fetched = runCatching { dataSource.fetchAll(apiKey) }
            val stamp = runCatching { dataSource.lastWatchlistedAt(apiKey) }.getOrNull()
            state.value = fetched.fold(
                onSuccess = { entries ->
                    State(
                        entries = entries,
                        watchlistIds = entries.map { it.id }.toSet(),
                        isLoading = false,
                        loaded = true,
                        lastWatchlistedAt = stamp ?: current.lastWatchlistedAt
                    )
                },
                onFailure = { current.copy(isLoading = false) }
            )
        }
    }

    private suspend fun ensureLoaded() {
        if (!state.value.loaded) refresh(TrackingRefreshIntent.AUTOMATIC)
    }

    /** Normalises an item id the same way library entries are keyed. */
    private fun contentKey(itemId: String): String =
        normalizeContentId(toTraktIds(parseContentIds(itemId))).ifBlank { itemId.trim() }
}
