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
package org.jitsi.rtp.rtp.header_extensions

import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.put
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.util.ByteBufferUtils
import unsigned.toUByte
import unsigned.toUInt
import java.nio.ByteBuffer

//TODO: handle one-byte header type 15:
//The local identifier value 15 is reserved for future extension and
//   MUST NOT be used as an identifier.  If the ID value 15 is
//   encountered, its length field should be ignored, processing of the
//   entire extension should terminate at that point, and only the
//   extension elements present prior to the element with ID 15
//   considered.


/**
 * Represents a single one-byte header extension (its ID, length, and
 * data)
 * https://tools.ietf.org/html/rfc5285#section-4.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |       0xBE    |    0xDE       |           length=3            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   | L=0   |     data      |  ID   |  L=1  |   data...     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * ...data   |    0 (pad)    |    0 (pad)    |  ID   | L=3   |     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                          data                                 |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
open class RtpOneByteHeaderExtension(
    final override val id: Int = -1,
    data: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER
) : RtpHeaderExtension() {
    final override val sizeBytes: Int
        get() = HEADER_SIZE + data.limit()

    //TODO: do this for all exposed ByteBuffer members?
    private val _data: ByteBuffer = data.rewind() as ByteBuffer
    final override val data: ByteBuffer
        get() = _data.duplicate()

    override fun serializeTo(buf: ByteBuffer) {
        val absBuf = buf.subBuffer(buf.position())
        setId(absBuf, id)
        setLength(absBuf, data.limit())
        data.rewind()
        setData(absBuf, data)
        data.rewind()
        buf.incrementPosition(sizeBytes)
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            append("id: $id, ")
            append("lengthBytes: ${data.limit()}, ")
            append("data: ${data.toHex()}")
            toString()
        }
    }

    companion object {
        const val HEADER_SIZE = 1
        const val COOKIE: Short = 0xBEDE.toShort()

        fun getId(buf: ByteBuffer): Int = buf.get(0).getBits(0, 4).toUInt()
        fun setId(buf: ByteBuffer, id: Int): Unit = buf.putBits(0, 0, id.toUByte(), 4)

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
        fun setLength(buf: ByteBuffer, length: Int) {
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
        fun setData(buf: ByteBuffer, dataBuf: ByteBuffer) {
            buf.put(1, dataBuf)
        }

        /**
         * Parse a one byte header extension starting at position 0
         * in [buf].  When finished, [buf]'s position will be advanced
         * past the parsed extension, but not past any padding
         */
        fun fromBuffer(buf: ByteBuffer): RtpOneByteHeaderExtension {
            val id = getId(buf)
            val lengthBytes = getLength(buf)
            val data = getData(buf, lengthBytes)
            val extension = RtpOneByteHeaderExtension(id, data)
            // Advance the buffer's position to the end of the data for this extension
            buf.incrementPosition(extension.sizeBytes)

            return extension
        }
    }
}
