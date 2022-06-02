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
package org.jitsi.videobridge.stats

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.BridgeJitterStats
import org.jitsi.nlj.stats.PacketDelayStats
import org.jitsi.rtp.extensions.looksLikeRtcp
import org.jitsi.rtp.extensions.looksLikeRtp
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.stats.BucketStats
import org.jitsi.videobridge.Endpoint

/**
 * Track how long it takes for all RTP and RTCP packets to make their way through the bridge.
 * [Endpoint] and [Relay] are the 'last place' that is aware of [PacketInfo] in the outgoing
 * chains; they track these stats here.  Since they're static, these members will track the delay
 * for packets going out to all endpoints.
 */
object PacketTransitStats {
    private val rtpPacketDelayStats = PacketDelayStats()
    private val rtcpPacketDelayStats = PacketDelayStats()

    private val bridgeJitterStats = BridgeJitterStats()

    @JvmStatic
    fun packetSent(packetInfo: PacketInfo) {
        if (packetInfo.packet.looksLikeRtp()) {
            rtpPacketDelayStats.addPacket(packetInfo)
            bridgeJitterStats.packetSent(packetInfo)
        } else if (packetInfo.packet.looksLikeRtcp()) {
            rtcpPacketDelayStats.addPacket(packetInfo)
        }
    }

    @JvmStatic
    val statsJson: OrderedJsonObject
        get() {
            val stats = OrderedJsonObject()
            stats["e2e_packet_delay"] = getPacketDelayStats()
            stats["overall_bridge_jitter"] = bridgeJitterStats.jitter
            return stats
        }

    @JvmStatic
    val bridgeJitter
        get() = bridgeJitterStats.jitter

    private fun getPacketDelayStats() = OrderedJsonObject().apply {
        put("rtp", rtpPacketDelayStats.toJson(format = BucketStats.Format.CumulativeRight))
        put("rtcp", rtcpPacketDelayStats.toJson(format = BucketStats.Format.CumulativeRight))
    }
}
