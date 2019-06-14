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

package org.jitsi.nlj.stats

import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tracks stats which are not necessarily tied to send or receive but the endpoint overall
 */
class EndpointConnectionStats : RtcpListener {
    interface EndpointConnectionStatsListener {
        fun onRttUpdate(newRtt: Double)
    }
    data class Snapshot(
        val rtt: Double
    )
    private val endpointConnectionStatsListeners: MutableList<EndpointConnectionStatsListener> = CopyOnWriteArrayList()

    // Maps the compacted NTP timestamp found in an SR SenderInfo to the clock time (in milliseconds)
    //  at which it was transmitted
    private val srSentTimes: MutableMap<Long, Long> = ConcurrentHashMap()
    private val logger = getLogger(this.javaClass)

    /**
     * The calculated RTT, in milliseconds, between the bridge and the endpoint
     */
    private var rtt: Double = 0.0

    fun addListener(listener: EndpointConnectionStatsListener) {
        endpointConnectionStatsListeners.add(listener)
    }

    fun getSnapshot(): Snapshot {
        // NOTE(brian): right now we only track a single stat, so synchronization isn't necessary.  If we add more
        // stats and it's appropriate they be 'snapshotted' together at the same time, we'll need to add a lock here
        return Snapshot(rtt)
    }

    override fun rtcpPacketReceived(packet: RtcpPacket, receivedTime: Long) {
        when (packet) {
            is RtcpSrPacket -> {
                logger.cdebug { "Received SR packet with ${packet.reportBlocks.size} report blocks" }
                packet.reportBlocks.forEach { reportBlock -> processReportBlock(receivedTime, reportBlock) }
            }
            is RtcpRrPacket -> {
                logger.cdebug { "Received RR packet with ${packet.reportBlocks.size} report blocks" }
                packet.reportBlocks.forEach { reportBlock -> processReportBlock(receivedTime, reportBlock) }
            }
        }
    }

    override fun rtcpPacketSent(packet: RtcpPacket) {
        when (packet) {
            is RtcpSrPacket -> {
                logger.cdebug { "Tracking sent SR packet with compacted timestamp ${packet.senderInfo.compactedNtpTimestamp}" }
                srSentTimes[packet.senderInfo.compactedNtpTimestamp] =
                        System.currentTimeMillis()
            }
        }
    }

    private fun processReportBlock(receivedTime: Long, reportBlock: RtcpReportBlock) {
        if (reportBlock.lastSrTimestamp > 0 && reportBlock.delaySinceLastSr > 0) {
            // We need to know when we sent the last SR
            val srSentTime = srSentTimes.getOrDefault(reportBlock.lastSrTimestamp, -1)
            if (srSentTime > 0) {
                // The delaySinceLastSr value is given in 1/65536ths of a second, so divide it by 65.536 to get it
                // in milliseconds
                val remoteProcessingDelayMs = reportBlock.delaySinceLastSr / 65.536
                rtt = receivedTime - srSentTime - remoteProcessingDelayMs
                endpointConnectionStatsListeners.forEach { it.onRttUpdate(rtt) }
            }
        } else {
            logger.cdebug { "Report block for ssrc ${reportBlock.ssrc} didn't have SR data: " +
                    "lastSrTimestamp was ${reportBlock.lastSrTimestamp}, " +
                    "delaySinceLastSr was ${reportBlock.delaySinceLastSr}" }
        }
    }
}