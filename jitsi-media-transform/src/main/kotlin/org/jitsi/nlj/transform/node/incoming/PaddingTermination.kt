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
package org.jitsi.nlj.transform.node.incoming

import java.util.Collections
import java.util.TreeMap
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.FilterNode
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.LRUCache

class PaddingTermination : FilterNode("Padding termination") {
    private val replayContexts: MutableMap<Long, MutableSet<Int>> = TreeMap()
    private var numPaddingPacketsSeen = 0

    override fun accept(packetInfo: PacketInfo): Boolean {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        val replayContext = replayContexts.computeIfAbsent(rtpPacket.ssrc) {
            Collections.newSetFromMap(LRUCache(1500))
        }

        return if (replayContext.add(rtpPacket.sequenceNumber)) {
            true
        } else {
            numPaddingPacketsSeen++
            false
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_padding_packets_seen", numPaddingPacketsSeen)
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
