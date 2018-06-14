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
 * [bitEndPos] must >= [bitStartPos]
 */
fun Byte.getBits(bitStartPos: Int, bitEndPos: Int): Int {
    var result = 0
    //TODO: we could also shift left to drop off the
    // unwanted bits on the left, then shift right to
    // drop off those bits.  NOTE: this doesn't work
    // because left shifts of a byte when casted to an int
    // don't drop the values (since the Int has plenty
    // of room)
//    println("starting number ${this.toInt().toString(2)}")
//    println("starting number shl $bitStartPos: ${(this.toInt() shl bitStartPos).toString(2)}")
//    result = (this.toInt() shl bitStartPos) ushr bitStartPos
//    println("result after dropping left $bitStartPos bits: ${result.toString(2)}")
//    result = result ushr (7 - bitEndPos)
//    println("result after dropping right ${7 - bitEndPos} bits: ${result.toString(2)}")

    // shiftOffset represents how far, from the right-most
    // bit position, the bit at bitStartPos should be
    // at in the result
    val shiftOffset = (bitEndPos - bitStartPos)
    (bitStartPos..bitEndPos).forEachIndexed { index, value ->
        result = result or (getBit(value) shl (shiftOffset - index))
    }
    return result
}
