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

package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cdebug

/**
 * Some [Vp8Packet] fields are not able to be determined by looking at a single VP8 packet (for example the spatial
 * layer index can only be acquired from keyframes).  This class keeps a longer-running 'memory' of the information
 * needed to fill out fields like that in [Vp8Packet]s
 */
class Vp8Parser : Node("Vp8 parser") {
    private val ssrcToSpatialLayerQuality: MutableMap<Long, Int> = HashMap()
    // Stats
    private var numKeyframes: Int = 0

    override fun doProcessPackets(p: List<PacketInfo>) {
        p.forEachAs<VideoRtpPacket> { _, pkt ->
            if (pkt is Vp8Packet) {
                // If this was part of a keyframe, it will have already had it set
                if (pkt.spatialLayerIndex > -1) {
                    ssrcToSpatialLayerQuality.putIfAbsent(pkt.header.ssrc, pkt.spatialLayerIndex)
                } else {
                    pkt.spatialLayerIndex = ssrcToSpatialLayerQuality[pkt.header.ssrc] ?: -1
                }
                if (pkt.isKeyFrame) {
                    logger.cdebug { "Received a keyframe for ssrc ${pkt.header.ssrc}" }
                    numKeyframes++
                }
            }
        }
        next(p)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num keyframes: $numKeyframes")
        }
    }
}