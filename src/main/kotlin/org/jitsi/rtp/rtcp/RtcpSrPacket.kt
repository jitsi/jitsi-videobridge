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

import org.jitsi.rtp.extensions.bytearray.cloneFromPool
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.extensions.bytearray.putInt
import org.jitsi.rtp.util.getIntAsLong

/**
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * sender |              NTP timestamp, most significant word             |
 * info   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |             NTP timestamp, least significant word             |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                         RTP timestamp                         |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                     sender's packet count                     |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                      sender's octet count                     |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *
 * RTCP SenderInfo block
 */
class SenderInfoParser {
    companion object {
        const val SIZE_BYTES = 20
        const val NTP_TS_MSW_OFFSET = 0
        const val NTP_TS_LSW_OFFSET = 4
        const val RTP_TS_OFFSET = 8
        const val SENDERS_PACKET_COUNT_OFFSET = 12
        const val SENDERS_OCTET_COUNT_OFFSET = 16

        fun getNtpTimestampMsw(buf: ByteArray, baseOffset: Int): Long =
            buf.getIntAsLong(baseOffset + NTP_TS_MSW_OFFSET)
        fun setNtpTimestampMsw(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + NTP_TS_MSW_OFFSET, value.toInt())

        fun getNtpTimestampLsw(buf: ByteArray, baseOffset: Int): Long =
            buf.getIntAsLong(baseOffset + NTP_TS_LSW_OFFSET)
        fun setNtpTimestampLsw(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + NTP_TS_LSW_OFFSET, value.toInt())

        fun getRtpTimestamp(buf: ByteArray, baseOffset: Int): Long =
            buf.getIntAsLong(baseOffset + RTP_TS_OFFSET)
        fun setRtpTimestamp(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + RTP_TS_OFFSET, value.toInt())

        fun getSendersPacketCount(buf: ByteArray, baseOffset: Int): Long =
            buf.getIntAsLong(baseOffset + SENDERS_PACKET_COUNT_OFFSET)
        fun setSendersPacketCount(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + SENDERS_PACKET_COUNT_OFFSET, value.toInt())

        fun getSendersOctetCount(buf: ByteArray, baseOffset: Int): Long =
            buf.getIntAsLong(baseOffset + SENDERS_OCTET_COUNT_OFFSET)
        fun setSendersOctetCount(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + SENDERS_OCTET_COUNT_OFFSET, value.toInt())
    }
}

data class SenderInfoBuilder(
    var ntpTimestampMsw: Long = -1,
    var ntpTimestampLsw: Long = -1,
    var rtpTimestamp: Long = -1,
    var sendersPacketCount: Long = -1,
    var sendersOctetCount: Long = -1
) {

    fun writeTo(buf: ByteArray, offset: Int) {
        SenderInfoParser.setNtpTimestampMsw(buf, offset, ntpTimestampMsw)
        SenderInfoParser.setNtpTimestampLsw(buf, offset, ntpTimestampLsw)
        SenderInfoParser.setRtpTimestamp(buf, offset, rtpTimestamp)
        SenderInfoParser.setSendersPacketCount(buf, offset, sendersPacketCount)
        SenderInfoParser.setSendersOctetCount(buf, offset, sendersOctetCount)
    }
}

/**
 *        0                   1                   2                   3
 *        0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * header |V=2|P|    RC   |   PT=SR=200   |             length            |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                         SSRC of sender                        |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * sender |              NTP timestamp, most significant word             |
 * info   +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |             NTP timestamp, least significant word             |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                         RTP timestamp                         |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                     sender's packet count                     |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                      sender's octet count                     |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * report |                 SSRC_1 (SSRC of first source)                 |
 * block  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * 1      | fraction lost |       cumulative number of packets lost       |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |           extended highest sequence number received           |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                      interarrival jitter                      |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                         last SR (LSR)                         |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                   delay since last SR (DLSR)                  |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * report |                 SSRC_2 (SSRC of second source)                |
 * block  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * 2      :                               ...                             :
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *        |                  profile-specific extensions                  |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * https://tools.ietf.org/html/rfc3550#section-6.4.1
 */
class RtcpSrPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : RtcpPacket(buffer, offset, length) {

    val senderInfo: SenderInfo by lazy { SenderInfo() }

    val reportBlocks: List<RtcpReportBlock> by lazy {
        (0 until reportCount).map {
            RtcpReportBlock.fromBuffer(buffer, offset + REPORT_BLOCKS_OFFSET + it * RtcpReportBlock.SIZE_BYTES)
        }.toList()
    }

    override fun clone(): RtcpSrPacket = RtcpSrPacket(cloneBuffer(), offset, length)

    // SenderInfo is defined differently so that we can scope these variables under a the 'senderInfo'
    // member here.  Is there a better way?
    inner class SenderInfo {
        val ntpTimestampMsw: Long = SenderInfoParser.getNtpTimestampMsw(buffer, offset + SENDER_INFO_OFFSET)
        val ntpTimestampLsw: Long = SenderInfoParser.getNtpTimestampLsw(buffer, offset + SENDER_INFO_OFFSET)
        val rtpTimestamp: Long = SenderInfoParser.getRtpTimestamp(buffer, offset + SENDER_INFO_OFFSET)
        val sendersPacketCount: Long = SenderInfoParser.getSendersPacketCount(buffer, offset + SENDER_INFO_OFFSET)
        val sendersOctetCount: Long = SenderInfoParser.getSendersOctetCount(buffer, offset + SENDER_INFO_OFFSET)

        /**
         * https://tools.ietf.org/html/rfc3550#section-4
         * In some fields where a more compact representation is
         * appropriate, only the middle 32 bits are used; that is, the low 16
         * bits of the integer part and the high 16 bits of the fractional part.
         * The high 16 bits of the integer part must be determined
         * independently.
         */
        val compactedNtpTimestamp: Long by lazy {
            (((ntpTimestampMsw and 0xFFFF) shl 16) or (ntpTimestampLsw ushr 16)).toPositiveLong()
        }
    }

    companion object {
        const val PT = 200
        const val SENDER_INFO_OFFSET = RtcpHeader.SIZE_BYTES
        const val REPORT_BLOCKS_OFFSET = SENDER_INFO_OFFSET + SenderInfoParser.SIZE_BYTES
    }
}

data class RtcpSrPacketBuilder(
    var rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    var senderInfo: SenderInfoBuilder = SenderInfoBuilder(),
    val reportBlocks: MutableList<RtcpReportBlock> = mutableListOf()
) {

    private val sizeBytes: Int
        get() = RtcpHeader.SIZE_BYTES + SenderInfoParser.SIZE_BYTES + reportBlocks.size * RtcpReportBlock.SIZE_BYTES

    fun build(): RtcpSrPacket {
        val buf = BufferPool.getArray(sizeBytes)
        writeTo(buf, 0)
        return RtcpSrPacket(buf, 0, sizeBytes)
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        rtcpHeader.apply {
            packetType = RtcpSrPacket.PT
            reportCount = reportBlocks.size
            length = RtpUtils.calculateRtcpLengthFieldValue(sizeBytes)
        }.writeTo(buf, offset)
        senderInfo.writeTo(buf, offset + RtcpSrPacket.SENDER_INFO_OFFSET)
        reportBlocks.forEachIndexed { index, reportBlock ->
            reportBlock.writeTo(buf, offset + RtcpSrPacket.REPORT_BLOCKS_OFFSET + index * RtcpReportBlock.SIZE_BYTES)
        }
    }
}
