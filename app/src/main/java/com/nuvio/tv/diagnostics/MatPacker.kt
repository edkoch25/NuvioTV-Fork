/*
 *  Kotlin port of Kodi's CPackerMAT (TrueHD -> MAT/IEC61937 packer).
 *
 *  Copyright (C) 2024 Team Kodi
 *  Copyright (C) 2010-2021 Hendrik Leppkes
 *  Ported to Kotlin 2026 for the NuvioTV fork (§9.5 MAT workstream).
 *
 *  SPDX-License-Identifier: GPL-2.0-or-later
 *
 *  The TrueHD seamless-branch handling (output-timing discontinuity detection and
 *  padding carry-forward) is derived from the TrueHD MAT packer in LAV Filters by
 *  Hendrik Leppkes (Nevcairiel), decoder/LAVAudio/BitstreamMAT.cpp. This is a
 *  line-faithful port of xbmc/cores/AudioEngine/Utils/PackerMAT.{h,cpp} at Kodi
 *  master; comments and structure are preserved so it can be diffed against upstream.
 *
 *  Byte-for-byte behaviour matters: the emitted MAT frames are written to an
 *  ENCODING_IEC61937 AudioTrack (little-endian S16 on ARM), so the byte order of
 *  the payload here must match. Gate 0 (§9.5) proved this box clocks the carrier
 *  only with LE preambles and a real lossless data-type; the IEC burst-preamble
 *  words are prepended by the sink, not here.
 */
package com.nuvio.tv.diagnostics

import java.util.ArrayDeque

/**
 * Packs raw TrueHD access units into 61440-byte MAT frames suitable for wrapping in
 * an IEC61937 burst and writing to a 192 kHz / 8-channel / 16-bit AudioTrack.
 *
 * Usage: call [packTrueHD] per TrueHD access unit; when it returns true, drain
 * [getOutputFrame] until it returns null. Pure logic - no Android or media3 types.
 */
class MatPacker {

    private enum class Type { PADDING, DATA }

    private class MatState {
        var init: Boolean = false
        var ratebits: Int = 0
        var outputTiming: Int = 0          // uint16 (kept masked to 0xFFFF)
        var outputTimingValid: Boolean = false
        var prevFrametime: Int = 0         // uint16
        var prevFrametimeValid: Boolean = false
        var matFramesize: Long = 0         // uint32 range; Long avoids sign issues
        var prevMatFramesize: Long = 0
        var padding: Long = 0
        var nOutputTimeOffset: Int = 0     // int32
    }

    private var state = MatState()
    private var bufferCount = 0
    private var buffer = ByteArray(MAT_BUFFER_SIZE)
    private val outputQueue = ArrayDeque<ByteArray>()

    /** Parsed fields from a TrueHD major sync frame. */
    private class MajorSyncInfo {
        var ratebits: Int = 0
        var outputTiming: Int = 0
        var outputTimingPresent: Boolean = false
        var valid: Boolean = false
    }

