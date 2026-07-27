/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 *
 * The per-format passthrough model (one switch per compressed format, phrased as a
 * receiver capability) follows Kodi's audiooutput.{ac3,eac3,dts,truehd,dtshd}passthrough
 * settings. Kodi is GPL-2.0-or-later; no Kodi code is reproduced here.
 */
package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes

/**
 * Which compressed audio formats may leave the box as a bitstream.
 *
 * Android reports what the HDMI/eARC chain claims to decode, and on several chains that
 * report is wrong: the platform offers DTS because the EDID advertises it, the app
 * bitstreams it, and the receiver produces silence. The only reliable correction is the
 * user, who knows what their gear can decode. Each flag therefore means "my receiver
 * handles this format" - on (the default) delegates to the platform report exactly as
 * before; off denies passthrough for that format so the renderer decodes it to PCM.
 *
 * Two deliberate safety properties:
 *
 * 1. [ALLOW_ALL] is the default everywhere, and with it this class denies nothing. A
 *    construction site that forgets to pass a policy therefore keeps current behaviour
 *    rather than silently changing it.
 * 2. A format is only deniable if a fallback decoder demonstrably exists for it. The
 *    five groups below are exactly the formats the bundled FFmpeg audio decoder handles
 *    (ac3, eac3, truehd, dca). AC-4, DTS Express and DTS:X P2 have no mapping in
 *    FfmpegLibrary.getCodecName, so they map to no group and are never denied - denying
 *    them would trade working passthrough for a track the renderer cannot decode.
 *    [softwareDecodersAvailable] is the second half of that guard: with the app's
 *    Decoder Priority set to "Device only" the FFmpeg renderer is absent from the
 *    renderer list, so nothing may be denied at all.
 */
data class AudioPassthroughPolicy(
    val allowAc3: Boolean = true,
    val allowEac3: Boolean = true,
    val allowTrueHd: Boolean = true,
    val allowDts: Boolean = true,
    val allowDtsHd: Boolean = true,
    val softwareDecodersAvailable: Boolean = true
) {

    /** The format families this policy can act on. */
    enum class Group { AC3, EAC3, TRUEHD, DTS, DTS_HD }

    /**
     * True when [mimeType] belongs to a group the user has turned off and a fallback
     * decoder is available, i.e. passthrough must be denied so the format is decoded.
     */
    fun deniesPassthrough(mimeType: String?): Boolean {
        if (!softwareDecodersAvailable) return false
        return when (groupOf(mimeType)) {
            Group.AC3 -> !allowAc3
            Group.EAC3 -> !allowEac3
            Group.TRUEHD -> !allowTrueHd
            Group.DTS -> !allowDts
            Group.DTS_HD -> !allowDtsHd
            null -> false
        }
    }

    /** True when this policy is inert - every format delegates to the platform report. */
    fun allowsEverything(): Boolean =
        allowAc3 && allowEac3 && allowTrueHd && allowDts && allowDtsHd

    companion object {
        /** The default: deny nothing, behave exactly as the platform report dictates. */
        val ALLOW_ALL = AudioPassthroughPolicy()

        /**
         * Maps a sample MIME type to its format group, or null when the format is not one
         * this policy may act on.
         *
         * Matching is by exact equality, never by prefix: DTS Express is
         * "audio/vnd.dts.hd;profile=lbr", which *starts with* the DTS-HD MIME type but has
         * no FFmpeg decoder mapping, so a prefix match would make it wrongly deniable.
         *
         * A null MIME type also yields null. The sink's bitstream test falls back to
         * Format.codecs in that case, but "dts" in a codecs string cannot distinguish DTS
         * from DTS-HD, and guessing would apply the wrong switch. Delegating is safe.
         */
        fun groupOf(mimeType: String?): Group? = when (mimeType) {
            MimeTypes.AUDIO_AC3 -> Group.AC3
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> Group.EAC3
            MimeTypes.AUDIO_TRUEHD -> Group.TRUEHD
            MimeTypes.AUDIO_DTS -> Group.DTS
            MimeTypes.AUDIO_DTS_HD -> Group.DTS_HD
            else -> null
        }
    }
}
