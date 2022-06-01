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

package org.jitsi.rtp.rtcp

import org.jitsi.rtp.extensions.bytearray.getBitAsBool
import org.jitsi.rtp.extensions.bytearray.putBitAsBoolean
import org.jitsi.rtp.extensions.bytearray.putInt
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.util.getBitsAsInt
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.rtp.util.getIntAsLong
import org.jitsi.rtp.util.getShortAsInt
import org.jitsi.rtp.util.putNumberAsBits

/**
 * Models the RTCP header as defined in https://tools.ietf.org/html/rfc3550#section-6.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|    RC   |   PT=SR=200   |             length            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         SSRC of sender                        |
 * +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *
 * length: 16 bits
 *   The length of this RTCP packet in 32-bit words minus one,
 *   including the header and any padding.  (The offset of one makes
 *   zero a valid length and avoids a possible infinite loop in
 *   scanning a compound RTCP packet, while counting 32-bit words
 *   avoids a validity check for a multiple of 4.)
 *
 * @author Brian Baldino
 *
 * TODO: Use the same utility function in RtpHeader and RtcpHeader
 */
class RtcpHeader {
    companion object {
        const val SIZE_BYTES = 8
        const val VERSION_OFFSET = 0
        const val PADDING_OFFSET = 0
        const val REPORT_COUNT_OFFSET = 0
        const val PACKET_TYPE_OFFSET = 1
        const val LENGTH_OFFSET = 2
        const val SENDER_SSRC_OFFSET = 4
        fun getVersion(buf: ByteArray, headerStartOffset: Int): Int =
            buf.getBitsAsInt(headerStartOffset + VERSION_OFFSET, 0, 2)
        fun setVersion(buf: ByteArray, headerStartOffset: Int, version: Int) =
            buf.putNumberAsBits(headerStartOffset + VERSION_OFFSET, 0, 2, version)

        fun hasPadding(buf: ByteArray, headerStartOffset: Int): Boolean =
            buf.getBitAsBool(headerStartOffset + PADDING_OFFSET, 2)
        fun setPadding(buf: ByteArray, headerStartOffset: Int, hasPadding: Boolean) =
            buf.putBitAsBoolean(headerStartOffset + PADDING_OFFSET, 2, hasPadding)

        fun getReportCount(buf: ByteArray, headerStartOffset: Int): Int =
            buf.getBitsAsInt(headerStartOffset + REPORT_COUNT_OFFSET, 3, 5)
        fun setReportCount(buf: ByteArray, headerStartOffset: Int, reportCount: Int) =
            buf.putNumberAsBits(headerStartOffset + REPORT_COUNT_OFFSET, 3, 5, reportCount)

        fun getPacketType(buf: ByteArray, headerStartOffset: Int): Int =
            buf.getByteAsInt(headerStartOffset + PACKET_TYPE_OFFSET)
        fun setPacketType(buf: ByteArray, headerStartOffset: Int, packetType: Int) =
            buf.set(headerStartOffset + PACKET_TYPE_OFFSET, packetType.toByte())

        // TODO document (bytes or words?)
        fun getLength(buf: ByteArray, headerStartOffset: Int): Int =
            buf.getShortAsInt(headerStartOffset + LENGTH_OFFSET)
        fun setLength(buf: ByteArray, headerStartOffset: Int, length: Int) =
            buf.putShort(headerStartOffset + LENGTH_OFFSET, length.toShort())

        fun getSenderSsrc(buf: ByteArray, headerStartOffset: Int): Long =
            buf.getIntAsLong(headerStartOffset + SENDER_SSRC_OFFSET)
        fun setSenderSsrc(buf: ByteArray, headerStartOffset: Int, senderSsrc: Long) =
            buf.putInt(headerStartOffset + SENDER_SSRC_OFFSET, senderSsrc.toInt())
    }
}

data class RtcpHeaderBuilder(
    var version: Int = 2,
    var hasPadding: Boolean = false,
    var reportCount: Int = -1,
    var packetType: Int = -1,
    var length: Int = -1,
    var senderSsrc: Long = 0
) {

    fun build(): ByteArray {
        val buf = ByteArray(RtcpHeader.SIZE_BYTES)
        writeTo(buf, 0)
        return buf
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        RtcpHeader.setVersion(buf, offset, version)
        RtcpHeader.setPadding(buf, offset, hasPadding)
        RtcpHeader.setReportCount(buf, offset, reportCount)
        RtcpHeader.setPacketType(buf, offset, packetType)
        RtcpHeader.setLength(buf, offset, length)
        RtcpHeader.setSenderSsrc(buf, offset, senderSsrc)
    }
}
