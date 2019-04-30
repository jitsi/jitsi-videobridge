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

package org.jitsi.rtp.extensions.bytearray
import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.putBit
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.BufferPool
import java.lang.Math.abs

/**
 * Put the right-most [numBits] bits from [src] into the byte at [byteIndex]
 * starting at position [destBitPos].  [destBitPos] is a 0-based index of the
 * bit in the byte at [byteIndex], where 0 is the MSB and 7 is the LSB.
 */
fun ByteArray.putBits(byteIndex: Int, destBitPos: Int, src: Byte, numBits: Int) {
    var byte = get(byteIndex)
    byte = putBits(byte, destBitPos, numBits, src)
    set(byteIndex, byte)
}

fun ByteArray.getBitAsBool(byteOffset: Int, bitOffset: Int): Boolean =
    get(byteOffset).getBitAsBool(bitOffset)

fun ByteArray.putBitAsBoolean(byteIndex: Int, destBitPos: Int, isSet: Boolean) {
    var byte = get(byteIndex)
    byte = putBit(byte, destBitPos, isSet)
    set(byteIndex, byte)
}

fun ByteArray.getShort(byteIndex: Int): Short {
    val b1 = get(byteIndex).toPositiveInt()
    val b2 = get(byteIndex + 1).toPositiveInt()
    return ((b1 shl 8) + b2).toShort()
}

fun ByteArray.putShort(byteIndex: Int, value: Short) {
    set(byteIndex, (value.toPositiveInt() ushr 8).toByte())
    set(byteIndex + 1, value.toByte())
}

fun ByteArray.get3Bytes(byteIndex: Int): Int {
    val b1 = get(byteIndex).toPositiveInt()
    val b2 = get(byteIndex + 1).toPositiveInt()
    val b3 = get(byteIndex + 2).toPositiveInt()
    return (b1 shl 16) + (b2 shl 8) + b3
}

fun ByteArray.put3Bytes(byteIndex: Int, value: Int) {
    set(byteIndex, ((value and 0x00FF0000) ushr 16).toByte())
    set(byteIndex + 1, ((value and 0x0000FF00) ushr 8).toByte())
    set(byteIndex + 2, (value and 0x000000FF).toByte())
}

fun ByteArray.getInt(byteIndex: Int): Int {
    val b1 = get(byteIndex).toPositiveInt()
    val b2 = get(byteIndex + 1).toPositiveInt()
    val b3 = get(byteIndex + 2).toPositiveInt()
    val b4 = get(byteIndex + 3).toPositiveInt()
    return ((b1 shl 24) + (b2 shl 16) + (b3 shl 8) + b4)
}

fun ByteArray.putInt(byteIndex: Int, value: Int) {
    set(byteIndex, (value ushr 24).toByte())
    set(byteIndex + 1, (value ushr 16).toByte())
    set(byteIndex + 2, (value ushr 8).toByte())
    set(byteIndex + 3, value.toByte())
}

fun byteArrayOf(vararg elements: Number): ByteArray {
    return elements.map { it.toByte() }.toByteArray()
}

/**
 * Shifts the data from [startPos] to [endPos] [numBytes] to the right.
 * Note that this method may increase the given buffer's limit, up to
 * its capacity.
 *
 * Note that [startPos] and [endPos] are zero-based and numBytes
 * must be positive!
 */
fun ByteArray.shiftDataRight(startPos: Int, endPos: Int, numBytes: Int) {
    if (numBytes < 0) {
        throw Exception("")
    }
    for (index in endPos downTo startPos) {
        set(index + numBytes, get(index))
    }
}

fun ByteArray.shiftDataLeft(startPos: Int, endPos: Int, numBytes: Int) {
    for (index in startPos..endPos) {
        set(index - numBytes, get(index))
    }
}

/**
 * Shifts the data from [startPos] to [endPos] by [delta] bytes.
 * if [delta] is negative, the data will be shifted to the left,
 * if [delta] is positive, the data will be shifted to the right
 */
fun ByteArray.shiftData(startPos: Int, endPos: Int, delta: Int) {
    when {
        delta < 0 -> shiftDataLeft(startPos, endPos, abs(delta))
        delta > 0 -> shiftDataRight(startPos, endPos, delta)
    }
}

fun ByteArray.cloneFromPool(): ByteArray {
    val clone = BufferPool.getArray(size)
    System.arraycopy(this, 0, clone, 0, size)
    return clone
}

operator fun ByteArray.plus(other: ByteArray): ByteArray {
    val newArray = BufferPool.getArray(size + other.size)
    System.arraycopy(this, 0, newArray, 0, size)
    System.arraycopy(other, 0, newArray, size, other.size)

    return newArray
}

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
/**
 * Print the entire contents of the [ByteArray] as hex
 * digits
 */
fun ByteArray.toHex(offset: Int = 0, length: Int = (size - offset)): String {
    val result = StringBuffer()
    var position = 0

    for (i in offset until (offset + length)) {
        val octet = get(i).toInt()
        val firstIndex = (octet and 0xF0).ushr(4)
        val secondIndex = octet and 0x0F
        result.append(HEX_CHARS[firstIndex])
        result.append(HEX_CHARS[secondIndex])
        if ((position + 1) % 16 == 0) {
            result.append("\n")
        } else if ((position + 1) % 4 == 0) {
            result.append(" ")
        }
        position++
    }

    return result.toString()
}

/**
 * Returns the hash code of the segment of this [ByteArray] starting at 'start' and ending in 'end' (exclusive).
 */
fun ByteArray.hashCodeOfSegment(start: Int, end: Int): Int {
    var result = 1
    for (i in start.coerceIn(0, size) until end.coerceIn(0, size)) {
        result = 31 * result + this[i]
    }
    return result
}

class ByteArrayUtils {
    companion object {
        val emptyByteArray = BufferPool.getArray(0)
    }
}
