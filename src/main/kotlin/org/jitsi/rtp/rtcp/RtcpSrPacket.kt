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
package org.jitsi.rtp.rtcp

import toUInt
import toUlong
import unsigned.toULong
import java.nio.ByteBuffer
import kotlin.properties.Delegates

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
class SenderInfo {
    private var buf: ByteBuffer
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
    var ntpTimestamp: Long
        get() = SenderInfo.getNtpTimestamp(buf)
        set(ntpTimestamp) = SenderInfo.setNtpTimestamp(buf, ntpTimestamp)

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
    var rtpTimestamp: Long
        get() = SenderInfo.getRtpTimestamp(buf)
        set(rtpTimestamp) = SenderInfo.setRtpTimestamp(buf, rtpTimestamp)
    /**
     * sender's packet count: 32 bits
     *     The total number of RTP data packets transmitted by the sender
     *     since starting transmission up until the time this SR packet was
     *     generated.  The count SHOULD be reset if the sender changes its
     *     SSRC identifier.
     */
    var sendersPacketCount: Long
        get() = SenderInfo.getSendersPacketCount(buf)
        set(sendersPacketCount) = SenderInfo.setSendersPacketCount(buf, sendersPacketCount)
    /**
     * sender's octet count: 32 bits
     *     The total number of payload octets (i.e., not including header or
     *     padding) transmitted in RTP data packets by the sender since
     *     starting transmission up until the time this SR packet was
     *     generated.  The count SHOULD be reset if the sender changes its
     *     SSRC identifier.  This field can be used to estimate the average
     *     payload data rate.
     */
    var sendersOctetCount: Long
        get() = SenderInfo.getSendersOctetCount(buf)
        set(sendersOctetCount) = SenderInfo.setSendersOctetCount(buf, sendersOctetCount)

    companion object {
        const val SIZE_BYTES = 20
        fun getNtpTimestamp(buf: ByteBuffer): Long = buf.getLong(0)
        fun setNtpTimestamp(buf: ByteBuffer, ntpTimestamp: Long) { buf.putLong(0, ntpTimestamp) }

        fun getRtpTimestamp(buf: ByteBuffer): Long = buf.getInt(8).toULong()
        fun setRtpTimestamp(buf: ByteBuffer, rtpTimestamp: Long) { buf.putInt(8, rtpTimestamp.toUInt()) }

        fun getSendersPacketCount(buf: ByteBuffer): Long = buf.getInt(12).toULong()
        fun setSendersPacketCount(buf: ByteBuffer, sendersPacketCount: Long) { buf.putInt(12, sendersPacketCount.toUInt()) }

        fun getSendersOctetCount(buf: ByteBuffer): Long = buf.getInt(16).toULong()
        fun setSendersOctetCount(buf: ByteBuffer, sendersOctetCount: Long) { buf.putInt(16, sendersOctetCount.toUInt()) }

        fun fromBuffer(buf: ByteBuffer): SenderInfo = SenderInfo(buf)
        fun fromValues(receiver: SenderInfo.() -> Unit): SenderInfo {
            return SenderInfo().apply(receiver)
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.duplicate()
    }

    constructor(
        ntpTimestamp: Long = 0,
        rtpTimestamp: Long = 0,
        sendersPacketCount: Long = 0,
        sendersOctetCount: Long = 0
    ) {
        this.buf = ByteBuffer.allocate(SenderInfo.SIZE_BYTES)
        this.ntpTimestamp = ntpTimestamp
        this.rtpTimestamp = rtpTimestamp
        this.sendersPacketCount = sendersPacketCount
        this.sendersOctetCount = sendersOctetCount
    }

    fun serializeToBuffer(buf: ByteBuffer) = buf.put(this.buf)
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
class RtcpSrPacket : RtcpPacket {
    override var buf: ByteBuffer
    override var header: RtcpHeader
        get() = RtcpHeader(buf)
        set(header) {
            header.serializeToBuffer(this.buf)
        }
    var senderInfo: SenderInfo
        get() = SenderInfo(buf.duplicate().position(8) as ByteBuffer)
        set(senderInfo) {
            senderInfo.serializeToBuffer(buf.duplicate().position(8) as ByteBuffer)
        }
    var reportBlocks: List<RtcpReportBlock> = listOf()
    override var size: Int = 0
        get() = RtcpHeader.SIZE_BYTES + SenderInfo.SIZE_BYTES + reportBlocks.size * RtcpReportBlock.SIZE_BYTES

    companion object Create {
        fun fromBuffer(header: RtcpHeader, buf: ByteBuffer): RtcpSrPacket {
            return RtcpSrPacket().apply {
                this.buf = buf.slice()
                this.header = header
                senderInfo = SenderInfo.fromBuffer(buf)
                reportBlocks = (0 until header.reportCount).map {
                    RtcpReportBlock.fromBuffer(buf)
                }
            }
        }
        fun fromValues(receiver: RtcpSrPacket.() -> Unit): RtcpSrPacket {
            val packet = RtcpSrPacket()
            packet.receiver()
            return packet
        }
    }

    constructor(buf: ByteBuffer) : super() {
        this.buf = buf
    }

    constructor(
        header: RtcpHeader = RtcpHeader()
        //, senderInfo = ... etc
    ) {
        this.buf = ByteBuffer.allocate(100)//TODO: size
        this.header = header
    }

    override fun serializeToBuffer(buf: ByteBuffer) {
        header.serializeToBuffer(buf)
        buf.apply {
            senderInfo.serializeToBuffer(buf)
            reportBlocks.forEach { it.serializeToBuffer(buf) }
        }
    }
}
