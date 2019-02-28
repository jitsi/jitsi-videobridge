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

package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPliPacket

class SentRtcpStats : Node("Sent RTCP stats") {
    private var numPlisSent = 0
    private var numFirsSent = 0

    override fun doProcessPackets(p: List<PacketInfo>) {
        p.forEachAs<RtcpPacket> { _, expectedPacketType ->
            when (expectedPacketType) {
                is RtcpFbPliPacket -> numPlisSent++
                is RtcpFbFirPacket -> numFirsSent++
            }
        }
        next(p)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num PLI packets tx: $numPlisSent")
            addStat("num FIR packets tx: $numFirsSent")
        }
    }
}