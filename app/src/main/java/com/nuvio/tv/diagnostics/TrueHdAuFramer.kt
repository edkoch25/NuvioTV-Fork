/*
 *  §9.5 MAT workstream - Commit B2a: the TrueHD access-unit framer.
 *
 *  MatPacker.packTrueHD needs ONE complete TrueHD access unit per call. But media3's
 *  AudioSink.handleBuffer hands the sink a ByteBuffer that may hold several access
 *  units batched together, and a buffer may be only partially consumable per call.
 *  This framer sits between them: bytes in, whole access units out, buffering any
 *  trailing partial AU until the next chunk completes it.
 *
 *  TrueHD access-unit framing (verified against FFmpeg truehd_core.c line 57 and
 *  mlp_parser.c line 119): the first big-endian 16-bit word's low 12 bits are the
 *  access_unit_length in 16-bit WORDS, so byteLength = (word0 & 0x0FFF) * 2. The top
 *  4 bits are a parity/check nibble. A major-sync AU additionally has
 *  (AV_RB32(data+4) & 0xFFFFFFFE) == 0xF8726FBA at bytes 4..7.
 *
 *  Pure logic - no Android or media3 types - so it is unit-tested standalone.
 */
package com.nuvio.tv.diagnostics

class TrueHdAuFramer {

    // Carry buffer for a partial AU spanning handleBuffer calls. Kept small; a single
    // TrueHD AU is at most 0x0FFF * 2 = 8190 bytes.
    private var carry = ByteArray(0)

    /**
     * Feed a chunk of the TrueHD bitstream; invokes [onAccessUnit] once per complete
     * access unit found (carry + chunk), in order. Returns the number of complete AUs
     * emitted. Any trailing partial AU is retained for the next call.
     *
     * The callback receives a freshly-allocated ByteArray sized exactly to the AU, so
     * it can be handed straight to MatPacker without offset bookkeeping.
     */
    fun feed(chunk: ByteArray, offset: Int, length: Int, onAccessUnit: (ByteArray) -> Int): Int {
        // Assemble carry + this chunk into one contiguous view. Avoiding a copy when
        // carry is empty is the common case (buffers usually arrive AU-aligned).
        val data: ByteArray
        val dataLen: Int
        if (carry.isEmpty()) {
            data = chunk
            dataLen = offset + length
        } else {
            data = ByteArray(carry.size + length)
            System.arraycopy(carry, 0, data, 0, carry.size)
            System.arraycopy(chunk, offset, data, carry.size, length)
            dataLen = data.size
        }
        val start = if (carry.isEmpty()) offset else 0
        carry = ByteArray(0)

        var pos = start
        var emitted = 0
        while (true) {
            // Need at least the 2-byte length header to size the AU.
            if (dataLen - pos < 2) {
                stash(data, pos, dataLen)
                break
            }
            val word0 = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
            val auLen = (word0 and 0x0FFF) * 2

            // A zero length is malformed (would loop forever); drop the rest defensively.
            if (auLen < 4) {
                break
            }
            // Whole AU not yet present - stash the remainder and wait for more.
            if (dataLen - pos < auLen) {
                stash(data, pos, dataLen)
                break
            }

            val au = ByteArray(auLen)
            System.arraycopy(data, pos, au, 0, auLen)
            onAccessUnit(au)
            emitted += 1
            pos += auLen
        }
        return emitted
    }

    /** Drop any buffered partial AU (call on flush/seek). */
    fun reset() {
        carry = ByteArray(0)
    }

    private fun stash(data: ByteArray, from: Int, to: Int) {
        val n = to - from
        carry = if (n <= 0) {
            ByteArray(0)
        } else {
            data.copyOfRange(from, to)
        }
    }

    companion object {
        /** Major-sync signature test: (AV_RB32(au+4) & 0xFFFFFFFE) == 0xF8726FBA. */
        fun isMajorSync(au: ByteArray): Boolean {
            if (au.size < 8) return false
            val w = (((au[4].toLong() and 0xFF) shl 24) or
                ((au[5].toLong() and 0xFF) shl 16) or
                ((au[6].toLong() and 0xFF) shl 8) or
                (au[7].toLong() and 0xFF))
            return (w and 0xFFFFFFFEL) == 0xF8726FBAL
        }
    }
}
