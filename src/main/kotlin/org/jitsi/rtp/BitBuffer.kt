package org.jitsi.rtp

import com.sun.javaws.exceptions.InvalidArgumentException
import java.nio.ByteBuffer

/**
 * Wraps a [ByteBuffer] and allows reading at a bit level
 * (instead of a byte level).  This means that
 * reads of bits will be done relative to the previous bit
 * position in the current byte.  Only when the last bit of
 * a byte is read will the buffer's position increment to
 * the next byte.  Bit reads across byte boundaries is
 * not supported.
 *
 * All reads of the [ByteBuffer] do not need to go through
 * this class; changes in the [ByteBuffer]'s will cause
 * [BitBuffer]'s bit offset to reset the next time a read is
 * done.
 */
class BitBuffer(private val buf: ByteBuffer) {
    /**
     * The bit offset into the current byte
     */
    private var currBitPos: Int = 0

    /**
     * The byte we're reading from in the [ByteBuffer]
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
            throw InvalidArgumentException(arrayOf("$currBitPos", "$numDesiredBits"))
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
