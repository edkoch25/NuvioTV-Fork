package com.nuvio.tv.ui.screens.player

/**
 * Resolves a human "provider" label for a playing stream: the debrid store that
 * served it (TorBox, AllDebrid, ...) if one is named, else the library back-end
 * (Emby, Jellyfin, ...), else the serving host. Used by the stats HUD and the
 * start-of-play loading overlay (nt41).
 *
 * The store lives inside the add-on's marketing label as a short token, so this
 * parses the stream name + description; add-on name and host are fallbacks only.
 * Token positions vary by add-on ("\u26a1 [TB]", "[TB \u26a1]", "(TB)",
 * "TB Instant", "[AD+]"), so a store CODE is matched only as a bracket/paren/space
 * -delimited token, never a bare substring — otherwise "WEB-DL" would read as
 * Debrid-Link and "HDR" as Debrider. The full store name is always tried first.
 *
 * Store set confirmed from AIOStreams' own provider list (2026-07-31).
 */

private data class DebridStore(
    val display: String,
    val fullNames: List<String>,
    val code: String
)

private val DEBRID_STORES = listOf(
    DebridStore("TorBox", listOf("torbox"), "TB"),
    DebridStore("Real-Debrid", listOf("real-debrid", "realdebrid"), "RD"),
    DebridStore("AllDebrid", listOf("alldebrid"), "AD"),
    DebridStore("Premiumize", listOf("premiumize"), "PM"),
    DebridStore("Debrid-Link", listOf("debrid-link"), "DL"),
    DebridStore("Debrider", listOf("debrider"), "DR"),
    DebridStore("EasyDebrid", listOf("easydebrid"), "ED"),
    DebridStore("Offcloud", listOf("offcloud"), "OC"),
    DebridStore("PikPak", listOf("pikpak"), "PKP"),
    DebridStore("put.io", listOf("put.io", "putio"), "P.IO"),
    DebridStore("Seedr", listOf("seedr"), "SDR")
)

private val LIBRARY_BACKENDS = listOf(
    "emby" to "Emby",
    "jellyfin" to "Jellyfin",
    "plex" to "Plex",
    "google drive" to "Google Drive",
    "gdrive" to "Google Drive"
)

// Chars that may sit immediately before/after a store code for it to count as a
// delimited token (plus string start/end). '\u26a1' is the lightning bolt some
// add-ons prefix to the tag.
private const val CODE_BEFORE = "[(\u26a1 "
private const val CODE_AFTER = "])+\u26a1 "

private fun containsDebridCode(haystack: String, code: String): Boolean {
    var from = 0
    while (true) {
        val i = haystack.indexOf(code, from)
        if (i < 0) return false
        val before = if (i > 0) haystack[i - 1] else null
        val after = if (i + code.length < haystack.length) haystack[i + code.length] else null
        val okBefore = before == null || before in CODE_BEFORE
        val okAfter = after == null || after in CODE_AFTER
        if (okBefore && okAfter) return true
        from = i + 1
    }
}

/**
 * @return the provider label, or null when nothing is known (the row is then omitted).
 */
fun resolveStreamProvider(
    streamName: String?,
    streamDescription: String?,
    addonName: String?,
    host: String?
): String? {
    val label = listOfNotNull(streamName, streamDescription).joinToString("\n")
    val lower = label.lowercase()
    val upper = label.uppercase()

    // 1. Debrid store: full name first (unambiguous), then delimited code.
    DEBRID_STORES.firstOrNull { store -> store.fullNames.any { lower.contains(it) } }
        ?.let { return it.display }
    DEBRID_STORES.firstOrNull { containsDebridCode(upper, it.code) }
        ?.let { return it.display }

    // 2. Library back-end, from the label or the add-on name.
    val libHaystack = lower + "\n" + (addonName ?: "").lowercase()
    LIBRARY_BACKENDS.firstOrNull { libHaystack.contains(it.first) }
        ?.let { return it.second }

    // 3. Serving host.
    return host?.takeIf { it.isNotBlank() }
}
