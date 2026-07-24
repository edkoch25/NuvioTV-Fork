package com.nuvio.tv.core.player

import android.os.SystemClock
import com.nuvio.tv.core.debrid.DirectDebridStreamFilter
import com.nuvio.tv.domain.model.DebridStreamPreferences
import com.nuvio.tv.domain.model.Stream

/**
 * Deterministic quality ranking for auto-select (task 3.7; TRaSH-aligned
 * rebuild).
 *
 * Candidates are ranked with the same fact extraction the Direct Debrid list
 * uses (DirectDebridStreamFilter), so a single user-editable preferences
 * object drives both the visible debrid list and auto-pick across ALL
 * sources, including passthrough streams (e.g. Emby bridge, pre-resolved
 * links) that bypass the list-level filter.
 *
 * Order (Q18: resolution wins outright, no cross-tier trading):
 * resolution -> quality (REMUX > BluRay > WEB-DL > ...) -> preferred release
 * group ladder (TRaSH HQ tiers by default) -> visual tags -> audio tags
 * (TrueHD first by default) -> channels -> encode -> size (desc) -> container
 * (mkv over mp4/unknown). Ties keep incoming order (stable sort).
 *
 * Streams matching any EXCLUDED preference (release groups, encodes, visual
 * tags, qualities, ...) are dropped from the pool first. If that empties the
 * pool, ranking falls back to the unfiltered candidates so auto-select still
 * plays something rather than silently doing nothing. Required/floor filters
 * are deliberately NOT applied here: they are a list-level concern already
 * enforced for managed debrid streams, and resolution-rank ordering means low
 * tiers only win when nothing better exists.
 *
 * The user's list sort profile is intentionally ignored: auto-pick always
 * uses this canonical chain, honouring preferred/excluded LISTS but not
 * custom sort orders (a size-ascending list sort must not make auto-play
 * pick the smallest file).
 */
object StreamQualityRank {

    private val CONTAINER_MKV = Regex("\\.mkv\\b|\\bmkv\\b", RegexOption.IGNORE_CASE)
    private val DEFAULT_PREFERENCES = DebridStreamPreferences()

    /** Stable, deterministic best-first ordering of the given streams. */
    fun rank(
        streams: List<Stream>,
        preferences: DebridStreamPreferences? = null
    ): List<Stream> {
        if (streams.size <= 1) return streams
        val effective = preferences ?: DEFAULT_PREFERENCES
        // R1-i instrument (24 Jul 2026). APPLY_SPLIT's select= brackets the whole
        // selectAutoPlayStream call, not factsFor: the "3.14 ms per stream" figure
        // in circulation is 976/311 arithmetic rather than a measurement, and its
        // denominator is allStreams rather than the post-isPlayable pool that
        // actually reaches here. Three cost centres live inside this function --
        // fact extraction, the exclusion filter, and a nine-level sort whose every
        // key lookup is a deep structural hash of a Stream -- and only the first is
        // addressed by making streamFacts cheaper. Measure the split before
        // optimising. Logging only; no behavioural change.
        val rankT0 = SystemClock.elapsedRealtime()
        val factsByStream = streams.associateWith { DirectDebridStreamFilter.factsFor(it, effective) }
        val rankFactsMs = SystemClock.elapsedRealtime() - rankT0
        val pool = streams.filter {
            DirectDebridStreamFilter.passesExclusionFilters(factsByStream.getValue(it), effective)
        }.ifEmpty { streams }
        val rankFilterMs = SystemClock.elapsedRealtime() - rankT0 - rankFactsMs
        val ranked = pool.sortedWith(
            compareBy<Stream> { factsByStream.getValue(it).resolutionRank }
                .thenBy { factsByStream.getValue(it).qualityRank }
                .thenBy { factsByStream.getValue(it).groupRank }
                .thenBy { factsByStream.getValue(it).visualRank }
                .thenBy { factsByStream.getValue(it).audioRank }
                .thenBy { factsByStream.getValue(it).channelRank }
                .thenBy { factsByStream.getValue(it).encodeRank }
                .thenByDescending { factsByStream.getValue(it).size ?: -1L }
                .thenByDescending { containerScore(it) }
        )
        val rankSortMs = SystemClock.elapsedRealtime() - rankT0 - rankFactsMs - rankFilterMs
        android.util.Log.i(
            "StreamQualityRank",
            "R1_SPLIT in=${streams.size} distinct=${factsByStream.size} pool=${pool.size} " +
                "facts=${rankFactsMs}ms filter=${rankFilterMs}ms sort=${rankSortMs}ms " +
                "total=${SystemClock.elapsedRealtime() - rankT0}ms"
        )
        return ranked
    }

    internal fun containerScore(stream: Stream): Int {
        val text = listOfNotNull(
            stream.behaviorHints?.filename,
            stream.getStreamUrl(),
            stream.name,
            stream.title,
            stream.description
        ).joinToString(" ")
        return if (CONTAINER_MKV.containsMatchIn(text)) 1 else 0
    }
}
