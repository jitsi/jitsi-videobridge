/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.rtp

import java.nio.ByteBuffer
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KMutableProperty0

/**
 * Return the value of the bit at position [bitPos], where
 * 0 represents the left-most bit and 7 represents the right-most
 * bit of the current byte
 */
fun Byte.getBit(bitPos: Int): Int {
    val mask = 0b1 shl (7 - bitPos)
    return (this.toInt() and mask) ushr (7 - bitPos)
}

// To use this would need to include the kotlin-reflect lib
// and even then you'd have to call it like:
// ::myByte.putBit(bitPos, isSet) --> actually even that didn't work
//fun KMutableProperty0<Byte>.putBit(bitPos: Int, isSet: Boolean) {
//    if (isSet) {
//        set((get().toInt() or (0b10000000 ushr bitPos)).toByte())
//    } else {
//       set((get().toInt() or (0b10000000 ushr bitPos).inv()).toByte())
//    }
//}

fun putBit(b: Byte, bitPos: Int, isSet: Boolean): Byte {
    return if (isSet) {
        (b.toInt() or (0b10000000 ushr bitPos)).toByte()
    } else {
        (b.toInt() and (0b10000000 ushr bitPos).inv()).toByte()
    }
}

fun Byte.getBitAsBool(bitPos: Int): Boolean = getBit(bitPos) == 1

/**
 * Isolate the bits in (bitStartPos, bitEndPos) (inclusive),
 * shift them all the way to the right and return them
 * as an int
 * [bitStartPos] + [numBits] must be <= 8
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

fun ByteBuffer.rewindOneByte() {
    this.position(this.position() - 1)
}

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

fun ByteBuffer.toHex() : String {
    val result = StringBuffer()

    val prevPosition = position()
    for (i in 0 until limit()) {
        val octet = get(i).toInt()
        val firstIndex = (octet and 0xF0).ushr(4)
        val secondIndex = octet and 0x0F
        result.append(HEX_CHARS[firstIndex])
        result.append(HEX_CHARS[secondIndex])
    }
    position(prevPosition)

    return result.toString()
}
