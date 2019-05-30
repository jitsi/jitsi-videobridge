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

import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.NodeStatsProducer
import org.jitsi.nlj.util.addMbps

interface PacketHandler {
    /**
     * Process the given packets
     */
    fun processPacket(packetInfo: PacketInfo)
}

/**
 * A [PacketHandler] which tracks statistics about the packets it handles
 */
abstract class StatsKeepingPacketHandler : PacketHandler, NodeStatsProducer {
    private val statistics = Statistics()

    final override fun processPacket(packetInfo: PacketInfo) {
        val now = System.currentTimeMillis()
        statistics.packetsReceived++
        statistics.bytesReceived += packetInfo.packet.length
        if (statistics.firstPacketReceivedTime == 0L) {
            statistics.firstPacketReceivedTime = now
        }
        statistics.lastPacketReceivedTime = now

        doProcessPacket(packetInfo)
    }

    /**
     * We override [processPacket] above and prevent subclasses from doing so
     * to make sure this class sees all packets that pass through it and it
     * can track stats correctly.  This method should be implemented by
     * subclasses as a stand-in for [processPacket], as it is called once
     * all the stat-tracking has been done.
     */
    protected abstract fun doProcessPacket(packetInfo: PacketInfo)

    override fun getNodeStats(): NodeStatsBlock = statistics.getNodeStats()

    /**
     * Statistics common to [PacketHandler]s which are interesting to track
     */
    data class Statistics(
        var firstPacketReceivedTime: Long = 0,
        var lastPacketReceivedTime: Long = 0,
        var bytesReceived: Long = 0,
        var packetsReceived: Long = 0
    ) : NodeStatsProducer {
        override fun getNodeStats(): NodeStatsBlock = NodeStatsBlock("Packet handler stats").apply {
            addNumber(RECEIVED_PACKETS, packetsReceived)
            addNumber(RECEIVED_BYTES, bytesReceived)
            addNumber(RECEIVED_DURATION_MS, lastPacketReceivedTime - firstPacketReceivedTime)
            addMbps(RECEIVED_BITRATE_MBPS, RECEIVED_BYTES, RECEIVED_DURATION_MS)
        }

        companion object {
            private const val RECEIVED_PACKETS = "received_packets"
            private const val RECEIVED_BYTES = "received_bytes"
            private const val RECEIVED_DURATION_MS = "received_duration_ms"
            private const val RECEIVED_BITRATE_MBPS = "received_bitrate_mbps"
        }
    }
}
