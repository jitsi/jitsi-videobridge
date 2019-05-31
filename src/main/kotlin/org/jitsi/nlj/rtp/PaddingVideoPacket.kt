/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.rtp

import org.jitsi.nlj.util.BufferPool
import org.jitsi.rtp.rtp.RtpHeader

class PaddingVideoPacket private constructor(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : VideoRtpPacket(buffer, offset, length) {

    override fun clone(): PaddingVideoPacket =
        throw NotImplementedError("clone() not supported for padding packets.")

    companion object {
        /**
         * Creating a PaddingVideoPacket by directly grabbing a buffer in its
         * ctor is problematic because we cannot clear the buffer we retrieve
         * before calling the parent class' constructor.  Because the buffer
         * may contain invalid data, any attempts to parse it by parent class(es)
         * could fail, so we use a helper here instead
         */
        fun create(length: Int): PaddingVideoPacket {
            val buf = BufferPool.getBuffer(length)
            // It's possible we the buffer we pulled from the pool already has
            // data in it, and we won't be overwriting it with anything so clear
            // out the data
            buf.fill(0, 0, length)

            return PaddingVideoPacket(buf, 0, length).apply {
                // Recalculate the header length now that we've zero'd everything out
                // and set the fields
                version = RtpHeader.VERSION
                headerLength = RtpHeader.getTotalLength(buffer, offset)
                paddingSize = payloadLength
            }
        }
    }
}