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
package org.jitsi.rtp.extensions

import java.nio.ByteBuffer

/**
 * Move this [ByteBuffer]'s position back one Byte
 */
fun ByteBuffer.rewindOneByte() {
    this.position(this.position() - 1)
}

/**
 * Put the right-most 3 bytes of [value]
 * into the buffer
 */
fun ByteBuffer.put3Bytes(value: Int) {
    this.put(((value and 0x00FF0000) ushr 16).toByte())
    this.put(((value and 0x0000FF00) ushr 8).toByte())
    this.put((value and 0x000000FF).toByte())
}

fun ByteBuffer.put3Bytes(index: Int, value: Int) {
    this.put(index, ((value and 0x00FF0000) ushr 16).toByte())
    this.put(index + 1, ((value and 0x0000FF00) ushr 8).toByte())
    this.put(index + 2, (value and 0x000000FF).toByte())
}

/**
 * Reads the next 3 bytes into the right-most
 * 3 bytes of an Int
 */
fun ByteBuffer.get3Bytes(): Int {
    val byte1 = get().toInt() shl 16
    val byte2 = get().toInt() shl 8
    val byte3 = get().toInt() and 0xFF
    return byte1 or byte2 or byte3
}

fun ByteBuffer.get3Bytes(index: Int): Int {
    val byte1 = get(index).toInt() shl 16
    val byte2 = get(index + 1).toInt() shl 8
    val byte3 = get(index + 2).toInt() and 0xFF
    return byte1 or byte2 or byte3

}

/**
 * Put the right-most [numBits] bits from [src] into the byte at [byteIndex]
 * starting at position [destBitPos].  [destBitPos] is a 0-based index of the
 * bit in the byte at [byteIndex], where 0 is the MSB and 7 is the LSB.
 */
fun ByteBuffer.putBits(byteIndex: Int, destBitPos: Int, src: Byte, numBits: Int) {
    var byte = get(byteIndex)
    byte = putBits(byte, destBitPos, numBits, src)
    put(byteIndex, byte)
}

fun ByteBuffer.putBitAsBoolean(byteIndex: Int, destBitPos: Int, isSet: Boolean) {
    var byte = get(byteIndex)
    byte = putBit(byte, destBitPos, isSet)
    put(byteIndex, byte)
}

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

/**
 * Print the entire contents of the [ByteBuffer] as hex
 * digits
 * TODO: should the spacing here (every 4 chunks) be right-justified?
 * (meaning, if you have a buffer with a size that isn't a multiple
 * of 8, the right-most chunks should be represented as a chunk
 * of 8, rather than the left-most.  for example:
 * data: 01000A00 64006400 7B000000 00
 * should probably be printed as:
 * data: 01 000A0064 0064007B 00000000
 */
fun ByteBuffer.toHex() : String {
    val result = StringBuffer()

    val prevPosition = position()
    for (i in 0 until limit()) {
        val octet = get(i).toInt()
        val firstIndex = (octet and 0xF0).ushr(4)
        val secondIndex = octet and 0x0F
        result.append(HEX_CHARS[firstIndex])
        result.append(HEX_CHARS[secondIndex])
        if ((i + 1) % 16 == 0) {
            result.append("\n")
        } else if ((i + 1) % 4 == 0) {
            result.append(" ")
        }
    }
    position(prevPosition)

    return result.toString()
}

/**
 * Returns a newly constructed [ByteBuffer] whose position 0 will
 * start at [startPosition] in the current buffer and whose limit
 * will be [size]
 */
fun ByteBuffer.subBuffer(startPosition: Int, size: Int): ByteBuffer {
    return (duplicate().position(startPosition) as ByteBuffer).slice().limit(size) as ByteBuffer
}

/**
 * Returns a newly constructed [ByteBuffer] whose position 0 will
 * start at [startPosition] in the current buffer and whose limit
 * will be the end of the original buffer
 */
fun ByteBuffer.subBuffer(startPosition: Int): ByteBuffer {
    return (duplicate().position(startPosition) as ByteBuffer).slice()
}
