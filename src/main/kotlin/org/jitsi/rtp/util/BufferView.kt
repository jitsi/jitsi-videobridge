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
package org.jitsi.rtp.util

import org.jitsi.rtp.extensions.toHex
import java.nio.ByteBuffer
import java.util.*

private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

/**
 * Represents a 'view' into some subset of an array by encapsulating the
 * array itself and the offset and length of the bit this view represents.
 * Two [BufferView]s are considered equal if their views contain the
 * same data.
 */
//TODO: make these private and only accessible via getters?  (to enforce
// calls to 'shrink'? is that even necessary?
//TODO: i think we can get rid of this and just use ByteBuffer#subBuffer
data class BufferView(val array: ByteArray, val offset: Int, var length: Int) {
    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other?.javaClass != javaClass) {
            return false
        }
        other as BufferView
        // We purposefully don't compare the offsets here: two views which contain
        // the same data but at different locations in their backing arrays are
        // considered equal
        if (length != other.length) {
            return false
        }
        for (i in 0 until length) {
            val ourIndex = offset + i
            val theirIndex = other.offset + i
            if (array[ourIndex] != other.array[theirIndex]) {
                return false
            }
        }
        return true
    }

    /**
     * Reduce the view of this buffer by [numBytes] bytes
     * from the end.
     */
    fun shrink(numBytes: Int) {
        length -= numBytes
    }

    override fun hashCode(): Int = Arrays.hashCode(array) + offset.hashCode() + length.hashCode()

    fun toHex(): String {
        val result = StringBuffer()

        for (i in offset until (offset + length)) {
            val octet = array[i].toInt()
            val firstIndex = (octet and 0xF0).ushr(4)
            val secondIndex = octet and 0x0F
            result.append(HEX_CHARS[firstIndex])
            result.append(HEX_CHARS[secondIndex])
            if (((i - offset) + 1) % 16 == 0) {
                result.append("\n")
            } else if (((i - offset) + 1) % 4 == 0) {
                result.append(" ")
            }
        }

        return result.toString()
    }
}
