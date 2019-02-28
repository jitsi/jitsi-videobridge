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
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.util.LRUCache
import java.util.Collections
import java.util.TreeMap

class PaddingTermination : Node("Padding termination") {
    private val replayContexts: MutableMap<Long, MutableSet<Int>> = TreeMap()
    private var numPaddingPacketsSeen = 0

    override fun doProcessPackets(p: List<PacketInfo>) {
        val outPackets = mutableListOf<PacketInfo>()
        p.forEach { packetInfo ->
            checkPacket(packetInfo.packetAs<RtpPacket>())?.let {
                outPackets.add(packetInfo)
            } ?: run {
                numPaddingPacketsSeen++
            }
        }
        next(outPackets)
    }

    private fun checkPacket(packet: RtpPacket): RtpPacket? {
        val replayContext = replayContexts.computeIfAbsent(packet.header.ssrc) {
            Collections.newSetFromMap(LRUCache(1500))
        }
        return if (replayContext.add(packet.header.sequenceNumber)) packet else null
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num padding packets seen: $numPaddingPacketsSeen")
        }
    }
}
