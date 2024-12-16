/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.rtp.util

import kotlin.experimental.and

/**
 * Read individual bits, and unaligned sets of bits, from a [ByteArray], with an incrementing offset.
 */
/* TODO: put this in jitsi-utils? */
class BitReader(val buf: ByteArray, private val byteOffset: Int = 0, private val byteLength: Int = buf.size) {
    private var offset = byteOffset * 8
    private val byteBound = byteOffset + byteLength

    init {
        check(byteOffset >= 0) { "byteOffset must be >= 0" }
        check(byteBound <= buf.size) { "byteOffset + byteLength must be <= buf.size" }
    }

    /** Clone with the current state (offset) and a new length in bytes. */
    fun clone(newByteLength: Int): BitReader {
        check(offset % 8 == 0) { "Cannot clone BitReader with unaligned offset" }
        check(offset / 8 + newByteLength <= byteBound) {
            "newByteLength $newByteLength exceeds buffer length $byteLength after offset $byteOffset"
        }
        return BitReader(buf, offset / 8, newByteLength)
    }

    fun remainingBits(): Int = byteBound * 8 - offset

    /** Read a single bit from the buffer, as a boolean, incrementing the offset. */
    fun bitAsBoolean(): Boolean {
        val byteIdx = offset / 8
        val bitIdx = offset % 8
        check(byteIdx < byteBound) {
            "offset $offset ($byteIdx/$bitIdx) invalid in buffer of length $byteLength after offset $byteOffset"
        }
        val byte = buf[byteIdx]
        val mask = (1 shl (7 - bitIdx)).toByte()
        offset++

        return (byte and mask) != 0.toByte()
    }

    /** Read a single bit from the buffer, as an integer, incrementing the offset. */
    fun bit() = if (bitAsBoolean()) 1 else 0

    /** Read [n] bits from the buffer, returning them as an unsigned integer. */
    fun bits(n: Int): Int {
        require(n < Int.SIZE_BITS)

        var ret = 0

        /* TODO: optimize this */
        repeat(n) {
            ret = ret shl 1
            ret = ret or bit()
        }

        return ret
    }

    /** Read [n] bits from the buffer, returning them as an unsigned long. */
    fun bitsLong(n: Int): Long {
        require(n < Long.SIZE_BITS)

        var ret = 0L

        /* TODO: optimize this */
        repeat(n) {
            ret = ret shl 1
            ret = ret or bit().toLong()
        }

        return ret
    }

    /** Skip forward [n] bits in the buffer. */
    fun skipBits(n: Int) {
        offset += n
    }

    /** Read a non-symmetric unsigned integer with max *value* [n] from the buffer.
     * (Note: *not* the number of bits.)
     *  See https://aomediacodec.github.io/av1-rtp-spec/#a82-syntax
     */
    fun ns(n: Int): Int {
        var w = 0
        var x = n
        while (x != 0) {
            x = x shr 1
            w++
        }
        val m = (1 shl w) - n
        val v = bits(w - 1)
        if (v < m) {
            return v
        }
        val extraBit = bit()
        return (v shl 1) - m + extraBit
    }

    /**
     * Read a LEB128-encoded unsigned integer.
     * https://aomediacodec.github.io/av1-spec/#leb128
     */
    fun leb128(): Long {
        var value = 0L
        (0..8).forEach { i ->
            val hasNext = bitAsBoolean()
            value = value or (bits(7).toLong() shl (i * 7))
            if (!hasNext) return value
        }
        return value
    }

    /** Reset the reader to the beginning of the buffer */
    fun reset() {
        offset = byteOffset * 8
    }
}
