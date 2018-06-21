package org.jitsi.rtp

import com.sun.javaws.exceptions.InvalidArgumentException
import java.nio.ByteBuffer

class BitBuffer(private val buf: ByteBuffer) {
    /**
     * The bit offset into the current byte
     */
    private var currBitPos: Int = 0
    private var currBytePos: Int = buf.position()
    private fun maybeResetCurrBitPos() {
        // If the buffer isn't on the same byte as it was
        // last time we read, then reset the current bit
        // position
        if (buf.position() != currBytePos) {
            currBitPos = 0
            currBytePos = buf.position()
        }
    }
    private fun validateOffset(numDesiredBits: Int) {
        if (currBitPos + numDesiredBits > 8) {
            throw InvalidArgumentException(arrayOf("$currBitPos", "$numDesiredBits"))
        }
    }

    private fun maybeResetBufPosition(previousBufPosition: Int) {
        if (currBitPos < 8) {
//            println("CurrBitPos is less than 8, reset to previous byte")
            buf.position(previousBufPosition)
        }
    }

    private fun moveCurrBitPos(numBitsRead: Int) {
        // Subtract 1 since numBitsRead included a bit
        // at the current position
        currBitPos += numBitsRead
//        println("Read $numBitsRead bits, curr bit position is now $currBitPos")
    }

    fun getBits(numBits: Int): Byte {
//        println("Reading $numBits bits, current position is $currBitPos")
        maybeResetCurrBitPos()
        validateOffset(numBits)
        val previousBufPosition = buf.position()
        val byte = buf.get()
        val result = byte.getBits(currBitPos, numBits)
        moveCurrBitPos(numBits)
        maybeResetBufPosition(previousBufPosition)
        return result
    }

    fun getBitAsBoolean(): Boolean {
        maybeResetCurrBitPos()
        validateOffset(1)
        val previousBufPosition = buf.position()
        val byte = buf.get()
        val result = byte.getBitAsBool(currBitPos)
        moveCurrBitPos(1)
        maybeResetBufPosition(previousBufPosition)
        return result
    }

    fun get(): Byte = buf.get()
    fun getShort(): Short = buf.short
    fun getInt(): Int = buf.int
}
