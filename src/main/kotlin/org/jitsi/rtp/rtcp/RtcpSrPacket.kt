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

import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.Packet
import org.jitsi.rtp.Serializable
import java.nio.ByteBuffer
import java.util.Objects

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
class SenderInfo(
    /**
     * NTP timestamp: 64 bits
     *     Indicates the wallclock time (see Section 4) when this report was
     *     sent so that it may be used in combination with timestamps
     *     returned in reception reports from other receivers to measure
     *     round-trip propagation to those receivers.  Receivers should
     *     expect that the measurement accuracy of the timestamp may be
     *     limited to far less than the resolution of the NTP timestamp.  The
     *     measurement uncertainty of the timestamp is not indicated as it
     *     may not be known.  On a system that has no notion of wallclock
     *     time but does have some system-specific clock such as "system
     *     uptime", a sender MAY use that clock as a reference to calculate
     *     relative NTP timestamps.  It is important to choose a commonly
     *     used clock so that if separate implementations are used to produce
     *     the individual streams of a multimedia session, all
     *     implementations will use the same clock.  Until the year 2036,
     *     relative and absolute timestamps will differ in the high bit so
     *     (invalid) comparisons will show a large difference; by then one
     *     hopes relative timestamps will no longer be needed.  A sender that
     *     has no notion of wallclock or elapsed time MAY set the NTP
     *     timestamp to zero.
     */
    val ntpTimestamp: Long = -1,
    /**
     * RTP timestamp: 32 bits
     *     Corresponds to the same time as the NTP timestamp (above), but in
     *     the same units and with the same random offset as the RTP
     *     timestamps in data packets.  This correspondence may be used for
     *     intra- and inter-media synchronization for sources whose NTP
     *     timestamps are synchronized, and may be used by media-independent
     *     receivers to estimate the nominal RTP clock frequency.  Note that
     *     in most cases this timestamp will not be equal to the RTP
     *     timestamp in any adjacent data packet.  Rather, it MUST be
     *     calculated from the corresponding NTP timestamp using the
     *     relationship between the RTP timestamp counter and real time as
     *     maintained by periodically checking the wallclock time at a
     *     sampling instant.
     */
    val rtpTimestamp: Long = -1,
    /**
     * sender's packet count: 32 bits
     *     The total number of RTP data packets transmitted by the sender
     *     since starting transmission up until the time this SR packet was
     *     generated.  The count SHOULD be reset if the sender changes its
     *     SSRC identifier.
     */
    val sendersPacketCount: Long = -1,
    /**
     * sender's octet count: 32 bits
     *     The total number of payload octets (i.e., not including header or
     *     padding) transmitted in RTP data packets by the sender since
     *     starting transmission up until the time this SR packet was
     *     generated.  The count SHOULD be reset if the sender changes its
     *     SSRC identifier.  This field can be used to estimate the average
     *     payload data rate.
     */
    val sendersOctetCount: Long = -1
) : Serializable(), Cloneable {
    override val sizeBytes: Int = SIZE_BYTES

    /**
     * https://tools.ietf.org/html/rfc3550#section-4
     * In some fields where a more compact representation is
     * appropriate, only the middle 32 bits are used; that is, the low 16
     * bits of the integer part and the high 16 bits of the fractional part.
     * The high 16 bits of the integer part must be determined
     * independently.
     */
    val compactedNtpTimestamp: Long
        get() = ntpTimestamp.and(0x0000FFFFFFFF0000).shr(16)

    override fun serializeTo(buf: ByteBuffer) {
        buf.putLong(ntpTimestamp)
        buf.putInt(rtpTimestamp.toInt())
        buf.putInt(sendersPacketCount.toInt())
        buf.putInt(sendersOctetCount.toInt())
    }

    public override fun clone(): SenderInfo =
        SenderInfo(ntpTimestamp, rtpTimestamp, sendersPacketCount, sendersOctetCount)

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("ntpTimestamp: $ntpTimestamp")
            appendln("rtpTimestamp: $rtpTimestamp")
            appendln("sendersPacketCount: $sendersPacketCount")
            appendln("sendersOctetCount: $sendersOctetCount")

            toString()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other?.javaClass != javaClass) {
            return false
        }
        other as SenderInfo
        return (ntpTimestamp == other.ntpTimestamp &&
                compactedNtpTimestamp == other.compactedNtpTimestamp &&
                rtpTimestamp == other.rtpTimestamp &&
                sendersPacketCount == other.sendersPacketCount &&
                sendersOctetCount == other.sendersOctetCount)
    }

    override fun hashCode(): Int {
        return Objects.hash(ntpTimestamp, compactedNtpTimestamp, rtpTimestamp, sendersPacketCount, sendersOctetCount)
    }

    companion object {
        const val SIZE_BYTES = 20
        fun fromBuffer(buf: ByteBuffer): SenderInfo {
            val ntpTimestamp = buf.getLong()
            val rtpTimestamp = buf.getInt().toPositiveLong()
            val sendersPacketCount = buf.getInt().toPositiveLong()
            val sendersOctetCount = buf.getInt().toPositiveLong()

            return SenderInfo(ntpTimestamp, rtpTimestamp, sendersPacketCount, sendersOctetCount)
        }
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
    header: RtcpHeader = RtcpHeader(),
    val senderInfo: SenderInfo = SenderInfo(),
    val reportBlocks: List<RtcpReportBlock> = listOf(),
    backingBuffer: ByteBuffer? = null
) : RtcpPacket(header.apply { packetType = PT; reportCount = reportBlocks.size }, backingBuffer) {
    override val sizeBytes: Int
        get() = header.sizeBytes + senderInfo.sizeBytes + (reportBlocks.size * RtcpReportBlock.SIZE_BYTES)

    override fun serializeTo(buf: ByteBuffer) {
        super.serializeTo(buf)
        senderInfo.serializeTo(buf)
        reportBlocks.forEach { it.serializeTo(buf) }
    }

    override fun clone(): Packet {
        val clonedReportBlocks = reportBlocks
                .map(RtcpReportBlock::clone)
                .toList()
        return RtcpSrPacket(header.clone(), senderInfo.clone(), clonedReportBlocks)
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("SR Packet")
            appendln(super.toString())
            appendln(senderInfo.toString())
            reportBlocks.forEach {
                appendln(it.toString())
            }
            toString()
        }
    }

    companion object {
        const val PT: Int = 200

        fun fromBuffer(buf: ByteBuffer): RtcpSrPacket {
            val header = RtcpHeader.fromBuffer(buf)
            val senderInfo = SenderInfo.fromBuffer(buf)
            val reportBlocks = (0 until header.reportCount)
                    .map { RtcpReportBlock.fromBuffer(buf) }
                    .toMutableList()
            return RtcpSrPacket(header, senderInfo, reportBlocks, buf)
        }
    }
}
