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

package org.jitsi.rtp.rtcp.rtcpfb

import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.rtcpfb.fci.Fir
import java.nio.ByteBuffer

/**
 *
 * https://tools.ietf.org/html/rfc4585#section-6.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|   FMT   |       PT      |          length               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of packet sender                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of media source                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                              SSRC                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Seq nr.       |    Reserved                                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                              ....                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * https://tools.ietf.org/html/rfc5104#section-4.3.1.1
 *
 * The SSRC field in the FIR FCI block is used to set the media sender
 * SSRC, the media source SSRC field in the RTCPFB header is unused for FIR packets.
 * Within the common packet header for feedback messages (as defined in
 * section 6.1 of [RFC4585]), the "SSRC of packet sender" field
 * indicates the source of the request, and the "SSRC of media source"
 * is not used and SHALL be set to 0.  The SSRCs of the media senders to
 * which the FIR command applies are in the corresponding FCI entries.
 * A FIR message MAY contain requests to multiple media senders, using
 * one FCI entry per target media sender.
 */
//TODO: support multiple FIR FCI blocks (could cause parsing issues!)
class RtcpFbFirPacket(
    header: RtcpHeader = RtcpHeader(),
    private val fci: Fir = Fir(),
    backingBuffer: ByteBuffer? = null
) : PayloadSpecificFbPacket(header.apply { reportCount = FMT }, 0, fci, backingBuffer) {

    override fun clone(): RtcpFbFirPacket = RtcpFbFirPacket(header.clone(), fci.clone())

    val seqNum: Int get() = fci.seqNum
    val firSsrc: Long get() = fci.ssrc

    companion object {
        const val FMT = 4
        const val SIZE_BYTES = FIXED_HEADER_SIZE + 8

        fun fromValues(
            header: RtcpHeader = RtcpHeader(),
            firSsrc: Long = -1,
            commandSeqNum: Int = -1
        ): RtcpFbFirPacket {
            val fci = Fir(firSsrc, commandSeqNum)
            return RtcpFbFirPacket(header, fci)
        }

        fun fromBuffer(buf: ByteBuffer): RtcpFbFirPacket {
            val header = RtcpHeader.fromBuffer(buf)
            // Unused, but we need to advance the buffer
            @Suppress("UNUSED_VARIABLE") val mediaSourceSsrc = getMediaSourceSsrc(buf)
            val fci = Fir.fromBuffer(buf)

            return RtcpFbFirPacket(header, fci, buf)
        }
    }
}