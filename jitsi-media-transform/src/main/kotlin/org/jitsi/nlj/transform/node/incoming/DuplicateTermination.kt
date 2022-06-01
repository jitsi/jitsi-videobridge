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

import java.util.TreeMap
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.LRUCache

/**
 * A node which drops packets with SSRC and sequence number pairs identical to ones
 * that have previously been seen.
 *
 * (Since SRTP also has anti-replay protection, the normal case where duplicates
 * will occur is after the [RtxHandler], since duplicate packets are sent over RTX for probing.)
 */
class DuplicateTermination() : TransformerNode("Duplicate termination") {
    private val replayContexts: MutableMap<Long, MutableSet<Int>> = TreeMap()
    private var numDuplicatePacketsDropped = 0

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        val replayContext = replayContexts.computeIfAbsent(rtpPacket.ssrc) {
            LRUCache.lruSet(1500, true)
        }

        if (!replayContext.add(rtpPacket.sequenceNumber)) {
            numDuplicatePacketsDropped++
            return null
        }

        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addNumber("num_duplicate_packets_dropped", numDuplicatePacketsDropped)
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
