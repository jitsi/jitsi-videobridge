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

import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.getShortAsInt

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-2.2
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   | L=1   |transport-wide sequence number | zero padding  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class TccHeaderExtension {

    companion object {
        const val DATA_SIZE_BYTES = 2

        fun getSequenceNumber(ext: RtpPacket.HeaderExtension): Int =
            getSequenceNumber(ext.currExtBuffer, ext.currExtOffset)
        fun setSequenceNumber(ext: RtpPacket.HeaderExtension, tccSeqNum: Int) {
            setSequenceNumber(ext.currExtBuffer, ext.currExtOffset, tccSeqNum)
        }

        /**
         * [offset] into [buf] is the start of this entire extension (not the data section)
         */
        fun getSequenceNumber(buf: ByteArray, offset: Int): Int =
            buf.getShortAsInt(offset + RtpPacket.HEADER_EXT_HEADER_SIZE)
        fun setSequenceNumber(buf: ByteArray, offset: Int, seqNum: Int) {
            buf.putShort(offset + RtpPacket.HEADER_EXT_HEADER_SIZE, seqNum.toShort())
        }
    }
}
