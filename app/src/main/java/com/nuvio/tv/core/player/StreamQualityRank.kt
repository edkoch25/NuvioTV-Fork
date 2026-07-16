package com.nuvio.tv.core.player

import com.nuvio.tv.domain.model.Stream

/**
 * Deterministic quality ranking for auto-select (task 3.7).
 *
 * Rank order (Q18: resolution wins outright, no cross-tier trading):
 * resolution -> HDR (DV > HDR10+ > HDR10/HLG > SDR) -> source type
 * (Remux > BluRay > WEB-DL > WEBRip/BDRip > HDTV) -> audio
 * (lossless > DTS:X > DD+/DTS > DD/AC3) -> container (mkv > mp4).
 *
 * All signals are parsed from the stream's advertised text (name, title,
 * description, filename, URL). Ties keep the incoming list order, so the
 * result is stable and deterministic for the same source list. Bare "Atmos"
 * is deliberately not treated as lossless: DD+ Atmos is lossy, while TrueHD
 * Atmos already matches the lossless tier via "TrueHD".
 */
object StreamQualityRank {

    internal data class Signals(
        val resolution: Int,
        val hdr: Int,
        val sourceType: Int,
        val audio: Int,
        val container: Int
    )

    private val RES_2160 = Regex("2160p|\\b4k\\b|\\buhd\\b", RegexOption.IGNORE_CASE)
    private val RES_1080 = Regex("1080p", RegexOption.IGNORE_CASE)
    private val RES_720 = Regex("720p", RegexOption.IGNORE_CASE)
    private val RES_SD = Regex("576p|480p", RegexOption.IGNORE_CASE)

    private val HDR_DV = Regex("dolby[ ._-]?vision|\\bdovi\\b|\\bdv\\b", RegexOption.IGNORE_CASE)
    private val HDR_10P = Regex("hdr10\\+|hdr10plus", RegexOption.IGNORE_CASE)
    private val HDR_10 = Regex("hdr10|\\bhdr\\b|\\bhlg\\b", RegexOption.IGNORE_CASE)

    private val SRC_REMUX = Regex("remux", RegexOption.IGNORE_CASE)
    private val SRC_BLURAY = Regex("blu[ ._-]?ray", RegexOption.IGNORE_CASE)
    private val SRC_WEBDL = Regex("web[ ._-]?dl", RegexOption.IGNORE_CASE)
    private val SRC_RIP = Regex("web[ ._-]?rip|bd[ ._-]?rip|br[ ._-]?rip", RegexOption.IGNORE_CASE)
    private val SRC_HDTV = Regex("hdtv", RegexOption.IGNORE_CASE)

    private val AUDIO_LOSSLESS = Regex(
        "true[ ._-]?hd|dts[ ._-]?hd([ ._-]?ma)?|\\bflac\\b|\\blpcm\\b|\\bpcm\\b",
        RegexOption.IGNORE_CASE
    )
    private val AUDIO_DTSX = Regex("dts[ ._:-]?x\\b", RegexOption.IGNORE_CASE)
    private val AUDIO_MID = Regex("dd\\+|ddp|e[ ._-]?ac[ ._-]?3|\\bdts\\b", RegexOption.IGNORE_CASE)
    private val AUDIO_LOW = Regex("\\bac[ ._-]?3\\b|dd[ ._]?5[ ._]?1|\\bdd\\b", RegexOption.IGNORE_CASE)

    private val CONTAINER_MKV = Regex("\\bmkv\\b", RegexOption.IGNORE_CASE)

    internal fun searchableText(stream: Stream): String = buildString {
        append(stream.name.orEmpty()).append(' ')
        append(stream.title.orEmpty()).append(' ')
        append(stream.description.orEmpty()).append(' ')
        append(stream.behaviorHints?.filename.orEmpty()).append(' ')
        append(stream.getStreamUrl().orEmpty())
    }

    internal fun signalsFor(text: String): Signals = Signals(
        resolution = when {
            RES_2160.containsMatchIn(text) -> 4
            RES_1080.containsMatchIn(text) -> 3
            RES_720.containsMatchIn(text) -> 2
            RES_SD.containsMatchIn(text) -> 1
            else -> 0
        },
        hdr = when {
            HDR_DV.containsMatchIn(text) -> 3
            HDR_10P.containsMatchIn(text) -> 2
            HDR_10.containsMatchIn(text) -> 1
            else -> 0
        },
        sourceType = when {
            SRC_REMUX.containsMatchIn(text) -> 5
            SRC_BLURAY.containsMatchIn(text) -> 4
            SRC_WEBDL.containsMatchIn(text) -> 3
            SRC_RIP.containsMatchIn(text) -> 2
            SRC_HDTV.containsMatchIn(text) -> 1
            else -> 0
        },
        audio = when {
            AUDIO_LOSSLESS.containsMatchIn(text) -> 4
            AUDIO_DTSX.containsMatchIn(text) -> 3
            AUDIO_MID.containsMatchIn(text) -> 2
            AUDIO_LOW.containsMatchIn(text) -> 1
            else -> 0
        },
        container = if (CONTAINER_MKV.containsMatchIn(text)) 1 else 0
    )

    /** Stable, deterministic best-first ordering of the given streams. */
    fun rank(streams: List<Stream>): List<Stream> {
        if (streams.size <= 1) return streams
        val signalsByStream = streams.associateWith { signalsFor(searchableText(it)) }
        return streams.sortedWith(
            compareByDescending<Stream> { signalsByStream.getValue(it).resolution }
                .thenByDescending { signalsByStream.getValue(it).hdr }
                .thenByDescending { signalsByStream.getValue(it).sourceType }
                .thenByDescending { signalsByStream.getValue(it).audio }
                .thenByDescending { signalsByStream.getValue(it).container }
        )
    }
}
