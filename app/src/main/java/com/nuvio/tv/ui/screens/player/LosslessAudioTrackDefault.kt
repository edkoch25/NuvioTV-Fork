package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.update

/**
 * Task 3.9 (audio half): default the audio selection to the best lossless track.
 *
 * Remuxes routinely carry a lossless track (TrueHD, DTS-HD MA, FLAC, PCM) alongside a
 * lossy convenience track (AC3/E-AC3/AAC), and the engines' own defaults follow container
 * flags and language only — on plenty of releases that lands on the lossy track. On an
 * AV chain built for lossless passthrough that is always the wrong default.
 *
 * Precedence (lowest wins only when everything above is absent):
 *   1. an explicit user pick this session (which also becomes the remembered selection),
 *   2. a remembered/persisted per-title selection,
 *   3. an engine-failover carry-over (the effective selection captured at switch time),
 *   4. this lossless default,
 *   5. the engine's own default.
 *
 * Language rule: the lossless preference is applied strictly *within* the
 * language-preferred candidate set (the first preferred language that has any track).
 * When that set has no provably-lossless member, the engine's pick stands — a lossless
 * track is never chosen over the user's language. Only when no track matches any
 * preferred language does the whole set become the candidate pool.
 *
 * Classification is deliberately conservative: a track is only ranked when its codec or
 * name proves the tier. mpv reports plain "dts" for every DTS flavour, so an mpv DTS
 * track is treated as lossless only when its name carries an MA / Master Audio hint;
 * an unprovable track is never promoted. ExoPlayer's AUDIO_DTS_HD mime (codec name
 * "DTS-HD") covers both MA and the lossy HRA profile; the container does not distinguish
 * them, and in remux practice DTS-HD tracks are overwhelmingly MA, so it ranks as
 * lossless here (documented trade-off).
 *
 * The default never persists: it writes no remembered selection, so a later explicit
 * pick behaves exactly as before.
 */
internal object LosslessAudioTrackDefault {

    /** Ranking tiers, higher is better. Mirrors the StreamQualityRank ordering. */
    const val TIER_TRUEHD = 4
    const val TIER_DTS_HD_MA = 3
    const val TIER_FLAC = 2
    const val TIER_PCM = 1

    private val commentaryHints = listOf(
        "commentary", "descriptive", "description", "narration", "narrator"
    )

    /**
     * Lossless tier of a track, or null when the track is not provably lossless.
     * [codec] is TrackInfo.codec: ExoPlayer supplies CustomDefaultTrackNameProvider
     * names ("TrueHD", "DTS-HD", "FLAC", "ALAC", "WAV", "PCM"); mpv supplies FFmpeg
     * codec names ("truehd", "mlp", "dts", "flac", "pcm_s24le", ...).
     * [name] is the display name, used for MA hints and PCM/LPCM hints only.
     */
    fun losslessTier(codec: String?, name: String?): Int? {
        val c = codec?.trim()?.lowercase().orEmpty()
        val n = name?.trim()?.lowercase().orEmpty()

        if (c == "truehd" || c == "mlp" || "truehd" in n || "true hd" in n) return TIER_TRUEHD

        val nameSaysMa =
            "dts-hd ma" in n || "dts hd ma" in n || "dtshd ma" in n || "master audio" in n ||
                Regex("\\bma\\b").containsMatchIn(n)
        if (c == "dts-hd") return TIER_DTS_HD_MA
        if (c == "dts" && nameSaysMa) return TIER_DTS_HD_MA
        if (c.isEmpty() && nameSaysMa && "dts" in n) return TIER_DTS_HD_MA

        if (c == "flac" || c == "alac" || "flac" in n) return TIER_FLAC

        if (c == "pcm" || c == "wav" || c.startsWith("pcm_") || "lpcm" in n) return TIER_PCM

        return null
    }

    fun isCommentaryLike(name: String?): Boolean {
        val n = name?.lowercase() ?: return false
        return commentaryHints.any { it in n }
    }

