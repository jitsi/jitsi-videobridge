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

import org.jitsi.rtp.NewRawPacket
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.getShortAsInt
import java.nio.ByteBuffer

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-2.2
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   | L=1   |transport-wide sequence number | zero padding  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class TccHeaderExtension(
    id: Int = -1,
    val tccSeqNum: Int = -1
) : RtpHeaderExtension(id) {
    override val dataSizeBytes: Int = 2

    override fun serializeData(buf: ByteBuffer) {
        buf.putShort(tccSeqNum.toShort())
    }

    companion object {
        fun fromUnparsed(unparsedHeaderExtension: UnparsedHeaderExtension): TccHeaderExtension {
            val tccSeqNum = unparsedHeaderExtension.data.getShort().toPositiveInt()
            return TccHeaderExtension(unparsedHeaderExtension.id, tccSeqNum)
        }

        fun getSequenceNumber(ext: NewRawPacket.HeaderExtension): Int =
            getSequenceNumber(ext.buffer, ext.offset, HeaderExtensionType.ONE_BYTE_HEADER_EXT)

        /**
         * [offset] into [buf] is the start of this entire extension (not the data section)
         */
        fun getSequenceNumber(buf: ByteArray, offset: Int, extType: HeaderExtensionType): Int =
            buf.getShortAsInt(offset + extType.headerSizeBytes)
        fun setSequenceNumber(buf: ByteArray, offset: Int, seqNum: Int, extType: HeaderExtensionType) {
            buf.putShort(offset + extType.headerSizeBytes, seqNum.toShort())
        }
    }
}
