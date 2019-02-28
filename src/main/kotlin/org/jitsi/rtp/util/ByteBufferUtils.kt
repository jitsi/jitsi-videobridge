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

import org.jitsi.rtp.extensions.subBuffer
import java.nio.ByteBuffer

class ByteBufferUtils {
    companion object {
        val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocate(0)
        /**
         * Returns [buf] if it is non-null and its capacity is large enough to hold
         * [requiredCapacity] bytes.  If not, allocate and return a new ByteBuffer of
         * size [requiredCapacity].
         * Note that, in the case of allocating a new buffer, the content of the
         * original buffer ([buf]) is NOT copied over.
         */
        fun ensureCapacity(buf: ByteBuffer?, requiredCapacity: Int): ByteBuffer {
            val newBuf = if (buf == null || buf.capacity() < requiredCapacity) {
                ByteBuffer.allocate(requiredCapacity)
            } else {
                buf
            }
            newBuf.rewind()
            newBuf.limit(requiredCapacity)
            return newBuf
        }

        /**
         * 'Grow' the given buffer, if needed.  This means that, if [buf]'s
         * capacity is not >= [requiredCapacity], allocate a new buffer of
         * size [requiredCapacity] and copy all of [buf]'s content into it
         * (starting at position 0 [buf] and the newly-allocated buffer).
         *
         * The returned buffer will have capacity/limit [requiredCapacity]
         * and be at position 0.
         */
        fun growIfNeeded(buf: ByteBuffer, requiredCapacity: Int): ByteBuffer {
            return if (buf.capacity() < requiredCapacity) {
                val newBuf = ByteBuffer.allocate(requiredCapacity)
                buf.rewind()
                newBuf.put(buf)

                newBuf.rewind() as ByteBuffer
            } else {
                if (buf.limit() < requiredCapacity) {
                    buf.limit(requiredCapacity)
                }
                buf.rewind() as ByteBuffer
            }
        }

        /**
         * [ByteBuffer.wrap] will set the buffer's current position to the offset, what this
         * method does is create a sub buffer (via [ByteBuffer.subBuffer]) where the sub buffer's
         * position 0 is the offset.
         */
        fun wrapSubArray(byteArray: ByteArray, offset: Int, length: Int) =
            ByteBuffer.wrap(byteArray).subBuffer(offset, length)
    }
}

fun byteBufferOf(vararg elements: Byte): ByteBuffer = ByteBuffer.wrap(byteArrayOf(*elements))

fun byteBufferOf(vararg elements: Any): ByteBuffer {
    val bytes = elements.map { (it as Number).toByte() }.toByteArray()
    return ByteBuffer.wrap(bytes)
}