    /**
     * Index of the track the default should select, or null when the engine's own pick
     * should stand. [languageMatches] is (trackLanguage, preferredLanguage) -> Boolean,
     * expected to tolerate ISO 639-1/639-2 variants (PlayerSubtitleUtils.matchesLanguageCode).
     */
    fun pickDefaultIndex(
        tracks: List<TrackInfo>,
        preferredLanguages: List<String>,
        languageMatches: (String?, String) -> Boolean
    ): Int? {
        if (tracks.isEmpty()) return null

        // Candidate pool: the first preferred language that has any track; if no
        // preferred language matches anything, the whole set.
        val pool = preferredLanguages
            .asSequence()
            .map { lang -> tracks.filter { languageMatches(it.language, lang) } }
            .firstOrNull { it.isNotEmpty() }
            ?: tracks

        val ranked = pool.mapNotNull { track ->
            losslessTier(track.codec, track.name)?.let { tier -> track to tier }
        }
        if (ranked.isEmpty()) return null

        // Highest tier that has a non-commentary member; a commentary track is never
        // made the default, even when it is the only lossless one.
        val byTierDesc = ranked.groupBy({ it.second }, { it.first })
            .toSortedMap(compareByDescending { it })
        for ((_, tierTracks) in byTierDesc) {
            val clean = tierTracks.filterNot { isCommentaryLike(it.name) }
            if (clean.isNotEmpty()) {
                return clean
                    .sortedWith(
                        compareByDescending<TrackInfo> { it.channelCount ?: 0 }
                            .thenBy { it.index }
                    )
                    .first()
                    .index
            }
        }
        return null
    }
}

/**
 * Applies the lossless default once per stream, after the persisted/remembered restore
 * pass has had its chance. Runs on both engines' tracks-ready paths; selection goes
 * through the same selectAudioTrack() the restore path uses, so engine handling and the
 * failover carry-over capture see it exactly like any other selection. Never persisted.
 */
internal fun PlayerRuntimeController.applyLosslessAudioDefaultIfUnset(
    audioTracks: List<TrackInfo>
) {
    if (audioTracks.isEmpty()) return
    if (losslessAudioDefaultAppliedForStream) return

    val rememberedAudio = rememberedTrackPreference?.audio
    val persistedAudio = persistedTrackPreference?.audio
    if (rememberedAudio != null || persistedAudio != null ||
        persistedAudioPreferenceSeenForStream ||
        pendingEngineSwitchTrackPreference != null
    ) {
        losslessAudioDefaultAppliedForStream = true
        logSwitchTrace(
            stage = "lossless-default",
            message = "result=skip reason=preference-present remembered=${rememberedAudio != null} " +
                "persisted=${persistedAudio != null} seen=$persistedAudioPreferenceSeenForStream " +
                "switchPending=${pendingEngineSwitchTrackPreference != null}"
        )
        return
    }

    losslessAudioDefaultAppliedForStream = true

    val pick = LosslessAudioTrackDefault.pickDefaultIndex(
        tracks = audioTracks,
        preferredLanguages = mpvPreferredAudioLanguages,
        languageMatches = { trackLanguage, preferred ->
            PlayerSubtitleUtils.matchesLanguageCode(trackLanguage, preferred)
        }
    )
    val selectedIndex = audioTracks.indexOfFirst { it.isSelected }
        .takeIf { it >= 0 } ?: _uiState.value.selectedAudioTrackIndex
    if (pick == null || pick == selectedIndex) {
        logSwitchTrace(
            stage = "lossless-default",
            message = "result=${if (pick == null) "none" else "already-selected"} " +
                "pick=$pick selected=$selectedIndex " +
                "tracks=${audioTracks.joinToString { "${it.index}:${it.codec}/${it.language}" }}"
        )
        return
    }

    val target = audioTracks[pick]
    logSwitchTrace(
        stage = "lossless-default",
        message = "result=apply index=$pick codec=${target.codec} lang=${target.language} " +
            "name=${target.name} channels=${target.channelCount} was=$selectedIndex"
    )
    selectAudioTrack(pick)
    _uiState.update { it.copy(selectedAudioTrackIndex = pick) }
}
