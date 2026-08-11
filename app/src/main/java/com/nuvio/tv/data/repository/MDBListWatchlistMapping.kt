package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchlistItemDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchlistPageDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchlistWriteDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListWatchlistWriteItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryEntryInput

/**
 * Pure, side-effect-free mapping between MDBList's watchlist wire shapes and the
 * app's library models.
 *
 * Content ids are produced by [normalizeContentId] - the SAME resolver Trakt and
 * the content-key layer use (imdb "tt..." preferred, then "tmdb:{n}", then
 * "trakt:{n}"). Routing MDBList ids through it means an MDBList entry keys
 * byte-identically to a Trakt/local entry for the same title, which is what makes
 * cross-library matching and de-duplication work. Reimplementing the rule here
 * would risk silent divergence, so it is deliberately reused.
 */

/** Namespaced so it can never collide with Trakt's bare "watchlist" tab key. */
internal const val MDBLIST_WATCHLIST_KEY = "mdblist_watchlist"

/** MDBList mediatype -> the app's library content type, or null to skip. */
internal fun mdbListLibraryType(mediatype: String?): String? =
    when (mediatype?.trim()?.lowercase()) {
        "movie" -> "movie"
        "show", "series", "tv", "anime" -> "series"
        else -> null
    }

private fun mdbListContentId(imdb: String?, tmdb: Int?, trakt: Int?): String =
    normalizeContentId(
        TraktIdsDto(
            trakt = trakt,
            imdb = imdb?.takeIf { it.isNotBlank() },
            tmdb = tmdb
        )
    )

internal fun MDBListWatchlistItemDto.toLibraryEntry(
    listKey: String = MDBLIST_WATCHLIST_KEY
): LibraryEntry? {
    val type = mdbListLibraryType(mediatype) ?: return null
    val imdb = ids?.imdb?.takeIf { it.isNotBlank() } ?: imdbId?.takeIf { it.isNotBlank() }
    val tmdb = ids?.tmdb ?: id
    val trakt = ids?.trakt
    val contentId = mdbListContentId(imdb, tmdb, trakt)
    if (contentId.isBlank()) return null
    return LibraryEntry(
        id = contentId,
        type = type,
        name = title ?: contentId,
        // The watchlist read carries no artwork; posters are hydrated from local
        // history / TMDB downstream, exactly as MDBList Continue Watching does.
        poster = null,
        background = null,
        logo = null,
        description = null,
        releaseInfo = releaseYear?.toString(),
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        listKeys = setOf(listKey),
        listedAt = parseIsoToMillis(watchlistAt),
        imdbId = imdb,
        tmdbId = tmdb,
        traktId = trakt
    )
}

internal fun MDBListWatchlistPageDto.toLibraryEntries(
    listKey: String = MDBLIST_WATCHLIST_KEY
): List<LibraryEntry> =
    ((movies ?: emptyList()) + (shows ?: emptyList())).mapNotNull { it.toLibraryEntry(listKey) }

/**
 * A single library item -> MDBList write item, or null if it carries no imdb/tmdb
 * id at all (it cannot be written and must be reported as skipped/unmatched).
 */
internal fun LibraryEntryInput.toMDBListWriteItem(): MDBListWatchlistWriteItemDto? {
    val parsed = parseContentIds(itemId)
    val imdb = imdbId?.takeIf { it.isNotBlank() } ?: parsed.imdb
    val tmdb = tmdbId ?: parsed.tmdb
    if (imdb.isNullOrBlank() && tmdb == null) return null
    return MDBListWatchlistWriteItemDto(imdb = imdb, tmdb = tmdb)
}

/** Canonical de-dup key: imdb when present, else tmdb. Blank if neither. */
internal fun MDBListWatchlistWriteItemDto.dedupKey(): String =
    imdb?.takeIf { it.isNotBlank() } ?: tmdb?.let { "tmdb:$it" } ?: ""

internal data class MDBListWatchlistWritePlan(
    val body: MDBListWatchlistWriteDto,
    val skippedUnmatched: Int,
    val moviesCount: Int,
    val showsCount: Int
) {
    val isEmpty: Boolean get() = moviesCount == 0 && showsCount == 0
}

/**
 * Builds a bulk write body from library items: maps each to a write item, drops
 * unmatched ones (counted), de-dups by canonical id (first occurrence wins), and
 * splits movies from shows. One request covers the whole batch.
 */
internal fun buildWatchlistWritePlan(items: List<LibraryEntryInput>): MDBListWatchlistWritePlan {
    var skipped = 0
    val movies = LinkedHashMap<String, MDBListWatchlistWriteItemDto>()
    val shows = LinkedHashMap<String, MDBListWatchlistWriteItemDto>()
    for (item in items) {
        val writeItem = item.toMDBListWriteItem()
        if (writeItem == null) {
            skipped++
            continue
        }
        val key = writeItem.dedupKey()
        if (key.isBlank()) {
            skipped++
            continue
        }
        val bucket = if (mdbListLibraryType(item.itemType) == "series") shows else movies
        bucket.putIfAbsent(key, writeItem)
    }
    return MDBListWatchlistWritePlan(
        body = MDBListWatchlistWriteDto(
            movies = movies.values.toList().takeIf { it.isNotEmpty() },
            shows = shows.values.toList().takeIf { it.isNotEmpty() }
        ),
        skippedUnmatched = skipped,
        moviesCount = movies.size,
        showsCount = shows.size
    )
}
