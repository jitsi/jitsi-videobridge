/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.PacketCache
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger

/**
 * When a nack packet is received, the [NackHandler] will try to retrieve the
 * nacked packets from the cache and then send them to the RTX output pipeline.
 */
class NackHandler(
    private val packetCache: PacketCache,
    private val onNackedPacketsReady: PacketHandler,
    parentLogger: Logger
) : NodeStatsProducer, RtcpListener, EndpointConnectionStats.EndpointConnectionStatsListener {
    private var numNacksReceived = 0
    private var numNackedPackets = 0
    private var numRetransmittedPackets = 0
    private var numPacketsNotResentDueToDelay = 0
    private var numCacheMisses = 0
    private val logger = createChildLogger(parentLogger)
    private var currRtt: Double = -1.0

    override fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Long) {
        if (packet is RtcpFbNackPacket) {
            onNackPacket(packet)
        }
    }

    private fun onNackPacket(nackPacket: RtcpFbNackPacket) {
        logger.cdebug { "Nack received for ${nackPacket.mediaSourceSsrc} ${nackPacket.missingSeqNums}" }
        val now = System.currentTimeMillis()

        numNacksReceived++
        val nackedPackets = mutableListOf<RtpPacket>()
        val ssrc = nackPacket.mediaSourceSsrc
        numNackedPackets += nackPacket.missingSeqNums.size
        nackPacket.missingSeqNums.forEach { missingSeqNum ->
            packetCache.get(ssrc, missingSeqNum)?.let { container ->
                val delay = now - container.timeAdded
                val shouldResendPacket =
                    (currRtt == -1.0) ||
                    (delay >= Math.min(currRtt * .9, currRtt - 5))
                if (shouldResendPacket) {
                    // The cache returns a null container on failure, never a container with a null packet. Maybe we
                    // can refactor to make it explicit.
                    nackedPackets.add(container.item!!)
                    packetCache.updateTimestamp(ssrc, missingSeqNum, now)
                    numRetransmittedPackets++
                } else {
                    numPacketsNotResentDueToDelay++
                }
            } ?: run {
                logger.cdebug { "Nack'd packet $ssrc $missingSeqNum wasn't in cache, unable to retransmit" }
                numCacheMisses++
            }
        }
        nackedPackets.forEach { onNackedPacketsReady.processPacket(PacketInfo(it)) }
    }

    override fun onRttUpdate(newRttMs: Double) {
        currRtt = newRttMs
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Nack handler").apply {
        addNumber("num_nack_packets_received", numNackedPackets)
        addNumber("num_nacked_packets", numNackedPackets)
        addNumber("num_retransmitted_packets", numRetransmittedPackets)
        addNumber("num_packets_not_retransmitted", numPacketsNotResentDueToDelay)
        addNumber("num_cache_misses", numCacheMisses)
    }
}
