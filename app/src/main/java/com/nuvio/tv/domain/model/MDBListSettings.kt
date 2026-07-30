package com.nuvio.tv.domain.model

data class MDBListSettings(
    val enabled: Boolean = false,
    val apiKey: String = "",
    val showTrakt: Boolean = true,
    val showImdb: Boolean = true,
    val showTmdb: Boolean = true,
    val showLetterboxd: Boolean = true,
    val showTomatoes: Boolean = true,
    val showAudience: Boolean = true,
    val showMetacritic: Boolean = true,
    val showMal: Boolean = true,
    val trackingEnabled: Boolean = false
) {
    /**
     * Tracking is actually usable: the integration is enabled, the tracking
     * toggle is on, and an API key is present. Single definition shared by the
     * source resolver and the settings UI.
     */
    val trackingReady: Boolean
        get() = enabled && trackingEnabled && apiKey.isNotBlank()
}
