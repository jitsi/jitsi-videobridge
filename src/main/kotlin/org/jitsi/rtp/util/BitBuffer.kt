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

import org.jitsi.rtp.extensions.getBit
import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.putBit
import org.jitsi.rtp.extensions.rewindOneByte
import java.nio.ByteBuffer

/**
 * A [ByteBuffer] is indexed at the level of [Byte]s:
 * read and write operations are done at the Byte (or greater)
 * level, and each read or write increments the ByteBuffer's
 * position by one Byte.  Some operations require reading and
 * writing at the bit level (like parsing packet header fields),
 * so ByteBuffer's byte-level API is awkward to work with.
 * [BitBuffer] wraps a [ByteBuffer] and allows reading at a bit level
 * instead of a byte level.  This means that
 * reads of bits will be done relative to the previous bit
 * position in the current byte.  Only when the last bit of
 * a byte is read will the buffer's position increment to
 * the next byte.  Bit reads across byte boundaries is
 * not supported.
 *
 * All reads of the [ByteBuffer] do not need to go through
 * this class; changes in the wrapped [ByteBuffer]'s position
 * in between accesses from the wrapping [BitBuffer] will cause
 * [BitBuffer]'s bit offset to reset the next time a read is
 * done.
 *
 * This is not done through extension functions because we need
 * new state (the current bit position) and isn't done through a
 * subclass because [ByteBuffer] is abstract and the underlying
 * type isn't instantiated directly (it's done through ByteBuffer.allocate)
*/
class BitBuffer(private val buf: ByteBuffer) {
    /**
     * The bit offset into the current byte
     */
    private var currBitPos: Int = 0

    /**
     * The byte we're reading from in the [ByteBuffer].  If the value
     * of [buf.position()] changes in between calls to this BitBuffer,
     * we'll reset [currBitPos] since if the buffer's byte position
     * has advanced, we'll assume the next bit read should be the
     * first bit of the new byte.
     */
    private var currBytePos: Int = buf.position()

    /**
     * If the buffer's current position doesn't match where
     * it was the last time we read from it, we'll assume we
     * should reset to reading the first bit in the current
     * byte rather than continuing to use any existing offset.
     */
    private fun maybeResetCurrBitPos() {
        if (buf.position() != currBytePos) {
            currBitPos = 0
            currBytePos = buf.position()
        }
    }

    /**
     * Make sure the amount of desired bits does not
     * cross a byte boundary
     */
    private fun validateOffset(numDesiredBits: Int) {
        if (currBitPos + numDesiredBits > 8) {
            throw IllegalArgumentException("$currBitPos + $numDesiredBits > 8")
        }
    }

    /**
     * If the most recent bit read did not reach a byte boundary,
     * reset the buffer's position back to the current byte
     * (since we're not done reading its bits yet)
     */
    private fun maybeResetBufPosition() {
        if (currBitPos < 8) {
            buf.rewindOneByte()
        }
    }

    /**
     * Move the current bit position backwards by
     * [numBitsToRewind] bits.  Will NOT rewind
     * to a previous byte (i.e. will not go
     * past bit position 0 of the current byte)
     */
    fun rewindBits(numBitsToRewind: Int) {
        currBitPos = Math.max(currBitPos - numBitsToRewind, 0)
    }

    /**
     * Get [numBits] bits starting at the current bit offset
     * of the current byte. Returns them in the form of a [Byte]
     */
    fun getBits(numBits: Int): Byte {
        maybeResetCurrBitPos()
        validateOffset(numBits)
        val byte = buf.get()
        val result = byte.getBits(currBitPos, numBits)
        currBitPos += numBits
        maybeResetBufPosition()
        return result
    }

    /**
     * Write the right-most [numBits] bits from [data], e.g.:
     * If data was the byte: 0b00000011 and numBits was 2,
     * we'd write '11' into the next two bit positions
     * of the buffer.
     */
    fun putBits(data: Byte, numBits: Int) {
        maybeResetCurrBitPos()
        validateOffset(numBits)
        val currentPosition = buf.position()
        var byte = buf.get()
        for (i in (numBits - 1) downTo 0) {
            // The bits in the given byte are 'right-aligned',
            // but we write them from left to right, so
            // we need to invert the position by doing 7 - i
            val bit = data.getBit(7 - i)
            byte = putBit(byte, currBitPos++, bit == 1)
        }
        buf.put(currentPosition, byte)
        maybeResetBufPosition()
    }

    fun putBoolean(b: Boolean) {
        maybeResetCurrBitPos()
        validateOffset(1)
        val currentPosition = buf.position()
        var byte = buf.get()
        byte = putBit(byte, currBitPos++, b)
        buf.put(currentPosition, byte)
        maybeResetBufPosition()
    }

    /**
     * Get the next bit interpreted as a [Boolean]
     */
    fun getBitAsBoolean(): Boolean {
        maybeResetCurrBitPos()
        validateOffset(1)
        val byte = buf.get()
        val result = byte.getBitAsBool(currBitPos)
        currBitPos += 1
        maybeResetBufPosition()
        return result
    }
}
