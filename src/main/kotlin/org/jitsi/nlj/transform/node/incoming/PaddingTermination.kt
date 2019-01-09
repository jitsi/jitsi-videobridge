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

import org.jitsi.impl.neomedia.transform.PaddingTermination
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.toRawPacket

class PaddingTermination : Node("Padding termination") {
    private val paddingTermination = PaddingTermination()
    private var numPaddingPacketsSeen = 0

    override fun doProcessPackets(p: List<PacketInfo>) {
        val outPackets = mutableListOf<PacketInfo>()
        p.forEach { packetInfo ->
            paddingTermination.reverseTransform(packetInfo.packet.toRawPacket())?.let {
                // If paddingTermination didn't return null, that means this is a packet
                // that should be forwarded.
                outPackets.add(packetInfo)
            } ?: run {
                numPaddingPacketsSeen++
            }
        }
        next(outPackets)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num padding packets seen: $numPaddingPacketsSeen")
        }
    }
}
