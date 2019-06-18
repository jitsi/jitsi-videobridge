/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.rtp.extensions

/**
 * Return the value of the bit at position [bitPos], where
 * 0 represents the left-most bit and 7 represents the right-most
 * bit of the current byte
 */
fun Byte.getBit(bitPos: Int): Int {
    val mask = 0b1 shl (7 - bitPos)
    return (this.toInt() and mask) ushr (7 - bitPos)
}

/**
 * Set or unset the bit at [bitPos] of byte [b] according to [isSet]
 * Unfortunately we can't do this as an extension function because
 * we can't modify the Byte via 'this' in an extension function.
 */
fun putBit(b: Byte, bitPos: Int, isSet: Boolean): Byte {
    return if (isSet) {
        (b.toInt() or (0b10000000 ushr bitPos)).toByte()
    } else {
        (b.toInt() and (0b10000000 ushr bitPos).inv()).toByte()
    }
}

/**
 * Puts the right-most [numBits] bits from [src] into [dest]  starting at
 * [bitStartPos]
 * [bitStartPos] is a 0 based index into the byte [dest], where the MSB
 * is position 0 and the LSB is position 7.
 * Given the values:
 * bitStartPos = 4
 * numBits = 3
 * src = 0b101
 * dest = 0b00000000
 *
 * The returned Byte will be:
 * 0b00001010
 */
fun putBits(dest: Byte, bitStartPos: Int, numBits: Int, src: Byte): Byte {
    // Start the position in src to the first bit we'll assign.
    val valueBitPosition = 7 - numBits + 1
    var result = dest
    for (i in 0 until numBits) {
        val isSet = src.getBitAsBool(valueBitPosition + i)
        result = putBit(result, bitStartPos + i, isSet)
    }
    return result
}

/**
 * Get the bit at [bitPos] as a [Boolean].  Will return [true]
 * if the bit is set (1) and [false] if it is unset (0)
 */
fun Byte.getBitAsBool(bitPos: Int): Boolean = getBit(bitPos) == 1

/**
 * Get [numBits] bits starting at [bitStartPos], shift them
 * all the way to the right and return the result as a [Byte]
 * [numBits] must be <= 8
 */
fun Byte.getBits(bitStartPos: Int, numBits: Int): Byte {
    // Subtract 1 since the bit at 'bitStartPos' will be included, e.g.:
    // A call getBits(4, 2) will read bits 4 and 5
    val bitEndPos = bitStartPos + numBits - 1
    var result = 0
    // shiftOffset represents how far, from the right-most
    // bit position, the bit at bitStartPos should be
    // at in the result
    val shiftOffset = (bitEndPos - bitStartPos)
    (bitStartPos..bitEndPos).forEachIndexed { index, value ->
        result = result or (getBit(value) shl (shiftOffset - index))
    }
    return result.toByte()
}
