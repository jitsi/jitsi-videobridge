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

import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.rtcpfb.fci.tcc.Tcc
import java.nio.ByteBuffer

/**
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|  FMT=15 |    PT=205     |           length              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     SSRC of packet sender                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      SSRC of media source                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |      base sequence number     |      packet status count      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                 reference time                | fb pkt. count |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          packet chunk         |         packet chunk          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         packet chunk          |  recv delta   |  recv delta   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           recv delta          |  recv delta   | zero padding  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class RtcpFbTccPacket(
    header: RtcpHeader = RtcpHeader(),
    mediaSourceSsrc: Long = -1,
    private val fci: Tcc = Tcc(),
    backingBuffer: ByteBuffer? = null
) : TransportLayerFbPacket(header.apply { reportCount = FMT }, mediaSourceSsrc, fci, backingBuffer) {

    val numPackets: Int get() = fci.numPackets

    fun addPacket(seqNum: Int, timestamp: Long) {
        fci.addPacket(seqNum, timestamp)
        payloadModified()
    }

    val referenceTimeMs: Long get() = fci.referenceTimeMs

    /**
     * Iterate over the pairs of (sequence number, timestamp) represented by this TCC packet
     */
    fun forEach(action: (Int, Long) -> Unit) = fci.forEach(action)

    override fun clone(): RtcpFbTccPacket {
        return RtcpFbTccPacket(header.clone(), mediaSourceSsrc, fci.clone())
    }

    companion object {
        const val FMT = 15

        fun fromBuffer(buf: ByteBuffer): RtcpFbTccPacket {
            val header = RtcpHeader.fromBuffer(buf)
            val mediaSourceSsrc = getMediaSourceSsrc(buf)
            // TCC FCI's parse wants the buffer to start at the beginning of
            // the FCI block, so create a wrapper buffer for it to make
            // that the case
            val fciBuf = buf.subBuffer(buf.position())
            val fci = Tcc.fromBuffer(fciBuf)
            // Increment buf's position by however much was parsed from fciBu
            buf.incrementPosition(fciBuf.position())
            //NOTE(brian): in my pcap replays I'm seeing instances
            // of TCC packets with padding without the padding bit
            // being set in the header
//            if (header.hasPadding) {
                consumePadding(buf)
//            }

            return RtcpFbTccPacket(header, mediaSourceSsrc, fci, buf)
        }
    }
}