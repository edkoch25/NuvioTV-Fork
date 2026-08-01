package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MDBList history writes behind the coordinator's connected-writer fan-out.
 * The fan-out preserves the fork's unconditional write-back: watch state is
 * written whenever MDBList tracking is on, so a Trakt-source user's MDBList
 * history never goes stale.
 *
 * A whole season arrives as one [addToHistory] batch and is sent as a single
 * markWatched request (the fork's single-request season rule). Removal also
 * clears any live playback sessions for the item, folding the fork's
 * session-clear-on-removal into the writer since upstream call sites only
 * broadcast the history removal.
 */
@Singleton
class MDBListTrackingHistoryWriter @Inject constructor(
    private val watchedService: MDBListWatchedService,
    private val progressService: MDBListProgressService,
    private val profileManager: ProfileManager
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.MDBLIST

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        val writeItems = items.map { item -> item.media.toMdbListWriteItem() }
        if (writeItems.isEmpty()) return TrackingMutationResult(0)
        runCatching { watchedService.markWatched(writeItems) }
            .onFailure { error -> Log.w(TAG, "MDBList history add failed", error) }
        return TrackingMutationResult(items.size)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        runCatching {
            items.forEach { media ->
                progressService.clearSessionsFor(
                    contentId = media.mdbListContentId(),
                    season = media.episode?.season,
                    episode = media.episode?.number
                )
            }
        }.onFailure { error -> Log.w(TAG, "MDBList session clear failed", error) }
        val writeItems = items.map { media -> media.toMdbListWriteItem() }
        if (writeItems.isNotEmpty()) {
            runCatching { watchedService.unmarkWatched(writeItems) }
                .onFailure { error -> Log.w(TAG, "MDBList watched removal failed", error) }
        }
        return TrackingMutationResult(items.size)
    }

    // The fork passed the catalogue content id straight through as imdbId; the
    // watched service's request builder owns id filtering, exactly as before.
    private fun TrackingMediaReference.toMdbListWriteItem(): MDBListWatchedWriteItem =
        MDBListWatchedWriteItem(
            imdbId = mdbListContentId(),
            season = episode?.season,
            episode = episode?.number
        )

    private fun TrackingMediaReference.mdbListContentId(): String =
        catalog?.contentId?.takeIf(String::isNotBlank)
            ?: ids.imdb?.takeIf(String::isNotBlank)
            ?: title?.trim().orEmpty()

    private companion object {
        private const val TAG = "MDBListHistoryWriter"
    }
}
