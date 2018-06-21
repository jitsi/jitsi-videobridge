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

/**
 * Return the value of the bit at position [bitPos], where
 * 0 represents the left-most bit and 7 represents the right-most
 * bit of the current byte
 */
fun Byte.getBit(bitPos: Int): Int {
    val mask = 0b1 shl (7 - bitPos)
    return (this.toInt() and mask) ushr (7 - bitPos)
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
