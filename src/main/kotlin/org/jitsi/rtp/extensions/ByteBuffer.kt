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

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

/**
 * Print the entire contents of the [ByteBuffer] as hex
 * digits
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
