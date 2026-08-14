package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSettingsCredentialPolicyTest {
    @Test
    fun `non tracker credentials are excluded from profile settings blobs`() {
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "torbox_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "premiumize_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "real_debrid_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_api_key"))
        assertTrue(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_client_id"))
    }

    @Test
    fun `tracker and non credential settings remain in their existing sync surfaces`() {
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("trakt_settings", "trakt_access_token"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("debrid_settings", "debrid_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("mdblist_settings", "mdblist_enabled"))
        assertFalse(shouldExcludePreferenceFromProfileSettingsSync("animeskip_settings", "animeskip_enabled"))
    }

    @Test
    fun `audio passthrough and denied handling are device-local, excluded from profile settings blobs`() {
        val deviceLocalAudioKeys = listOf(
            "force_optical_passthrough",
            "allow_ac3_passthrough",
            "allow_eac3_passthrough",
            "allow_truehd_passthrough",
            "allow_dts_passthrough",
            "allow_dts_hd_passthrough",
            "denied_codec_handling",
            "mat_passthrough_enabled",
        )
        deviceLocalAudioKeys.forEach { key ->
            assertTrue(shouldExcludePreferenceFromProfileSettingsSync("player_settings", key))
            // the exclusion is scoped to player_settings only
            assertFalse(shouldExcludePreferenceFromProfileSettingsSync("theme_settings", key))
        }
    }
}
