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

import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.put
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.util.BitBuffer
import unsigned.toUByte
import unsigned.toUInt
import java.nio.ByteBuffer

fun Short.isOneByteHeaderType(): Boolean
        = this.compareTo(RtpOneByteHeaderExtension.COOKIE) == 0

// https://tools.ietf.org/html/rfc5285#section-4.1
//  0                   1                   2                   3
//  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |       0xBE    |    0xDE       |           length=3            |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |  ID   | L=0   |     data      |  ID   |  L=1  |   data...     |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// ...data   |    0 (pad)    |    0 (pad)    |  ID   | L=3   |     |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
// |                          data                                 |
// +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
/**
 * Represents a single one-byte header extension (it's ID, length, and
 * data).
 * [buf]'s position should be at the start of the ID field for this
 * extension
 */
open class RtpOneByteHeaderExtension : RtpHeaderExtension {
    override val id: Int
    override val lengthBytes: Int
    override val data: ByteBuffer
    override val size: Int
        get() = RtpOneByteHeaderExtension.HEADER_SIZE + lengthBytes

    companion object {
        const val HEADER_SIZE = 1
        const val COOKIE: Short = 0xBEDE.toShort()

        fun getId(buf: ByteBuffer): Int = buf.get(0).getBits(0, 4).toUInt()
        fun setId(id: Int, buf: ByteBuffer) {
            buf.putBits(0, 0, id.toUByte(), 4)
        }

        /**
         * Gets the length of the data chunk of this extension, in bytes.  Note that this
         * does not return the literal value in the buffer, but the logical length of
         * the data chunk (which is the literal value + 1)
         */
        fun getLength(buf: ByteBuffer): Int = buf.get(0).getBits(4, 4).toUInt() + 1

        /**
         * Sets the length of the data chunk of this extension, in bytes.  The length given
         * should be the logical length; this method will translate it into the proper value
         * (logical length - 1)
         */
        fun setLength(length: Int, buf: ByteBuffer) {
            val lengthValue = length - 1
            buf.putBits(0, 4, lengthValue.toUByte(), 4)
        }

        /**
         * Return the data chunk wrapped in a new ByteBuffer (where position 0 will be
         * the start of the data chunk).  [buf] position 0 should be the start of the
         * entire extension chunk.
         */
        fun getData(buf: ByteBuffer, lengthBytes: Int): ByteBuffer = buf.subBuffer(1, lengthBytes)

        /**
         * Put the entirety of [dataBuf] into the data chunk position in [buf]
         */
        fun setData(dataBuf: ByteBuffer, buf: ByteBuffer) {
            buf.put(1, dataBuf)
        }
    }

    /**
     * Parse a one byte header extension starting at position 0
     * in [buf].  When finished, [buf]'s position will be advanced
     * past the parsed extension and any padding.
     */
    constructor(buf: ByteBuffer) : super() {
        id = getId(buf)
        lengthBytes = getLength(buf)
        data = getData(buf, lengthBytes)
    }

    /**
     * [lengthBytes] is the logical length of the data chunk (it will be
     * converted to the proper value to write in the extension itself.  See
     * [setLength])
     */
    constructor(
        id: Int,
        lengthBytes: Int,
        data: ByteBuffer
    ) {
        this.id = id
        this.lengthBytes = lengthBytes
        this.data = data
    }

    private fun getBuffer(): ByteBuffer {
        val buf = ByteBuffer.allocate(size)
        setId(id, buf)
        setLength(lengthBytes, buf)
        data.rewind()
        setData(data, buf)

        buf.rewind()
        return buf
    }

    //TODO: header extension classes should probably be made consistent with
    // the other types which all use a 'getBuffer' method
    override fun serializeToBuffer(buf: ByteBuffer) {
        buf.put(getBuffer())
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
