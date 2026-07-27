/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 */
package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * What the platform says the audio chain can take, captured once per sink build.
 *
 * The per-format passthrough overrides exist because this report is sometimes wrong - a
 * chain advertises DTS in its EDID that the receiver cannot actually decode. When a user
 * says "no audio on DTS-HD", the first thing worth knowing is what the platform claimed,
 * and that is invisible today. This puts it on the diagnostics page so it can be
 * photographed and quoted, with no logcat required.
 *
 * Read-only: probes capabilities and reads a settings value. It never opens an AudioTrack
 * (see the MAT workstream - startup IEC61937 activity is a wedge risk on some HALs) and
 * never writes a setting.
 *
 * [AudioTrack.isDirectPlaybackSupported] is the same question media3's AudioCapabilities
 * asks, so this reports what the sink will decide, not an independent opinion. The
 * encoded-surround *mode* is included because it is what makes the platform's answer
 * interpretable: in AUTO the enabled set is derived from EDID and is exactly as
 * trustworthy as the EDID; in MANUAL the user has curated it deliberately.
 */
object AudioCapabilityReport {

    /** Label to AudioFormat encoding, in the order they appear in the report. */
    private val PROBES: List<Pair<String, Int>> = buildList {
        add("AC3" to AudioFormat.ENCODING_AC3)
        add("EAC3" to AudioFormat.ENCODING_E_AC3)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            add("EAC3-JOC" to AudioFormat.ENCODING_E_AC3_JOC)
        }
        add("TrueHD" to AudioFormat.ENCODING_DOLBY_TRUEHD)
        add("DTS" to AudioFormat.ENCODING_DTS)
        add("DTS-HD" to AudioFormat.ENCODING_DTS_HD)
    }

    @Volatile
    private var latestReport: String? = null

    /** The most recent report, or null if none has been captured yet. */
    val latest: String?
        get() = latestReport

    /**
     * Probes the platform and stores the result. Call from the sink build, where the same
     * MEDIA/MOVIE attributes the sink uses are in force.
     *
     * Any failure is swallowed into a short marker rather than propagated: a diagnostic
     * must never be able to break playback.
     */
    fun capture(context: Context) {
        latestReport = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                "unavailable (API ${Build.VERSION.SDK_INT} < 29)"
            } else {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
                val supported = mutableListOf<String>()
                val absent = mutableListOf<String>()
                for ((label, encoding) in PROBES) {
                    val format = AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(48_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                        .build()
                    val ok = runCatching {
                        AudioTrack.isDirectPlaybackSupported(format, attributes)
                    }.getOrDefault(false)
                    (if (ok) supported else absent).add(label)
                }
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                format(supported, absent, surroundModeName(audioManager))
            }
        } catch (t: Throwable) {
            "unavailable (${t.javaClass.simpleName})"
        }
    }

    /** Clears the report. Used by tests. */
    internal fun reset() {
        latestReport = null
    }

    /** Pure formatter, split out so the wording is unit-testable without a device. */
    internal fun format(supported: List<String>, absent: List<String>, surroundMode: String): String {
        val direct = if (supported.isEmpty()) "none" else supported.joinToString(" ")
        val missing = if (absent.isEmpty()) "none" else absent.joinToString(" ")
        return "direct: $direct · absent: $missing · surround: $surroundMode"
    }

    /**
     * The Android TV "surround sound" mode. NEVER/ALWAYS/AUTO/MANUAL are the platform's
     * own names; MANUAL means the user has ticked formats by hand, which is the only case
     * where the enabled set carries information the EDID does not.
     */
    private fun surroundModeName(audioManager: AudioManager?): String {
        if (audioManager == null) return "unknown"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "unknown"
        return when (runCatching { audioManager.encodedSurroundMode }.getOrNull()) {
            AudioManager.ENCODED_SURROUND_OUTPUT_NEVER -> "NEVER"
            AudioManager.ENCODED_SURROUND_OUTPUT_ALWAYS -> "ALWAYS"
            AudioManager.ENCODED_SURROUND_OUTPUT_AUTO -> "AUTO"
            AudioManager.ENCODED_SURROUND_OUTPUT_MANUAL -> "MANUAL"
            else -> "unknown"
        }
    }
}
