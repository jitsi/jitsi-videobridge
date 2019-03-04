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
package org.jitsi.nlj.rtcp

import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.PacketCache
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbNackPacket

/**
 * When a nack packet is received, the [NackHandler] will try to retrieve the
 * nacked packets from the cache and then send them to the RTX output pipeline.
 */
class NackHandler(
    private val packetCache: PacketCache,
    private val onNackedPacketsReady: PacketHandler
) : NodeStatsProducer, RtcpListener {
    private var numNacksReceived = 0
    private var numNackedPackets = 0
    private var numRetransmittedPackets = 0
    private var numCacheMisses = 0
    private val logger = getLogger(this.javaClass)

    override fun onRtcpPacketReceived(packetInfo: PacketInfo) {
        val packet = packetInfo.packet
        if (packet is RtcpFbNackPacket) {
            onNackPacket(packet)
        }
    }

    private fun onNackPacket(nackPacket: RtcpFbNackPacket) {
        logger.cdebug { "Nack received for ${nackPacket.mediaSourceSsrc} ${nackPacket.missingSeqNums}" }

        numNacksReceived++
        val nackedPackets = mutableListOf<Packet>()
        val ssrc = nackPacket.mediaSourceSsrc
        numNackedPackets += nackPacket.missingSeqNums.size
        nackPacket.missingSeqNums.forEach { missingSeqNum ->
            packetCache.getPacket(ssrc, missingSeqNum)?.let {
                nackedPackets.add(it)
                numRetransmittedPackets++
            } ?: run {
                logger.cdebug { "Nack'd packet $ssrc $missingSeqNum wasn't in cache, unable to retransmit" }
                numCacheMisses++
            }
        }
        nackedPackets.forEach { onNackedPacketsReady.processPacket(PacketInfo(it)) }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return NodeStatsBlock("Nack handler").apply {
            addStat( "num nack packets received: $numNackedPackets")
            addStat( "num nacked packets: $numNackedPackets")
            addStat( "num retransmitted packets: $numRetransmittedPackets")
            addStat( "num cache misses: $numCacheMisses")
        }
    }
}
