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

import kotlin.reflect.KClass
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.rtp.rtcp.RtcpPacket

class SentRtcpStats : ObserverNode("Sent RTCP stats") {
    private var sentRtcpCounts = mutableMapOf<KClass<out RtcpPacket>, Int>().withDefault { 0 }

    override fun observe(packetInfo: PacketInfo) {
        val rtcpPacket: RtcpPacket = packetInfo.packetAs()
        sentRtcpCounts[rtcpPacket::class] = (sentRtcpCounts[rtcpPacket::class] ?: 0) + 1
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            sentRtcpCounts.forEach { (rtcpType, count) ->
                addNumber("num_${rtcpType}_tx", count)
            }
        }
    }
}