    /**
     * Pack one TrueHD access unit. Returns true if at least one complete MAT frame
     * is now available from [getOutputFrame].
     */
    fun packTrueHD(data: ByteArray, size: Int): Boolean {
        // discard too small packets (cannot be valid)
        if (size < 10) return false

        val isMajorSync = readBE32(data, 4) == FORMAT_MAJOR_SYNC

        var info = MajorSyncInfo()
        if (isMajorSync) {
            info = parseMajorSyncHeaders(data, size)
            // Fall back to the master's ratebits read when the full header parse fails.
            state.ratebits = if (info.valid) info.ratebits else (data[8].toInt() and 0xFF) ushr 4
        } else if (!state.prevFrametimeValid) {
            // only start streaming on a major sync frame
            return false
        }

        val frameTime = readBE16(data, 2)
        var spaceSize: Long = 0
        val frameSamples = 40 shl (state.ratebits and 7)

        // Advance the output timing counter (16-bit wrap).
        state.outputTiming = (state.outputTiming + frameSamples) and 0xFFFF

        if (info.outputTimingPresent) {
            if (state.outputTimingValid && info.outputTiming != state.outputTiming) {
                // seamless branch point: carry padding forward instead of dropping frames.
                state.prevFrametimeValid = false
                spaceSize = (frameSamples * (64 ushr (state.ratebits and 7))).toLong()

                var prevOutput = (info.outputTiming - frameSamples) and 0xFFFF
                if (prevOutput < frameTime) prevOutput += 0x10000
                val currentFrameOutputOffset = prevOutput - frameTime

                if (state.nOutputTimeOffset >= currentFrameOutputOffset) {
                    state.padding +=
                        ((state.nOutputTimeOffset - currentFrameOutputOffset) *
                            (64 ushr (state.ratebits and 7))).toLong()
                }
            }
            state.outputTiming = info.outputTiming
            state.outputTimingValid = true
        }

        // compute final padded size for the previous frame, if any
        if (state.prevFrametimeValid) {
            spaceSize = (((frameTime - state.prevFrametime) and 0xFFFF) *
                (64 ushr (state.ratebits and 7))).toLong()
        }

        // if for some reason the spaceSize fails, align the actual frame size
        if (spaceSize < state.prevMatFramesize) {
            spaceSize = ffAlign(state.prevMatFramesize, (64 ushr (state.ratebits and 7)).toLong())
        }

        state.padding += (spaceSize - state.prevMatFramesize)

        // Seek/corruption safety net: unbounded positive padding would spin the loop.
        if (state.padding > MAT_BUFFER_SIZE.toLong() * 5) {
            state = MatState()
            state.init = true
            buffer = ByteArray(MAT_BUFFER_SIZE)
            bufferCount = 0
            return false
        }

        // Record frame-time to output-time offset for the next discontinuity.
        if (state.outputTimingValid) {
            var prevOutput = (state.outputTiming - frameSamples) and 0xFFFF
            if (prevOutput < frameTime) prevOutput += 0x10000
            state.nOutputTimeOffset = prevOutput - frameTime
        }

        state.prevFrametime = frameTime
        state.prevFrametimeValid = true

        // Write the MAT header into the fresh buffer
        if (count() == 0) {
            writeHeader()
            if (!state.init) {
                state.init = true
                state.matFramesize = 0
            }
        }

        // write padding of the previous frame (if any)
        while (state.padding > 0) {
            writePadding()
            if (count() == MAT_BUFFER_SIZE) {
                flushPacket()
                writeHeader()
            }
        }

        // write actual audio data to the buffer
        var remaining = fillDataBuffer(data, 0, size, Type.DATA)

        if (remaining != 0 || count() == MAT_BUFFER_SIZE) {
            flushPacket()
            if (remaining != 0) {
                writeHeader()
                remaining = fillDataBuffer(data, size - remaining, remaining, Type.DATA)
            }
        }

        state.prevMatFramesize = state.matFramesize
        state.matFramesize = 0

        return outputQueue.isNotEmpty()
    }

    /** Drain one complete MAT frame, or null if none pending. */
    fun getOutputFrame(): ByteArray? = outputQueue.pollFirst()

    private fun count(): Int = bufferCount

    private fun writeHeader() {
        // buffer is already MAT_BUFFER_SIZE and zeroed on (re)alloc; reset contents.
        java.util.Arrays.fill(buffer, 0.toByte())

        // reserve space for the IEC header, then the MAT start code
        val size = BURST_HEADER_SIZE + MAT_START_CODE.size
        System.arraycopy(MAT_START_CODE, 0, buffer, BURST_HEADER_SIZE, MAT_START_CODE.size)
        bufferCount = size

        state.matFramesize += size.toLong()

        if (state.padding > 0) {
            if (state.padding > size) {
                state.padding -= size.toLong()
                state.matFramesize = 0
            } else {
                state.matFramesize = (size - state.padding)
                state.padding = 0
            }
        }
    }

