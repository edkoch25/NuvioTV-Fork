/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 */
package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
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
                format(supported, absent, negotiatedEncodings(audioManager), surroundModeName(audioManager))
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
    internal fun format(
        supported: List<String>,
        absent: List<String>,
        negotiated: String,
        surroundMode: String
    ): String {
        val direct = if (supported.isEmpty()) "none" else supported.joinToString(" ")
        val missing = if (absent.isEmpty()) "none" else absent.joinToString(" ")
        return "direct: $direct · absent: $missing · negotiated: $negotiated · surround: $surroundMode"
    }

    /** HDMI/ARC/eARC output device types whose negotiated encodings we report. */
    private val HDMI_OUTPUT_TYPES = setOf(
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_HDMI_EARC
    )

    /**
     * The encodings the connected HDMI/ARC/eARC output actually negotiated, read from
     * [AudioDeviceInfo.getEncodings]. A different oracle from isDirectPlaybackSupported
     * above: that reads the vendor audio-policy profiles (static, and on some TVs still
     * advertising formats the licence-stripped HAL will not open); this reflects what the
     * HDMI link reported after EDID negotiation. When the two disagree - a TV that claims
     * DTS it cannot open - this is the row that tends to be honest. PCM16 is included
     * deliberately: its presence is what makes the line legible at a glance.
     *
     * Best-effort. getEncodings() returns an empty array when the platform does not
     * expose it, which is common on TVs; we say "unknown" then rather than imply nothing
     * is accepted.
     */
    private fun negotiatedEncodings(audioManager: AudioManager?): String {
        if (audioManager == null) return "unknown"
        val labels = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type in HDMI_OUTPUT_TYPES }
                .flatMap { it.encodings.asList() }
                .distinct()
                .mapNotNull(::encodingLabel)
        }.getOrNull().orEmpty()
        return if (labels.isEmpty()) "unknown" else labels.joinToString(" ")
    }

    /** AudioFormat.ENCODING_* to the same short labels used in the direct/absent lists. */
    private fun encodingLabel(encoding: Int): String? = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "PCM16"
        AudioFormat.ENCODING_AC3 -> "AC3"
        AudioFormat.ENCODING_E_AC3 -> "EAC3"
        AudioFormat.ENCODING_E_AC3_JOC -> "EAC3-JOC"
        AudioFormat.ENCODING_DOLBY_TRUEHD -> "TrueHD"
        AudioFormat.ENCODING_DTS -> "DTS"
        AudioFormat.ENCODING_DTS_HD -> "DTS-HD"
        else -> null
    }

    /**
     * The Android TV "surround sound" mode. NEVER/ALWAYS/AUTO/MANUAL are the platform's
     * own names; MANUAL means the user has ticked formats by hand, which is the only case
     * where the enabled set carries information the EDID does not.
     */
    private fun surroundModeName(audioManager: AudioManager?): String {
        if (audioManager == null) return "unknown"
        // getEncodedSurroundMode() is API 31+ (Android 12). The previous API 29 gate let
        // the call through on Android 10/11, where it throws and collapsed to "unknown" -
        // indistinguishable from a genuinely unreadable value. Attempt only where the
        // getter exists, wrap it so an OEM off-by-one still degrades cleanly, and say
        // "n/a" when the platform cannot report the mode rather than "unknown".
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return "n/a (<API 31)"
        val mode = runCatching { audioManager.encodedSurroundMode }.getOrNull()
            ?: return "n/a"
        return when (mode) {
            AudioManager.ENCODED_SURROUND_OUTPUT_NEVER -> "NEVER"
            AudioManager.ENCODED_SURROUND_OUTPUT_ALWAYS -> "ALWAYS"
            AudioManager.ENCODED_SURROUND_OUTPUT_AUTO -> "AUTO"
            AudioManager.ENCODED_SURROUND_OUTPUT_MANUAL -> "MANUAL"
            else -> "unknown"
        }
    }
}
