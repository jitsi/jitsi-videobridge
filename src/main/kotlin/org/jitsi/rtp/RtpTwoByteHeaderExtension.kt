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

import org.jitsi.rtp.extensions.put
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.util.ByteBufferUtils
import unsigned.toUByte
import unsigned.toUInt
import java.nio.ByteBuffer

/**
 * Represents a single two-byte header extension (its ID, length and data)
 * https://tools.ietf.org/html/rfc5285#section-4.1
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |       0x10    |    0x00       |           length=3            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |      ID       |     L=0       |     ID        |     L=1       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |       data    |    0 (pad)    |       ID      |      L=4      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                          data                                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class RtpTwoByteHeaderExtension : RtpHeaderExtension {
    override val id: Int
    override val lengthBytes: Int
    override val data: ByteBuffer
    override val size: Int
        get() = RtpTwoByteHeaderExtension.HEADER_SIZE + lengthBytes

    companion object {
        const val HEADER_SIZE = 2
        const val COOKIE: Short = 0x1000

        fun getId(buf: ByteBuffer): Int = buf.get(0).toUInt()
        fun setId(buf: ByteBuffer, id: Int) {
            buf.put(0, id.toUByte())
        }

        fun getLength(buf: ByteBuffer): Int = buf.get(1).toUInt()
        fun setLength(buf: ByteBuffer, length: Int) {
            buf.put(1, length.toUByte())
        }

        fun getData(buf: ByteBuffer, lengthBytes: Int) = buf.subBuffer(2, lengthBytes)
        fun setData(buf: ByteBuffer, dataBuf: ByteBuffer) {
            buf.put(2, dataBuf)
        }
    }

    /**
     * When parsing a buffer, after the constructor is finished the buffer's
     * position will be past this extension, but not past any padding
     */
    constructor(buf: ByteBuffer) {
        id = getId(buf)
        lengthBytes = getLength(buf)
        data = getData(buf, lengthBytes)
        // Advance the buffer's position to the end of the data for this extension
        buf.position(buf.position() + size)
    }

    constructor(
        id: Int,
        lengthBytes: Int,
        data: ByteBuffer
    ) {
        this.id = id
        this.lengthBytes = lengthBytes
        this.data = data
    }

    override fun getBuffer(): ByteBuffer {
        val buf = ByteBufferUtils.ensureCapacity(null, size)
        buf.rewind()
        buf.limit(size)

        setId(buf, id)
        setLength(buf, lengthBytes)
        data.rewind()
        setData(buf, data)

        buf.rewind()
        return buf

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
