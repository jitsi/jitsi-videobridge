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
import org.jitsi.rtp.util.BitBuffer
import java.nio.ByteBuffer

// https://tools.ietf.org/html/rfc5285#section-4.1
// 0                   1                   2                   3
// 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |       0xBE    |    0xDE       |           length=3            |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |  ID   | L=0   |     data      |  ID   |  L=1  |   data...
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// ...data   |    0 (pad)    |    0 (pad)    |  ID   | L=3   |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                          data                                 |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
/**
 * Represents a single one-byte header extension (it's ID, length, and
 * data).
 * [buf]'s position should be at the start of the ID field for this
 * extension
 */
class RtpOneByteHeaderExtension(val buf: ByteBuffer) : RtpHeaderExtension() {
    private val bitBuffer = BitBuffer(buf)
    override val id = bitBuffer.getBits(4).toInt()
    override val lengthBytes = bitBuffer.getBits(4).toInt() + 1
    //TODO: readonly?
    override val data: ByteBuffer = buf.slice().limit(lengthBytes) as ByteBuffer

    companion object {
        const val COOKIE: Short = 0xBEDE.toShort()
    }

    init {
        // We created a buffer view for the data, but now need to advance the buffer's
        // position by that amount
        buf.position(buf.position() + lengthBytes)
        // Consume any trailing padding
        consumePadding(buf)
    }

    override fun serializeToBuffer(buf: ByteBuffer) {
        with(BitBuffer(buf)) {
            putBits(id.toByte(), 4)
            putBits((lengthBytes - 1).toByte(), 4)
            // Make a new view of the buffer and rewind it so we don't
            // affect any operation currently operating on data
            val rewoundData = data.duplicate()
            rewoundData.rewind()
            buf.put(rewoundData)
        }
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
