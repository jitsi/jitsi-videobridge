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

import org.jitsi.rtp.extensions.toHex
import java.nio.ByteBuffer

/**
 *  Checks if this [Short] matches the IDs used by [RtpTwoByteHeaderExtension].
 *  See https://tools.ietf.org/html/rfc5285#section-4.3
 */
fun Short.isTwoByteHeaderType(): Boolean
        = RtpTwoByteHeaderExtension.COOKIE.compareTo(this.toInt() and 0xfff0) == 0

// https://tools.ietf.org/html/rfc5285#section-4.1
// 0                   1                   2                   3
// 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |       0x10    |    0x00       |           length=3            |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |      ID       |     L=0       |     ID        |     L=1       |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |       data    |    0 (pad)    |       ID      |      L=4      |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                          data                                 |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// buf should point to the start of the ID field of this extension
class RtpTwoByteHeaderExtension(val buf: ByteBuffer) : RtpHeaderExtension() {
    override val id = buf.get().toInt()
    override val lengthBytes = buf.get().toInt()
    override val data: ByteBuffer = buf.slice().limit(lengthBytes) as ByteBuffer
    override val size: Int
        get() = RtpTwoByteHeaderExtension.HEADER_SIZE + lengthBytes

    companion object {
        const val HEADER_SIZE = 2
        const val COOKIE: Short = 0x1000

    }

    init {
        // We created a buffer view for the data, but now need to advance the buffer's
        // position by that amount
        buf.position(buf.position() + lengthBytes)
        // Consume any trailing padding
        consumePadding(buf)
    }

    override fun serializeToBuffer(buf: ByteBuffer) {
        buf.put(id.toByte())
        buf.put(lengthBytes.toByte())
        // Make a new view of the buffer and rewind it so we don't
        // affect any operation currently operating on data
        val rewoundData = data.duplicate()
        rewoundData.rewind()
        buf.put(rewoundData)
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            append("id: $id, ")
            append("length: $lengthBytes, ")
            append("data: ${data.toHex()}")
            toString()
        }
    }
}
