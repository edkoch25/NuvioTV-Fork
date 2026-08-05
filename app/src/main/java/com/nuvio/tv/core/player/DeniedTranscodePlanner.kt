/*
 * Copyright (C) 2024-2026 NuvioTV contributors
 *
 * This file is part of a fork of NuvioTV (https://github.com/NuvioMedia/NuvioTV)
 * and is licensed under the GNU General Public License v3.0.
 */
package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes

/**
 * F5: decides which denied formats should be transcoded to AC-3 instead of decoded to
 * PCM, as a per-chain choice.
 *
 * Decode-to-PCM stays the default because it is lossless and keeps the full channel
 * count on any receiver that accepts multichannel LPCM. Transcode-to-AC-3 is opt-in,
 * for chains that cannot take multichannel PCM (e.g. a 2-channel-LPCM soundbar, where
 * PCM decode collapses surround to stereo and compressed 5.1 AC-3 is the better
 * outcome). AC-3 is lossy and 5.1-capped, so it must never be forced on a chain that
 * can take multichannel PCM - hence the opt-in.
 *
 * The returned set feeds FfmpegAudioRenderer.setDeniedTranscodeMimes, which widens the
 * renderer's existing force-mode transcode predicate to the listed MIME types. An empty
 * set leaves the renderer behaviourally identical to before.
 *
 * Guards, in order:
 *  1. The user has opted in ([transcodeDeniedToAc3]).
 *  2. Global Force AC-3 mode is off - force mode already transcodes every eligible
 *     format inside the renderer regardless of this set, so keeping the set empty
 *     there keeps force-mode semantics exactly as they were.
 *  3. A fallback decoder exists at all ([AudioPassthroughPolicy.softwareDecodersAvailable]).
 *  4. AC-3 output is actually usable: [AudioPassthroughPolicy.deniesPassthrough] must
 *     be false for AC-3. This one check is the sink-fallback guard - it covers both
 *     the user's AC-3 switch and an F3-learned AC-3 rejection on this chain, because
 *     both feed deniesPassthrough. Transcoding into an output the sink would refuse
 *     would strand the track with no renderer.
 *
 * Only formats the bundled FFmpeg decoder handles are eligible (the same five groups
 * [AudioPassthroughPolicy] can deny), and AC-3 itself is never in the set - it is the
 * transcode target. Stereo sources are excluded inside the renderer's eligibility
 * clause (nothing is gained re-encoding 2.0 to lossy AC-3), so they are not filtered
 * here.
 */
object DeniedTranscodePlanner {

    /** Denied-and-transcode-eligible candidates: every deniable group except AC-3. */
    private val candidateMimeTypes = listOf(
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD
    )

    fun effectiveTranscodeMimes(
        policy: AudioPassthroughPolicy,
        transcodeDeniedToAc3: Boolean,
        forcePassthroughActive: Boolean
    ): Set<String> {
        if (!transcodeDeniedToAc3) return emptySet()
        if (forcePassthroughActive) return emptySet()
        if (!policy.softwareDecodersAvailable) return emptySet()
        if (policy.deniesPassthrough(MimeTypes.AUDIO_AC3)) return emptySet()
        return candidateMimeTypes.filter { policy.deniesPassthrough(it) }.toSet()
    }
}
