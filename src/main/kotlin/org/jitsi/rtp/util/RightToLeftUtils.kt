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

import org.jitsi.rtp.extensions.getBitAsBool
import java.nio.ByteBuffer

/**
 * The way some packets' data is formatted, it's easiest to read bytes/bits from right
 * to left (see [GenericNackBlp], for example).  The utils in this file make it more
 * convenient to deal with [ByteBuffer]s and [Byte]s formatted in such a way, by treating
 * the least-significant byte/bit as position '0'
 */
class RightToLeftBufferUtils {
    companion object {
        private fun getLeftToRightPosition(bufferSize: Int, rightToLeftPosition: Int): Int =
            bufferSize - rightToLeftPosition - 1

        private fun get(buf: ByteBuffer, byteIndex: Int): Byte {
            return buf.get(getLeftToRightPosition(buf.limit(), byteIndex))
        }

        private fun put(buf: ByteBuffer, byteIndex: Int, b: Byte) {
            buf.put(getLeftToRightPosition(buf.limit(), byteIndex), b)
        }

        fun getBitAsBool(buf: ByteBuffer, rightToLeftBitPosition: Int): Boolean {
            val rightToLeftBytePosition = rightToLeftBitPosition / 8
            val byte = get(buf, rightToLeftBytePosition)
            return RightToLeftByteUtils.getBitAsBool(byte, rightToLeftBitPosition % 8)
        }

        fun putBit(buf: ByteBuffer, rightToLeftBitPosition: Int, isSet: Boolean) {
            val rightToLeftBytePosition = rightToLeftBitPosition / 8
            val byte = get(buf, rightToLeftBytePosition)
            val newByte = RightToLeftByteUtils.putBit(byte, rightToLeftBitPosition % 8, isSet)
            put(buf, rightToLeftBytePosition, newByte)
        }
    }
}

class RightToLeftByteUtils {
    companion object {
        /**
         * Given a bit position in the 'right to left' space (where the LSB is position 0), return a position
         * in the 'left to right' space (where the LSB is position 7)
         */
        private fun getLeftToRightPosition(rightToLeftPosition: Int): Int = 7 - rightToLeftPosition

        fun getBitAsBool(b: Byte, bitPosition: Int): Boolean = b.getBitAsBool(getLeftToRightPosition(bitPosition))

        fun putBit(b: Byte, bitPosition: Int, isSet: Boolean): Byte {
            return org.jitsi.rtp.extensions.putBit(b, getLeftToRightPosition(bitPosition), isSet)
        }
    }
}
