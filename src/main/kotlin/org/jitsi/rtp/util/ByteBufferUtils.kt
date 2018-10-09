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

import java.nio.ByteBuffer

class ByteBufferUtils {
    companion object {
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
    }
}
