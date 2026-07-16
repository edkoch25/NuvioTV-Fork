package com.nuvio.tv.diagnostics

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Gate 0 — MAT/IEC61937 workstream (§9.5) capability probe.
 *
 * Answers the single go/no-go question before any packer work: does THIS box accept an
 * [AudioTrack] configured for [AudioFormat.ENCODING_IEC61937], and does the playback head
 * advance when data is written to it? If not, self-packed MAT is a dead end on this silicon
 * (and the fallback [AudioFormat.ENCODING_DOLBY_MAT] carriage is checked in the same run).
 *
 * Framework-only: no media3, no packer, no sink or UI changes. Runs once at app start and
 * logs everything under tag [TAG]. Every cell is independently guarded so a partial or failed
 * query is logged and interpreted, never auto-failed and never able to crash startup.
 *
 * Primary probe cell (validated against Kodi's Android AudioTrack sink, which verifies TrueHD /
 * DTS-HD MA passthrough at exactly this config): IEC61937 @ 192 kHz, 8-channel (7.1).
 *
 * Stays in-tree for the Xiaomi TV Box S (task 4.0) re-run on different silicon.
 */
object Gate0Probe {

    private const val TAG = "Gate0"

    // Carrier for TrueHD/DTS-HD MA over IEC61937 is constant-rate 192 kHz, 8-channel, 16-bit.
    private const val IEC_RATE_HZ = 192_000
    private const val PRIMARY_CHANNEL_MASK = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND

    // ~500 ms of the parameterised burst, to see whether the HAL clocks the carrier.
    private const val HEAD_ADVANCE_WRITE_MS = 500

    // IEC61937 burst-preamble sync words (verified against FFmpeg spdifenc.c): Pa=0xF872,
    // Pb=0x4E1F. nt39 wrote raw zeros (no sync words) so the receiver never locked and the head
    // stayed at 0 despite 1.5 MB accepted; nt41 varies byte order and data-type per cell.

    @Volatile
    private var hasRun = false

    /** Idempotent; safe to call from Application.onCreate on any build. */
    fun runOnce(context: Context) {
        if (hasRun) return
        hasRun = true
        val appContext = context.applicationContext
        // Never let a diagnostic take down startup.
        Thread({
            try {
                probe(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "probe aborted", t)
            }
        }, "gate0-probe").start()
    }

    private data class Cell(
        val label: String,
        val encoding: Int,
        val sampleRate: Int,
        val channelMask: Int
    )

    private fun probe(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            Log.e(TAG, "no AudioManager; aborting")
            return
        }

        Log.i(TAG, "===== Gate 0 IEC61937 capability probe =====")
        Log.i(
            TAG,
            "device=${Build.MANUFACTURER}/${Build.MODEL} board=${Build.BOARD} " +
                "sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}"
        )
        dumpSurroundSetting(context)
        dumpHdmiEncodings(am)

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()

        // IEC cells: the primary pass cell first, then two diagnostic cells that carry only
        // lower-bitrate formats (192k/stereo = 6.144 Mbit/s = E-AC3 territory, insufficient for
        // TrueHD; 48k/stereo lower still) — useful for interpreting a primary-cell failure.
        val iecCells = listOf(
            Cell("IEC61937 192k/8ch  [PRIMARY]", AudioFormat.ENCODING_IEC61937, IEC_RATE_HZ, PRIMARY_CHANNEL_MASK),
            Cell("IEC61937 192k/2ch  [diag]", AudioFormat.ENCODING_IEC61937, IEC_RATE_HZ, AudioFormat.CHANNEL_OUT_STEREO),
            Cell("IEC61937 48k/2ch   [diag]", AudioFormat.ENCODING_IEC61937, 48_000, AudioFormat.CHANNEL_OUT_STEREO)
        )

        // Reference cells: query-only. DOLBY_MAT is the fallback-carriage check — if raw
        // IEC61937 fails but DOLBY_MAT is a supported direct format, §9.5 may survive via it.
        // DOLBY_TRUEHD is media3's current HAL-packs path, for a like-for-like baseline.
        val refCells = listOf(
            Cell("DOLBY_MAT 192k/8ch", AudioFormat.ENCODING_DOLBY_MAT, IEC_RATE_HZ, PRIMARY_CHANNEL_MASK),
            Cell("DOLBY_TRUEHD 48k/8ch", AudioFormat.ENCODING_DOLBY_TRUEHD, 48_000, PRIMARY_CHANNEL_MASK),
            Cell("E_AC3 48k/6ch", AudioFormat.ENCODING_E_AC3, 48_000, AudioFormat.CHANNEL_OUT_5POINT1),
            Cell("AC3 48k/6ch", AudioFormat.ENCODING_AC3, 48_000, AudioFormat.CHANNEL_OUT_5POINT1),
            Cell("DTS_HD 48k/8ch", AudioFormat.ENCODING_DTS_HD, 48_000, PRIMARY_CHANNEL_MASK)
        )

