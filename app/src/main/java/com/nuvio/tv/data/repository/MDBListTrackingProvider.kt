package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.TrackingSeekScrobblePolicy
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * MDBList as a third [TrackingScrobbler] behind the coordinator. Thin adapter
 * over the fork's [MDBListScrobbleService], which keeps its own gating
 * (enabled/tracking/API key), dedup window, retry ladder and post-stop
 * progress refresh.
 *
 * No Trakt episode remap on this path: MDBList keys shows by IMDb id with the
 * addon's own season/episode numbering, so the reference's numbers are sent
 * as-is. Items without an IMDb id are skipped silently — MDBList cannot match
 * them (fork rule, unchanged).
 */
@Singleton
class MDBListTrackingScrobbler @Inject constructor(
    private val service: MDBListScrobbleService
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.MDBLIST
    override val seekScrobblePolicy = TrackingSeekScrobblePolicy.STOP_AND_RESTART

    override suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ) {
        val item = event.media.toMdbListScrobbleItem()
        if (item == null) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "mdblist adapter skipped action=${action.wireValue} reason=no_imdb_id " +
                    event.scrobbleDiagnosticSummary()
            )
            return
        }
        when (action) {
            TrackingScrobbleAction.START ->
                service.scrobbleStart(item, event.progressPercent.toFloat())
            // The fork sent stop for pause on this sink; MDBList has no pause
            // endpoint and records progress from the stop payload.
            TrackingScrobbleAction.PAUSE ->
                service.scrobbleStop(item, event.progressPercent.toFloat())
            TrackingScrobbleAction.STOP ->
                service.scrobbleStop(item, event.progressPercent.toFloat())
        }
    }

    private fun TrackingMediaReference.toMdbListScrobbleItem(): TraktScrobbleItem? {
        val imdb = ids.imdb?.takeIf(String::isNotBlank) ?: return null
        val itemIds = TraktIdsDto(
            trakt = ids.trakt.toIntExactOrNull(),
            imdb = imdb,
            tmdb = ids.tmdb.toIntExactOrNull(),
            tvdb = ids.tvdb?.toIntOrNull()
        )
        if (kind == TrackingMediaKind.MOVIE) {
            return TraktScrobbleItem.Movie(title = title, year = year, ids = itemIds)
        }
        val episodeReference = episode ?: return null
        val season = episodeReference.season ?: return null
        return TraktScrobbleItem.Episode(
            showTitle = title,
            showYear = year,
            showIds = itemIds,
            season = season,
            number = episodeReference.number,
            episodeTitle = episodeReference.title
        )
    }

    private fun Long?.toIntExactOrNull(): Int? =
        this?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
}

@Singleton
class MDBListTrackingProvider @Inject constructor(
    settingsDataStore: MDBListSettingsDataStore,
    override val scrobbler: MDBListTrackingScrobbler
) : TrackingProvider {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val descriptor = TrackingProviderDescriptor(
        id = TrackingProviderId.MDBLIST,
        displayName = "MDBList",
        capabilities = setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE
        )
    )

    // "Connected" for MDBList means the API-key integration is enabled with
    // tracking on — the same trackingReady signal the fork's source resolver
    // keyed on.
    override val isAuthenticated = settingsDataStore.settings
        .map { settings -> settings.trackingReady }
        .stateIn(scope, SharingStarted.Eagerly, false)
}
