package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil

/**
 * Reads the transfer characteristics straight out of an HEVC SPS.
 *
 * Why this exists. `MatroskaExtractor` builds `Format.colorInfo` only when the MKV
 * `Colour` master element is present (`if (hasColorInfo)`), and the fork's vendored
 * `dvmkv` copy does the same. Both parse the codec-private with `HevcConfig` — which
 * *does* extract `colorTransfer` from the SPS VUI — but keep only `initializationData`,
 * `nalUnitLengthFieldLength` and `codecs` from it and discard the colour. The `Colour`
 * element is optional and plenty of remuxes are muxed without it, so `colorInfo` comes
 * back null and the HUD's HDR row falls through to "SDR" on a genuine HDR10 stream —
 * while the decoder and the display follow the SPS in-band and correctly go HDR.
 *
 * `Format.initializationData` holds the VPS/SPS/PPS in Annex-B form (HevcConfig writes a
 * 4-byte start code before each NAL), and `NalUnitUtil.parseH265SpsNalUnit` is public, so
 * the fallback is a short walk over data we already have.
 *
 * HEVC only. AV1 and VP9 carry colour in their own sequence headers; neither shows up in
 * the remuxes this fork plays, and neither is handled here.
 */
@UnstableApi
internal object PlaybackVideoColorTransfer {

    private const val H265_NAL_UNIT_TYPE_SPS = 33
    private const val NAL_HEADER_BYTES = 2

    // Formats do not change often; parse once per initialisation blob.
    @Volatile
    private var cachedInitData: ByteArray? = null

    @Volatile
    private var cachedTransfer: Int = Format.NO_VALUE

    /**
     * The colour transfer the container declared, or — when it declared none — the one the
     * HEVC SPS carries. [Format.NO_VALUE] when neither is available.
     */
    fun of(format: Format): Int {
        val declared = format.colorInfo?.colorTransfer ?: Format.NO_VALUE
        if (declared != Format.NO_VALUE) return declared

        val initData = format.initializationData.firstOrNull() ?: return Format.NO_VALUE
        val cached = cachedInitData
        if (cached != null && cached.contentEquals(initData)) return cachedTransfer

        val parsed = parseSpsColorTransfer(initData)
        cachedInitData = initData
        cachedTransfer = parsed
        return parsed
    }

    private fun parseSpsColorTransfer(data: ByteArray): Int = runCatching {
        var offset = 0
        while (offset + 4 + NAL_HEADER_BYTES <= data.size) {
            if (!isStartCode(data, offset)) {
                offset++
                continue
            }
            val nalStart = offset + 4
            var nalEnd = data.size
            var scan = nalStart
            while (scan + 4 <= data.size) {
                if (isStartCode(data, scan)) {
                    nalEnd = scan
                    break
                }
                scan++
            }
            // HEVC NAL header: forbidden_zero_bit, then 6 bits of nal_unit_type.
            val nalType = (data[nalStart].toInt() shr 1) and 0x3F
            if (nalType == H265_NAL_UNIT_TYPE_SPS) {
                return@runCatching NalUnitUtil
                    .parseH265SpsNalUnit(data, nalStart, nalEnd, /* vpsData= */ null)
                    .colorTransfer
            }
            offset = nalEnd
        }
        Format.NO_VALUE
    }.getOrDefault(Format.NO_VALUE)

    private fun isStartCode(data: ByteArray, at: Int): Boolean =
        at + 4 <= data.size &&
            data[at] == 0.toByte() &&
            data[at + 1] == 0.toByte() &&
            data[at + 2] == 0.toByte() &&
            data[at + 3] == 1.toByte()
}