        Log.i(TAG, "----- support queries (getDirectPlaybackSupport / isDirectPlaybackSupported) -----")
        iecCells.forEach { queryCell(am, attrs, it) }
        refCells.forEach { queryCell(am, attrs, it) }

        // Head-advance matrix. The PCM control runs first: it proves the measurement itself
        // works (real silence must advance a PCM head) so an IEC non-advance can be read as an
        // IEC-path property, not a broken probe. Then four IEC61937 192k/8ch cells vary byte
        // order (LE/BE) and burst data-type (null 0x00 / TrueHD 0x16). Each cell cross-checks
        // getPlaybackHeadPosition against AudioTimestamp.framePosition, because an
        // offload/bitstream track may not update the legacy head even while audio flows.
        Log.i(TAG, "----- head-advance matrix -----")
        val pcmControl = pcmControlAdvances(attrs)
        // Pd is the length code: 0 for null, 61424 bytes for TrueHD (bytes not bits - a known
        // packer trap). Unit is the burst period: 2048 (null), 61440 (one TrueHD MAT frame),
        // 65536 (DTS-HD HBR, pkt_offset = period 16384 x 4, from FFmpeg spdif_header_dts4).
        val cLeNull = runBurstCell(attrs, "LE null", bigEndian = false, dataType = 0x00, pdLength = 0, unitBytes = 2048)
        val cBeNull = runBurstCell(attrs, "BE null", bigEndian = true, dataType = 0x00, pdLength = 0, unitBytes = 2048)
        val cLeTrueHd = runBurstCell(attrs, "LE TrueHD", bigEndian = false, dataType = 0x16, pdLength = 61424, unitBytes = 61440)
        val cBeTrueHd = runBurstCell(attrs, "BE TrueHD", bigEndian = true, dataType = 0x16, pdLength = 61424, unitBytes = 61440)
        // DTS-HD MA (the other lossless codec, carrying DTS:X): data_type 0x11 | HBR subtype
        // 0x5 << 8 = 0x0511 (192k HBR -> period 16384 -> subtype 0x5, FFmpeg spdif_dts4_subtype).
        // Confirms the carrier clocks for DTS-HD too, and doubles as a second data-type test.
        // Zero payload like the TrueHD cells; a non-advance is read the same cautious way.
        val cLeDtsHd = runBurstCell(attrs, "LE DTS-HD", bigEndian = false, dataType = 0x0511, pdLength = 0, unitBytes = 65536)
        val cBeDtsHd = runBurstCell(attrs, "BE DTS-HD", bigEndian = true, dataType = 0x0511, pdLength = 0, unitBytes = 65536)
        val anyAdvanced = cLeNull || cBeNull || cLeTrueHd || cBeTrueHd || cLeDtsHd || cBeDtsHd

