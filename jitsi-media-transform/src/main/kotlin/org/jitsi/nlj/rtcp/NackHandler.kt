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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.PacketHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.PacketOrigin
import org.jitsi.nlj.stats.EndpointConnectionStats
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.PacketCache
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * When a nack packet is received, the [NackHandler] will try to retrieve the
 * nacked packets from the cache and then send them to the RTX output pipeline.
 *
 * It does not retransmit a packet that was first sent more than max([maxPacketAge], [maxPacketAgeRtts] * RTT) ago
 * (by default max(3 s, 9 RTT), see `jmt.nack` in reference.conf). A receiver that comes back after a network outage
 * can NACK all the packets that it lost. Packets that old are no longer useful to the receiver, and a burst of them
 * fills the send queue.
 */
class NackHandler(
    private val packetCache: PacketCache,
    private val onNackedPacketsReady: PacketHandler,
    parentLogger: Logger,
    private val clock: Clock = Clock.systemUTC(),
    private val maxPacketAge: Duration = maxPacketAgeConfig,
    private val maxPacketAgeRtts: Double = maxPacketAgeRttsConfig
) : NodeStatsProducer, RtcpListener, EndpointConnectionStats.EndpointConnectionStatsListener {
    private var numNacksReceived = 0
    private var numNackedPackets = 0
    private var numRetransmittedPackets = 0
    private var numPacketsNotResentDueToDelay = 0
    private var numPacketsNotResentDueToAge = 0
    private var numCacheMisses = 0
    private val logger = createChildLogger(parentLogger)
    private var currRtt: Double = -1.0

    override fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Instant?) {
        if (packet is RtcpFbNackPacket) {
            onNackPacket(packet)
        }
    }

    private fun onNackPacket(nackPacket: RtcpFbNackPacket) {
        logger.cdebug { "Nack received for ${nackPacket.mediaSourceSsrc} ${nackPacket.missingSeqNums}" }
        val now = clock.millis()
        val maxAgeMs = if (currRtt == -1.0) {
            maxPacketAge.toMillis().toDouble()
        } else {
            Math.max(maxPacketAge.toMillis().toDouble(), maxPacketAgeRtts * currRtt)
        }

        numNacksReceived++
        val nackedPackets = mutableListOf<RtpPacket>()
        val ssrc = nackPacket.mediaSourceSsrc
        numNackedPackets += nackPacket.missingSeqNums.size
        nackPacket.missingSeqNums.forEach { missingSeqNum ->
            packetCache.get(ssrc, missingSeqNum)?.let { container ->
                // The time of the first transmission. The cache updates [timeAdded] when we retransmit the packet.
                val age = now - container.timeInserted
                // The time since the last transmission (the first one, or the last retransmission).
                val delay = now - container.timeAdded
                val shouldResendPacket =
                    (currRtt == -1.0) ||
                        (delay >= Math.min(currRtt * .9, currRtt - 5))
                if (age > maxAgeMs) {
                    BufferPool.returnBuffer(container.item!!.buffer)
                    numPacketsNotResentDueToAge++
                } else if (shouldResendPacket) {
                    // The cache returns a null container on failure, never a container with a null packet. Maybe we
                    // can refactor to make it explicit.
                    nackedPackets.add(container.item!!)
                    packetCache.updateTimestamp(ssrc, missingSeqNum, now)
                    numRetransmittedPackets++
                } else {
                    BufferPool.returnBuffer(container.item!!.buffer)
                    numPacketsNotResentDueToDelay++
                }
            } ?: run {
                logger.cdebug { "Nack'd packet $ssrc $missingSeqNum wasn't in cache, unable to retransmit" }
                numCacheMisses++
            }
        }
        nackedPackets.forEach {
            val packetInfo = PacketInfo(it)
            packetInfo.packetOrigin = PacketOrigin.Retransmission
            onNackedPacketsReady.processPacket(packetInfo)
        }
    }

    override fun onRttUpdate(newRttMs: Double) {
        currRtt = newRttMs
    }

    override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Nack handler").apply {
        addNumber("num_nack_packets_received", numNackedPackets)
        addNumber("num_nacked_packets", numNackedPackets)
        addNumber("num_retransmitted_packets", numRetransmittedPackets)
        addNumber("num_packets_not_retransmitted", numPacketsNotResentDueToDelay)
        addNumber("num_packets_not_retransmitted_too_old", numPacketsNotResentDueToAge)
        addNumber("num_cache_misses", numCacheMisses)
    }

    companion object {
        /** The minimum value of the max age of a packet that we retransmit. */
        private val maxPacketAgeConfig: Duration by config {
            "jmt.nack.max-packet-age".from(JitsiConfig.newConfig)
        }

        /** The max age of a packet that we retransmit, as a number of RTTs (if larger than [maxPacketAgeConfig]). */
        private val maxPacketAgeRttsConfig: Double by config {
            "jmt.nack.max-packet-age-rtts".from(JitsiConfig.newConfig)
        }
    }
}
