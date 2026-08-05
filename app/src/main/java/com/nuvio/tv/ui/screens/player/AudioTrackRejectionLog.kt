/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 */
package com.nuvio.tv.ui.screens.player

import android.os.Build

/**
 * One observed refusal of a passthrough AudioTrack by the platform for a bitstream encoding
 * it had claimed to support - the ground-truth signal that isDirectPlaybackSupported and
 * AudioDeviceInfo.getEncodings() cannot supply proactively (they read the vendor policy and
 * EDID, not the HAL's actual open() result). Recorded when the audio-track-failure recovery
 * ladder engages on a bitstream input.
 *
 * Three consumers will read this in later builds:
 *  - F2b diagnostic: render "rejected on open: DTS-HD" in the Audio Chain Claims row.
 *  - F3 policy: after enough observations, auto-deny passthrough for the encoding so the
 *    format is decoded from the first play rather than only after a failure.
 *  - Device assessment: promote the matching per-format switch from VERIFY to CALCULATED
 *    for this device, recommending it OFF on the evidence of a real rejection.
 *
 * The shape is deliberately richer than a bare encoding so all three can key off it without
 * a later migration: the encoding refused, the route it was refused on (an eARC swap
 * invalidates an ARC observation), a device fingerprint (evidence stays scoped to the
 * hardware that produced it), and a timestamp (recency / invalidation).
 */
internal data class AudioTrackRejection(
    /** Short label, e.g. "DTS-HD" - the same vocabulary as the Audio Chain Claims row. */
    val encoding: String,
    /** Output-route key at the time of refusal, e.g. "type:hdmi_arc|...", or null. */
    val routeKey: String?,
    /** "Manufacturer Model", so evidence never leaks across a device restore. */
    val deviceFingerprint: String,
    val atMs: Long
)

/**
 * Session-scoped in-memory store of [AudioTrackRejection]s. Build 1 records and exposes;
 * the render/policy/assessment consumers arrive in later builds. Session scope is enough for
 * the diagnostic (a failure, then opening the card, in the same session); F3 adds persistence.
 */
internal object AudioTrackRejectionLog {

    private val lock = Any()
    private val entries = mutableListOf<AudioTrackRejection>()
    private val groupsPersistedThisSession = mutableSetOf<String>()

    fun deviceFingerprint(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    /** Bitstream sample MIME to the short label used in the Audio Chain Claims row. */
    fun labelForMime(mime: String?): String? = when (mime) {
        "audio/ac3" -> "AC3"           // MimeTypes.AUDIO_AC3
        "audio/eac3" -> "EAC3"         // MimeTypes.AUDIO_E_AC3
        "audio/eac3-joc" -> "EAC3-JOC" // MimeTypes.AUDIO_E_AC3_JOC
        "audio/true-hd" -> "TrueHD"    // MimeTypes.AUDIO_TRUEHD
        "audio/vnd.dts" -> "DTS"       // MimeTypes.AUDIO_DTS
        "audio/vnd.dts.hd" -> "DTS-HD" // MimeTypes.AUDIO_DTS_HD
        else -> null
    }

    /** Records one refusal, de-duplicated on (encoding, routeKey), keeping the latest time. */
    fun record(encoding: String, routeKey: String?, atMs: Long) {
        synchronized(lock) {
            entries.removeAll { it.encoding == encoding && it.routeKey == routeKey }
            entries.add(AudioTrackRejection(encoding, routeKey, deviceFingerprint(), atMs))
        }
    }

    /** All refusals recorded this session, in insertion order. */
    fun snapshot(): List<AudioTrackRejection> = synchronized(lock) { entries.toList() }

    /** Distinct encodings refused on [routeKey], or on any route when [routeKey] is null. */
    fun encodingsRejectedOn(routeKey: String?): Set<String> = synchronized(lock) {
        entries.filter { routeKey == null || it.routeKey == routeKey }
            .map { it.encoding }
            .toSet()
    }

    /**
     * True the first time [routeGroupKey] ("routeKey::GROUP") is offered this session; used
     * to persist each (route, group) rejection at most once per session, so the datastore's
     * two-session confirmation guard counts distinct sessions rather than retries within one.
     */
    fun markGroupFirstThisSession(routeGroupKey: String): Boolean = synchronized(lock) {
        groupsPersistedThisSession.add(routeGroupKey)
    }

    /** Clears the log. Used by tests and by a future "re-detect chain" action. */
    fun reset() {
        synchronized(lock) {
            entries.clear()
            groupsPersistedThisSession.clear()
        }
    }
}