        // Verdict summary line (parse target).
        val primaryQuery = queryStrings(am, attrs, iecCells.first())
        Log.i(
            TAG,
            "VERDICT primary_query=${primaryQuery} pcm_control=$pcmControl " +
                "leNull=$cLeNull beNull=$cBeNull leTrueHd=$cLeTrueHd beTrueHd=$cBeTrueHd " +
                "leDtsHd=$cLeDtsHd beDtsHd=$cBeDtsHd any_iec_advanced=$anyAdvanced " +
                "=> pass=${primaryQuery.contains("SUPPORTED") && anyAdvanced}"
        )
        probeMatEncodingBuilds(am, attrs)
        Log.i(TAG, "===== Gate 0 probe complete =====")
    }

    /** Logs both the modern (API 33+) and legacy support queries; they can disagree. */
    private fun queryCell(am: AudioManager, attrs: AudioAttributes, cell: Cell) {
        Log.i(TAG, "  ${cell.label}: ${queryStrings(am, attrs, cell)}")
    }

    private fun queryStrings(am: AudioManager, attrs: AudioAttributes, cell: Cell): String {
        val format = try {
            AudioFormat.Builder()
                .setEncoding(cell.encoding)
                .setSampleRate(cell.sampleRate)
                .setChannelMask(cell.channelMask)
                .build()
        } catch (t: Throwable) {
            return "format-build-failed(${t.javaClass.simpleName})"
        }

        val modern = if (Build.VERSION.SDK_INT >= 33) {
            try {
                decodeDirectSupport(AudioManager.getDirectPlaybackSupport(format, attrs))
            } catch (t: Throwable) {
                "modern-err(${t.javaClass.simpleName})"
            }
        } else {
            "modern-n/a"
        }

        val legacy = if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (AudioTrack.isDirectPlaybackSupported(format, attrs)) "legacy-SUPPORTED" else "legacy-no"
            } catch (t: Throwable) {
                "legacy-err(${t.javaClass.simpleName})"
            }
        } else {
            "legacy-n/a"
        }

        return "$modern | $legacy"
    }

    private fun decodeDirectSupport(flags: Int): String {
        if (flags == AudioManager.DIRECT_PLAYBACK_NOT_SUPPORTED) return "modern-NOT_SUPPORTED(0)"
        val parts = mutableListOf<String>()
        // OFFLOAD_GAPLESS is a COMPOSITE constant that includes the OFFLOAD bit, so a plain
        // OFFLOAD result matched the old non-zero test and mislabelled as gapless (nt39 printed
        // OFFLOAD_GAPLESS for flag 1 and 5). Test full-mask equality instead.
        if (flags and AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED ==
            AudioManager.DIRECT_PLAYBACK_OFFLOAD_GAPLESS_SUPPORTED
        ) {
            parts += "OFFLOAD_GAPLESS"
        } else if (flags and AudioManager.DIRECT_PLAYBACK_OFFLOAD_SUPPORTED != 0) {
            parts += "OFFLOAD"
        }
        if (flags and AudioManager.DIRECT_PLAYBACK_BITSTREAM_SUPPORTED != 0) parts += "BITSTREAM"
        return "modern-SUPPORTED[$flags:${parts.joinToString("+")}]"
    }

    /**
     * Runs one IEC61937 192k/8ch head-advance cell with the given burst parameters. Constructs
     * the track, writes ~500 ms of the parameterised burst, then polls both
     * getPlaybackHeadPosition and AudioTimestamp.framePosition across several samples. Returns
     * true if EITHER advanced - a legacy head that stays flat while the timestamp advances means
     * audio is flowing but the old API doesn't track this offload path (not a real failure).
     * A non-advance on both is read alongside the PCM control, not treated as an automatic fail.
     */
    private fun runBurstCell(
        attrs: AudioAttributes,
        label: String,
        bigEndian: Boolean,
        dataType: Int,
        pdLength: Int,
        unitBytes: Int
    ): Boolean {
        var track: AudioTrack? = null
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_IEC61937)
                .setSampleRate(IEC_RATE_HZ)
                .setChannelMask(PRIMARY_CHANNEL_MASK)
                .build()

            val minBuf = AudioTrack.getMinBufferSize(IEC_RATE_HZ, PRIMARY_CHANNEL_MASK, AudioFormat.ENCODING_IEC61937)
            if (minBuf <= 0) {
                Log.w(TAG, "  [$label] minBuffer non-positive")
                return false
            }
            val bufSize = minBuf.coerceAtLeast(64 * 1024)
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "  [$label] not INITIALIZED (state=${track.state})")
                return false
            }

            track.play()

            val totalToWrite = IEC_RATE_HZ * 8 * 2 * HEAD_ADVANCE_WRITE_MS / 1000
            val payload = buildBurst(bigEndian, dataType, pdLength, unitBytes, minOf(bufSize, 64 * 1024))
            var written = 0
            while (written < totalToWrite) {
                val n = track.write(payload, 0, payload.size)
                if (n <= 0) break
                written += n
            }

            val heads = IntArray(6)
            val frames = LongArray(6)
            for (i in heads.indices) {
                heads[i] = track.playbackHeadPosition
                val ts = AudioTimestamp()
                frames[i] = if (track.getTimestamp(ts)) ts.framePosition else -1L
                if (i < heads.lastIndex) Thread.sleep(100)
            }
            val headAdv = heads.last() > heads.first()
            val tsAdv = frames.last() > 0L && frames.last() > frames.first()
            Log.i(
                TAG,
                "  [$label] wrote=$written playState=${track.playState} " +
                    "head=[${heads.joinToString(">")}] ts=[${frames.joinToString(">")}] " +
                    "headAdv=$headAdv tsAdv=$tsAdv"
            )
            return headAdv || tsAdv
        } catch (t: Throwable) {
            Log.w(TAG, "  [$label] threw ${t.javaClass.simpleName}: ${t.message}")
            return false
        } finally {
            try {
                track?.pause(); track?.flush(); track?.stop()
            } catch (_: Throwable) {
            }
            try {
                track?.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Builds a repeating IEC61937 burst: each unit is the preamble Pa=0xF872, Pb=0x4E1F,
     * Pc=dataType, Pd=pdLength (as 16-bit words in the requested byte order) then zero stuffing
     * to unitBytes. Sync words verified against FFmpeg spdifenc.c; Pc TrueHD=0x16 and Pd=61424
     * bytes verified against FFmpeg spdif.h. Zero payload - this tests whether the HAL clocks on
     * header/framing alone, not full decode.
     */
    private fun buildBurst(
        bigEndian: Boolean,
        dataType: Int,
        pdLength: Int,
        unitBytes: Int,
        totalBytes: Int
    ): ByteArray {
        val buf = ByteArray(totalBytes)
        fun putWord(pos: Int, word: Int) {
            if (bigEndian) {
                buf[pos] = ((word ushr 8) and 0xFF).toByte()
                buf[pos + 1] = (word and 0xFF).toByte()
            } else {
                buf[pos] = (word and 0xFF).toByte()
                buf[pos + 1] = ((word ushr 8) and 0xFF).toByte()
            }
        }
        var off = 0
        while (off + unitBytes <= totalBytes) {
            putWord(off, 0xF872)      // Pa
            putWord(off + 2, 0x4E1F)  // Pb
            putWord(off + 4, dataType) // Pc
            putWord(off + 6, pdLength) // Pd
            // remainder of the unit is zero stuffing (ByteArray init).
            off += unitBytes
        }
        return buf
    }

    /**
     * Positive control for the head-advance MEASUREMENT itself: a PCM 48k/stereo track fed real
     * silence must advance its head. If this advances and the IEC cell does not, the difference
     * is a genuine IEC-path property, not a broken measurement; if even this does not advance,
     * head-advance is unreliable on this device and IEC results can't be read from it.
     */
    private fun pcmControlAdvances(attrs: AudioAttributes): Boolean {
        var track: AudioTrack? = null
        try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(48_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val minBuf = AudioTrack.getMinBufferSize(
                48_000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                Log.w(TAG, "  PCM control: minBuffer non-positive")
                return false
            }
            val bufSize = minBuf.coerceAtLeast(16 * 1024)
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(TAG, "  PCM control: not INITIALIZED (state=${track.state})")
                return false
            }
            track.play()
            val silence = ByteArray(minOf(bufSize, 16 * 1024))
            val total = 48_000 * 2 * 2 * 300 / 1000
            var written = 0
            while (written < total) {
                val nw = track.write(silence, 0, silence.size)
                if (nw <= 0) break
                written += nw
            }
            val h0 = track.playbackHeadPosition
            Thread.sleep(300)
            val h1 = track.playbackHeadPosition
            val adv = h1 > h0
            Log.i(TAG, "  PCM control: head $h0 -> $h1 (advanced=$adv)")
            return adv
        } catch (t: Throwable) {
            Log.w(TAG, "  PCM control threw ${t.javaClass.simpleName}: ${t.message}")
            return false
        } finally {
            try {
                track?.pause(); track?.flush(); track?.stop()
            } catch (_: Throwable) {
            }
            try {
                track?.release()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * nt52 - the definitive ENCODING_DOLBY_MAT availability test. Gate 0's original DOLBY_MAT cell
     * was a QUERY at 192 kHz (the IEC carrier rate), but MAT is a 48 kHz-based container, so that
     * query proved nothing. This ACTUALLY attempts AudioTrack construction across the plausible MAT
     * rates and channel masks, with a DOLBY_TRUEHD control (the working passthrough format) to prove
     * the probe itself is sound. If no MAT config initialises but the control does, ENCODING_DOLBY_MAT
     * is genuinely closed to apps on this device - the last app-side lever for the MAT workstream.
     */
    private fun probeMatEncodingBuilds(am: AudioManager, attrs: AudioAttributes) {
        Log.i(TAG, "----- ENCODING_DOLBY_MAT build probe (nt52) -----")
        val matCells = listOf(
            Cell("MAT 48k/2ch", AudioFormat.ENCODING_DOLBY_MAT, 48_000, AudioFormat.CHANNEL_OUT_STEREO),
            Cell("MAT 48k/8ch", AudioFormat.ENCODING_DOLBY_MAT, 48_000, PRIMARY_CHANNEL_MASK),
            Cell("MAT 96k/2ch", AudioFormat.ENCODING_DOLBY_MAT, 96_000, AudioFormat.CHANNEL_OUT_STEREO),
            Cell("MAT 176k/2ch", AudioFormat.ENCODING_DOLBY_MAT, 176_400, AudioFormat.CHANNEL_OUT_STEREO),
            Cell("MAT 192k/2ch", AudioFormat.ENCODING_DOLBY_MAT, 192_000, AudioFormat.CHANNEL_OUT_STEREO),
            Cell("MAT 192k/8ch", AudioFormat.ENCODING_DOLBY_MAT, 192_000, PRIMARY_CHANNEL_MASK)
        )
        val control = Cell("CONTROL DOLBY_TRUEHD 48k/8ch", AudioFormat.ENCODING_DOLBY_TRUEHD, 48_000, PRIMARY_CHANNEL_MASK)

        var anyMatInit = false
        matCells.forEach { if (probeOneBuild(am, attrs, it)) anyMatInit = true }
        val controlInit = probeOneBuild(am, attrs, control)

        Log.i(TAG, "MAT_VERDICT any_mat_initialised=$anyMatInit control_truehd_initialised=$controlInit" +
            " => mat_usable=$anyMatInit probe_valid=$controlInit")
    }

    /** Query + getMinBufferSize + ACTUAL AudioTrack build for one cell; true if INITIALIZED. Never plays. */
    private fun probeOneBuild(am: AudioManager, attrs: AudioAttributes, cell: Cell): Boolean {
        val query = queryStrings(am, attrs, cell)
        val minBuf = try {
            AudioTrack.getMinBufferSize(cell.sampleRate, cell.channelMask, cell.encoding)
        } catch (t: Throwable) { -99 }
        var track: AudioTrack? = null
        var state = "not-attempted"
        var initialised = false
        try {
            val format = AudioFormat.Builder()
                .setEncoding(cell.encoding)
                .setSampleRate(cell.sampleRate)
                .setChannelMask(cell.channelMask)
                .build()
            val buf = if (minBuf > 0) minBuf.coerceAtLeast(64 * 1024) else 64 * 1024
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            initialised = track.state == AudioTrack.STATE_INITIALIZED
            state = if (initialised) "INITIALIZED" else "state=${track.state}"
        } catch (t: Throwable) {
            state = "build-threw(${t.javaClass.simpleName}:${t.message})"
        } finally {
            try { track?.release() } catch (_: Throwable) {}
        }
        Log.i(TAG, "  [${cell.label}] query={$query} minBuf=$minBuf build=$state")
        return initialised
    }

    private fun dumpSurroundSetting(context: Context) {
        // Precondition reminder: this must read as Auto (2) for the probe to be meaningful; a
        // Never (1) value invalidates every IEC cell. Read defensively — key/type vary by device.
        val value = try {
            Settings.Global.getString(context.contentResolver, "encoded_surround_output")
        } catch (t: Throwable) {
            "read-err(${t.javaClass.simpleName})"
        }
        Log.i(TAG, "encoded_surround_output=$value  (0=AUTO_DEFAULT, 1=NEVER, 2=ALWAYS/MANUAL vary by OEM)")
    }

    private fun dumpHdmiEncodings(am: AudioManager) {
        try {
            val outputs: Array<AudioDeviceInfo> = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            for (dev in outputs) {
                val isHdmi = dev.type == AudioDeviceInfo.TYPE_HDMI ||
                    dev.type == AudioDeviceInfo.TYPE_HDMI_ARC ||
                    dev.type == AudioDeviceInfo.TYPE_HDMI_EARC
                if (!isHdmi) continue
                val encodings = try {
                    dev.encodings.joinToString(",")
                } catch (t: Throwable) {
                    "enc-err(${t.javaClass.simpleName})"
                }
                Log.i(TAG, "HDMI device type=${dev.type} product=${dev.productName} encodings=[$encodings]")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "HDMI encodings dump threw ${t.javaClass.simpleName}")
        }
    }
}
