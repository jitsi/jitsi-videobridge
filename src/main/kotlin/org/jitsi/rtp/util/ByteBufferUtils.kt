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
        val EMPTY_BUFFER = ByteBuffer.allocate(0)
        /**
         * Returns [buf] if it is non-null and its limit is large enough to hold
         * [capacity] bytes.  If not, allocate and return a new ByteBuffer of
         * size [capacity]
         */
        fun ensureCapacity(buf: ByteBuffer?, capacity: Int): ByteBuffer {
            return if (buf == null || buf.limit() < capacity) {
                ByteBuffer.allocate(capacity)
            } else {
                buf
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

//TODO: maybe this is a good way to avoid having to do the annoying '.toByte()' conversions?  verify it works
// correctly
//fun byteBufferOf(vararg elements: Any): ByteBuffer {
//    val bytes = elements.map { (it as Number).toByte() }.toByteArray()
//    return ByteBuffer.wrap(bytes)
//}
