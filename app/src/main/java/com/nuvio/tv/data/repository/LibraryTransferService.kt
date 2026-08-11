package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tracking.TrackingLibraryProviderRegistry
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.core.tracking.providerId
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.SavedLibraryItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class LibraryTransferResult(
    val copied: Int,
    val alreadyPresent: Int,
    val unmatched: Int,
    val duplicates: Int,
    val removedFromSource: Int,
    val failed: Int
)

/**
 * Executes a provider-agnostic library transfer between two locations (local,
 * Trakt, Simkl, MDBList). Copy is the default; move is copy-then-remove from the
 * source. Reads and writes go through the same registry / preferences the rest
 * of the app uses; the actual planning is [computeTransferPlan].
 *
 * This is the engine only - no UI wires it yet. The destination write is
 * best-effort per item and failures are counted rather than aborting the batch.
 * For an MDBList destination the whole batch is one bulk request instead of one
 * write per item, which keeps a large copy inside the free-tier budget.
 */
@Singleton
class LibraryTransferService @Inject constructor(
    private val trackingProviders: TrackingLibraryProviderRegistry,
    private val libraryPreferences: LibraryPreferences,
    private val mdbListDataSource: MDBListWatchlistDataSource
) {
    /** The current contents of a library location. */
    suspend fun read(mode: LibrarySourceMode): List<LibraryEntry> {
        val providerId = mode.providerId
        return if (providerId == null) {
            libraryPreferences.getAllItems().map { it.toLibraryEntry() }
        } else {
            val provider = trackingProviders.provider(providerId) ?: return emptyList()
            provider.refresh(TrackingRefreshIntent.USER_INITIATED)
            provider.items.first()
        }
    }

    /** Dry run: what a copy from [from] to [to] would do, without writing. */
    suspend fun preview(from: LibrarySourceMode, to: LibrarySourceMode): LibraryTransferPlan {
        if (from == to) return LibraryTransferPlan(emptyList(), 0, 0, 0, 0)
        val source = read(from)
        val destinationKeys = read(to).map(::transferKey).toSet()
        return computeTransferPlan(source, destinationKeys)
    }

    /** Performs the transfer. Safe to call after [preview] has been shown. */
    suspend fun execute(
        from: LibrarySourceMode,
        to: LibrarySourceMode,
        mode: LibraryTransferMode
    ): LibraryTransferResult {
        if (from == to) return LibraryTransferResult(0, 0, 0, 0, 0, 0)
        val plan = preview(from, to)
        val written = writeAll(to, plan.toWrite)
        val failed = plan.willWrite - written
        val removed = if (mode == LibraryTransferMode.MOVE && written > 0) {
            removeAll(from, plan.toWrite.take(written))
        } else {
            0
        }
        return LibraryTransferResult(
            copied = written,
            alreadyPresent = plan.alreadyPresent,
            unmatched = plan.unmatched,
            duplicates = plan.duplicates,
            removedFromSource = removed,
            failed = failed
        )
    }

    private suspend fun writeAll(to: LibrarySourceMode, items: List<LibraryEntryInput>): Int {
        if (items.isEmpty()) return 0
        if (to == LibrarySourceMode.MDBLIST) {
            val apiKey = mdbListDataSource.apiKeyOrNull() ?: return 0
            val written = mdbListDataSource.addAll(apiKey, items)
            trackingProviders.provider(TrackingProviderId.MDBLIST)
                ?.refresh(TrackingRefreshIntent.USER_INITIATED)
            return written
        }
        val providerId = to.providerId
        if (providerId != null) {
            val provider = trackingProviders.provider(providerId) ?: return 0
            val desired = ListMembershipChanges(provider.toggledDefaultMembership(emptyMap()))
            var ok = 0
            for (item in items) {
                runCatching {
                    provider.applyMembershipChanges(item, desired, destructiveRemovalConfirmed = false)
                }.onSuccess { ok++ }
            }
            return ok
        }
        var ok = 0
        for (item in items) {
            runCatching { libraryPreferences.addItem(item.toSavedLibraryItem()) }.onSuccess { ok++ }
        }
        return ok
    }

    private suspend fun removeAll(from: LibrarySourceMode, items: List<LibraryEntryInput>): Int {
        if (items.isEmpty()) return 0
        if (from == LibrarySourceMode.MDBLIST) {
            val apiKey = mdbListDataSource.apiKeyOrNull() ?: return 0
            val removed = mdbListDataSource.removeAll(apiKey, items)
            trackingProviders.provider(TrackingProviderId.MDBLIST)
                ?.refresh(TrackingRefreshIntent.USER_INITIATED)
            return removed
        }
        val providerId = from.providerId
        if (providerId != null) {
            val provider = trackingProviders.provider(providerId) ?: return 0
            val cleared = ListMembershipChanges(
                provider.toggledDefaultMembership(emptyMap()).mapValues { false }
            )
            var ok = 0
            for (item in items) {
                runCatching {
                    provider.applyMembershipChanges(item, cleared, destructiveRemovalConfirmed = true)
                }.onSuccess { ok++ }
            }
            return ok
        }
        var ok = 0
        for (item in items) {
            runCatching { libraryPreferences.removeItem(item.itemId, item.itemType) }.onSuccess { ok++ }
        }
        return ok
    }
}

private fun SavedLibraryItem.toLibraryEntry(): LibraryEntry {
    val parsed = parseContentIds(id)
    return LibraryEntry(
        id = id,
        type = type,
        name = name,
        poster = poster,
        posterShape = posterShape,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        addonBaseUrl = addonBaseUrl,
        listedAt = addedAt,
        imdbId = parsed.imdb,
        tmdbId = parsed.tmdb,
        traktId = parsed.trakt
    )
}

private fun LibraryEntryInput.toSavedLibraryItem(): SavedLibraryItem = SavedLibraryItem(
    id = itemId,
    type = itemType,
    name = title,
    poster = poster,
    posterShape = posterShape,
    background = background,
    description = description,
    releaseInfo = releaseInfo,
    imdbRating = imdbRating,
    genres = genres,
    addonBaseUrl = addonBaseUrl,
    logo = logo,
    addedAt = System.currentTimeMillis()
)
