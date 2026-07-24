package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream

/**
 * The single call site for auto-play selection.
 *
 * StreamScreenViewModel had two calls to selectAutoPlayStream, each passing
 * eleven arguments assembled from PlayerSettingsDataStore, AddonRepository,
 * BingeGroupCacheDataStore and DebridSettingsDataStore. They differed in
 * exactly one argument. The second sits inside
 * `if (directFlowActive && persistedBingeGroup != null)`, so its
 * `preferBingeGroupInSelection = true` is equivalent to the first's
 * `persistedBingeGroup != null` -- which means the flag can be derived from
 * [Inputs] and only [bingeGroupOnly] needs to be passed.
 *
 * Why this exists rather than a third copy of the argument list: S4b
 * (pre-resolving the auto-play winner at the details page) has to predict what
 * the stream screen will pick. A divergence there is not a wrong pick, it is a
 * wasted createTorrent against the user's debrid account plus a burnt link
 * generation, silently, on every play. One implementation cannot diverge from
 * itself. Ranking during the prefetch has the same requirement without the
 * account cost.
 *
 * No selection logic lives here. This assembles arguments and delegates;
 * StreamAutoPlaySelector and StreamQualityRank are unchanged, so their existing
 * unit tests still cover the behaviour.
 *
 * [debridStreamPreferences] is deliberately NOT part of [Inputs]. The ViewModel
 * holds it in a @Volatile var populated by an async DataStore collection, and
 * both call sites read it at call time. Folding it into a snapshot would change
 * when it is read. Closing that race belongs with the change that gathers these
 * reads concurrently, not here.
 *
 * Upstream: NuvioMedia/NuvioTV. Licensed under GPL-3.0.
 */
object AutoPlaySelection {

    /**
     * Settings-derived selector inputs, snapshotted once per loadStreams().
     * Every field is already a snapshot in the caller: playerSettings comes
     * from a single .first(), installedAddonOrder from a single
     * getInstalledAddons().first(), and preferredBingeGroup from a single
     * BingeGroupCacheDataStore.get().
     */
    data class Inputs(
        val mode: StreamAutoPlayMode,
        val regexPattern: String,
        val source: StreamAutoPlaySource,
        val installedAddonNames: Set<String>,
        val selectedAddons: Set<String>,
        val selectedPlugins: Set<String>,
        val preferredBingeGroup: String?
    )

    /**
     * Selects the stream to auto-play, or null for the picker.
     *
     * @param bingeGroupOnly when true, a binge-group miss returns null instead
     *   of falling back to the configured mode -- the eager pre-timeout check.
     */
    fun select(
        streams: List<Stream>,
        inputs: Inputs,
        debridStreamPreferences: DebridStreamPreferences?,
        bingeGroupOnly: Boolean = false
    ): Stream? = StreamAutoPlaySelector.selectAutoPlayStream(
        streams = streams,
        mode = inputs.mode,
        regexPattern = inputs.regexPattern,
        source = inputs.source,
        installedAddonNames = inputs.installedAddonNames,
        selectedAddons = inputs.selectedAddons,
        selectedPlugins = inputs.selectedPlugins,
        preferredBingeGroup = inputs.preferredBingeGroup,
        preferBingeGroupInSelection = inputs.preferredBingeGroup != null,
        bingeGroupOnly = bingeGroupOnly,
        debridStreamPreferences = debridStreamPreferences
    )
}
