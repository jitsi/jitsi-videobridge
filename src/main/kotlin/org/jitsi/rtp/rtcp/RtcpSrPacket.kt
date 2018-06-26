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

import java.nio.ByteBuffer

/**
 * RTCP SenderInfo block
 * [buf] is a buffer which starts at the beginning
 * of a SenderInfo block
 */
class SenderInfo(buf: ByteBuffer) {
    companion object {
        /**
         * How far into an RTCP SR packet the SenderInfo
         * block starts.  Useful for code which is creating
         * a [SenderInfo] from an [RtcpSrPacket].
         */
        const val RTCP_OFFSET_BYTES = 12
    }
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
    val ntpTimestamp: Long = buf.getLong(0)

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
    val rtpTimestamp: Long = buf.getInt(8).toLong()
    /**
     * sender's packet count: 32 bits
     *     The total number of RTP data packets transmitted by the sender
     *     since starting transmission up until the time this SR packet was
     *     generated.  The count SHOULD be reset if the sender changes its
     *     SSRC identifier.
     */
    val sendersPacketCount: Long = buf.getInt(12).toLong()
    /**
     * sender's octet count: 32 bits
     *     The total number of payload octets (i.e., not including header or
     *     padding) transmitted in RTP data packets by the sender since
     *     starting transmission up until the time this SR packet was
     *     generated.  The count SHOULD be reset if the sender changes its
     *     SSRC identifier.  This field can be used to estimate the average
     *     payload data rate.
     */
    val sendersOctetCount: Long = buf.getInt(16).toLong()
}

/**
 * [buf] is a buffer which starts at the beginning of the RTCP header
 * https://tools.ietf.org/html/rfc3550#section-6.4.1
 */
//TODO: should we actually copy values from the buffer?  or should
// we read into the buffer on each access (unless they've been overridden)?
// is there a noticeable performance difference between these two schemes?
// (both in repeated copy scenarios and repeated access scenarios)
class RtcpSrPacket(buf: ByteBuffer) {
    val header = RtcpHeader.create(buf)
    val senderInfo = SenderInfo(buf)
    val reportBlocks = mutableListOf<ReportBlock>()

    init {
        repeat(header.reportCount) {
            reportBlocks.add(ReportBlock(buf))
        }
    }
}
