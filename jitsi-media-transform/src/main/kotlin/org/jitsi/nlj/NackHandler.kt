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
package org.jitsi.nlj

import org.jitsi.nlj.transform.StatsProducer
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.nlj.util.getByteBuffer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbNackPacket
import org.jitsi_modified.impl.neomedia.rtp.NewRawPacketCache

/**
 * When a nack packet is received, the [NackHandler] will try to retrieve the
 * nacked packets from the cache and then send them to the RTX output pipeline.
 */
class NackHandler(
    private val packetCache: NewRawPacketCache,
    private val onNackedPacketsReady: PacketHandler
) : StatsProducer {
    private var numNacksReceived = 0
    private var numNackedPackets = 0
    private var numRetransmittedPackets = 0

    fun onNackPacket(nackPacket: RtcpFbNackPacket) {
        numNacksReceived++
        val nackedPackets = mutableListOf<Packet>()
        val ssrc = nackPacket.mediaSourceSsrc
        numNackedPackets += nackPacket.missingSeqNums.size
        nackPacket.missingSeqNums.forEach { missingSeqNum ->
            val packet = packetCache.get(ssrc, missingSeqNum)
            if (packet != null) {
                nackedPackets.add(RtpPacket(packet.getByteBuffer()))
                numRetransmittedPackets++
            }
        }
        if (nackedPackets.isNotEmpty()) {
            onNackedPacketsReady.processPackets(nackedPackets.map { PacketInfo(it) })
        }
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            appendLnIndent(indent, "Nack handler")
            appendLnIndent(indent + 2, "num nack packets received: $numNackedPackets")
            appendLnIndent(indent + 2, "num nacked packets: $numNackedPackets")
            appendLnIndent(indent + 2, "num retransmitted packets: $numRetransmittedPackets")
            toString()
        }
    }
}