    private fun writePadding() {
        if (state.padding == 0L) return

        // padding capped to Int for the buffer call; MAT_BUFFER_SIZE bounds it.
        val req = state.padding.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val remaining = fillDataBuffer(null, 0, req, Type.PADDING)

        if (remaining >= 0) {
            state.padding = remaining.toLong()
            state.matFramesize = 0
        } else {
            // more padding than requested was written (MAT middle/end marker landed)
            state.padding = 0
            state.matFramesize = (-remaining).toLong()
        }
    }

    private fun appendData(data: ByteArray?, dataOffset: Int, size: Int, type: Type) {
        if (type == Type.DATA && data != null) {
            System.arraycopy(data, dataOffset, buffer, bufferCount, size)
        }
        state.matFramesize += size.toLong()
        bufferCount += size
    }

    /**
     * Returns leftover byte count (>=0) that didn't fit, or a NEGATIVE value meaning
     * MORE than requested was consumed because a MAT middle/end marker was written -
     * this mirrors the C++ contract exactly (WritePadding relies on the sign).
     */
    private fun fillDataBuffer(data: ByteArray?, dataOffset: Int, size: Int, type: Type): Int {
        if (count() >= MAT_BUFFER_LIMIT) return size

        var remaining = size

        // Write MAT middle marker if this write straddles MAT_POS_MIDDLE.
        if (count() <= MAT_POS_MIDDLE && count() + size > MAT_POS_MIDDLE) {
            val nBytesBefore = MAT_POS_MIDDLE - count()
            appendData(data, dataOffset, nBytesBefore, type)
            remaining -= nBytesBefore

            appendData(MAT_MIDDLE_CODE, 0, MAT_MIDDLE_CODE.size, Type.DATA)
            if (type == Type.PADDING) remaining -= MAT_MIDDLE_CODE.size

            if (remaining > 0) {
                remaining = fillDataBuffer(
                    data,
                    if (data != null) dataOffset + nBytesBefore else 0,
                    remaining,
                    type
                )
            }
            return remaining
        }

        // Not enough room: write what fits and add the MAT end code.
        if (count() + size >= MAT_BUFFER_LIMIT) {
            val nBytesBefore = MAT_BUFFER_LIMIT - count()
            appendData(data, dataOffset, nBytesBefore, type)
            remaining -= nBytesBefore

            appendData(MAT_END_CODE, 0, MAT_END_CODE.size, Type.DATA)
            if (type == Type.PADDING) remaining -= MAT_END_CODE.size

            return remaining
        }

        appendData(data, dataOffset, size, type)
        return 0
    }

    private fun flushPacket() {
        if (count() == 0) return
        // buffer is exactly MAT_BUFFER_SIZE here by construction.
        outputQueue.addLast(buffer.copyOf())
        buffer = ByteArray(MAT_BUFFER_SIZE)
        bufferCount = 0
    }

    /**
     * Parse a TrueHD major sync frame for ratebits and, if a restart header is
     * present, the output-timing counter. MSB-first big-endian bit reader,
     * matching Kodi's CBitstreamReader exactly.
     */
    private fun parseMajorSyncHeaders(p: ByteArray, buffsize: Int): MajorSyncInfo {
        val info = MajorSyncInfo()
        if (buffsize < 32) return info

        var majorSyncSize = 28
        if ((p[29].toInt() and 1) != 0) { // restart header exists
            val extensionSize = (p[30].toInt() and 0xFF) ushr 4
            majorSyncSize += 2 + extensionSize * 2
        }
        if (majorSyncSize > buffsize) return info

        val bs = BitReader(p, 4, buffsize - 4)

        bs.skip(32) // format_sync
        info.ratebits = bs.read(4)
        info.valid = true

        // channel/presentation/signature/flags block, then variable_rate + peak_data_rate
        bs.skip(1 + 1 + 2 + 2 + 2 + 5 + 2 + 13 + 16 + 16 + 16 + 1 + 15)

        val numSubstreams = bs.read(4)
        bs.skip(4 + (majorSyncSize - 17) * 8)

        // substream directory
        for (i in 0 until numSubstreams) {
            val extraSubstreamWord = bs.read(1)
            bs.skip(15)
            if (extraSubstreamWord != 0) bs.skip(16)
        }

        // substream segments
        for (i in 0 until numSubstreams) {
            if (bs.read(1) != 0) {          // block_header_exists
                if (bs.read(1) != 0) {      // restart_header_exists
                    bs.skip(14)             // restart_sync_word
                    info.outputTiming = bs.read(16)
                    info.outputTimingPresent = true
                }
            }
            break
        }
        return info
    }

