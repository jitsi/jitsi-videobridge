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

import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.rtcpfb.fci.GenericNack
import org.jitsi.rtp.rtcp.rtcpfb.fci.GenericNackFci
import java.nio.ByteBuffer
import java.util.SortedSet

/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|   FMT   |       PT      |          length               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of packet sender                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of media source                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 */
class RtcpFbNackPacket(
    header: RtcpHeader = RtcpHeader(),
    mediaSourceSsrc: Long = -1,
    private val fci: GenericNackFci = GenericNackFci(),
    backingBuffer: ByteBuffer? = null
) : TransportLayerFbPacket(header.apply { reportCount = FMT }, mediaSourceSsrc, fci, backingBuffer) {

    val missingSeqNums: List<Int> get() = fci.missingSeqNums

    override fun clone(): RtcpFbNackPacket {
        return RtcpFbNackPacket(header.clone(), mediaSourceSsrc, fci.clone())
    }

    companion object {
        const val FMT = 1
        const val SIZE_BYTES = FIXED_HEADER_SIZE + 4
        fun fromBuffer(buf: ByteBuffer): RtcpFbNackPacket {
            val bufStartPosition = buf.position()
            val header = RtcpHeader.fromBuffer(buf)
            // The amount of NACK blocks contained in the packet is just determined
            // by the size in the header, and GenericNackFci.fromBuffer will keep
            // parsing until the buffer has no remaining data.  Since this might
            // be part of a compound packet, create a buffer which contains only
            // this packet's data so that we can parse it entirely.
            // TODO: should we just set this as a general requirement when
            // parsing RTCP packets and have the iterator take care of the
            // sub-buffer?
            val packetBuf = buf.subBuffer(bufStartPosition, header.lengthBytes)
            packetBuf.position(header.sizeBytes)
            val mediaSourceSsrc = getMediaSourceSsrc(packetBuf)
            val fci = GenericNackFci.fromBuffer(packetBuf)

            buf.position(bufStartPosition + header.lengthBytes)

            return RtcpFbNackPacket(header, mediaSourceSsrc, fci, packetBuf)
        }
        fun fromValues(
            header: RtcpHeader = RtcpHeader(),
            mediaSourceSsrc: Long = -1,
            missingSeqNums: SortedSet<Int> = sortedSetOf()
        ): RtcpFbNackPacket {
            val fci = GenericNackFci.fromValues(missingSeqNums)
            return RtcpFbNackPacket(header, mediaSourceSsrc, fci)
        }
    }
}
