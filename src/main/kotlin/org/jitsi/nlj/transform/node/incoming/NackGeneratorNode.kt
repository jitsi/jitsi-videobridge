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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.Nack
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket

class NackGeneratorNode(
    private val onNackPacketReady: (RtcpPacket) -> Unit = {}
) : Node("Nack generator") {
    private var nacksSent = 0
    // For now, just nack a chunk of 5 packets for every 100 we see
    var packetsSinceLastNack = 0
    override fun doProcessPackets(p: List<PacketInfo>) {
//        packetsSinceLastNack += p.size
//        if (packetsSinceLastNack > 100) {
//            // Nack the 5 previous packets
//            val missingSeqNumBase = (p.get(0) as RtpPacket).header.sequenceNumber - 5
//
//            val nackFci = Nack(packetId = missingSeqNumBase, missingSeqNums = (missingSeqNumBase until missingSeqNumBase+5).toList())
//            val nackPacket = RtcpFbPacket(feedbackControlInformation = nackFci)
//            nackPacket.mediaSourceSsrc = (p.get(0) as RtpPacket).header.ssrc
//            onNackPacketReady(nackPacket)
//
//            nacksSent++
//            packetsSinceLastNack = 0
//        }
        next(p)
    }

    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            appendLnIndent(indent + 2, "num nacks sent: $nacksSent")
            toString()
        }
    }
}