    /** MSB-first big-endian bit reader, faithful to Kodi's CBitstreamReader::GetBits. */
    private class BitReader(private val buf: ByteArray, start: Int, private val length: Int) {
        private var bytePos = start
        private var offBits = 0

        fun read(nbits: Int): Int {
            val ret = getBits(nbits)
            offBits += nbits
            bytePos += offBits / 8
            offBits %= 8
            return ret
        }

        fun skip(nbits: Int) {
            offBits += nbits
            bytePos += offBits / 8
            offBits %= 8
        }

        private fun getBits(nbits: Int): Int {
            var nbytes = (offBits + nbits) / 8
            if (((offBits + nbits) % 8) > 0) nbytes++
            if (bytePos + nbytes > buf.size || nbytes > 4) return 0

            var ret = 0L
            for (i in 0 until nbytes) {
                ret += (buf[bytePos + i].toLong() and 0xFF) shl ((nbytes - i - 1) * 8)
            }
            val shift = (4 - nbytes) * 8 + offBits
            // ((ret << shift) >> shift) masks the high bits; then align to the field.
            ret = ((ret shl shift) and 0xFFFFFFFFL) ushr shift
            ret = ret ushr ((nbytes * 8) - nbits - offBits)
            return ret.toInt()
        }
    }

    companion object {
        private const val FORMAT_MAJOR_SYNC = 0xF8726FBAL

        private const val BURST_HEADER_SIZE = 8
        const val MAT_BUFFER_SIZE = 61440
        private const val MAT_BUFFER_LIMIT = MAT_BUFFER_SIZE - 24 // MAT end code size
        private const val MAT_POS_MIDDLE = 30708 + BURST_HEADER_SIZE

        private val MAT_START_CODE = byteArrayOf(
            0x07, 0x9E.toByte(), 0x00, 0x03, 0x84.toByte(), 0x01, 0x01,
            0x01, 0x80.toByte(), 0x00, 0x56, 0xA5.toByte(), 0x3B, 0xF4.toByte(),
            0x81.toByte(), 0x83.toByte(), 0x49, 0x80.toByte(), 0x77, 0xE0.toByte()
        )
        private val MAT_MIDDLE_CODE = byteArrayOf(
            0xC3.toByte(), 0xC1.toByte(), 0x42, 0x49, 0x3B, 0xFA.toByte(),
            0x82.toByte(), 0x83.toByte(), 0x49, 0x80.toByte(), 0x77, 0xE0.toByte()
        )
        private val MAT_END_CODE = byteArrayOf(
            0xC3.toByte(), 0xC2.toByte(), 0xC0.toByte(), 0xC4.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x97.toByte(), 0x11,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )

        private fun readBE32(b: ByteArray, off: Int): Long {
            return (((b[off].toLong() and 0xFF) shl 24) or
                ((b[off + 1].toLong() and 0xFF) shl 16) or
                ((b[off + 2].toLong() and 0xFF) shl 8) or
                (b[off + 3].toLong() and 0xFF))
        }

        private fun readBE16(b: ByteArray, off: Int): Int {
            return ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
        }

        private fun ffAlign(x: Long, a: Long): Long = (x + a - 1) and (a - 1).inv()
    }
}
