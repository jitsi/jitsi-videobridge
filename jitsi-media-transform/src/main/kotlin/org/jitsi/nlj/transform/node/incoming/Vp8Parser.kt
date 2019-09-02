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
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.createChildLogger
import org.jitsi.utils.logging2.Logger

/**
 * Some [Vp8Packet] fields are not able to be determined by looking at a single VP8 packet (for example the spatial
 * layer index can only be acquired from keyframes).  This class keeps a longer-running 'memory' of the information
 * needed to fill out fields like that in [Vp8Packet]s
 */
class Vp8Parser(
    parentLogger: Logger
) : TransformerNode("Vp8 parser") {
    private val logger = parentLogger.createChildLogger(Vp8Parser::class)
    private val ssrcToSpatialLayerQuality: MutableMap<Long, Int> = HashMap()
    // Stats
    private var numKeyframes: Int = 0

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val videoRtpPacket: VideoRtpPacket = packetInfo.packet as VideoRtpPacket
        if (videoRtpPacket is Vp8Packet) {
            // If this was part of a keyframe, it will have already had it set
            if (videoRtpPacket.spatialLayerIndex > -1) {
                ssrcToSpatialLayerQuality.putIfAbsent(videoRtpPacket.ssrc, videoRtpPacket.spatialLayerIndex)
            } else {
                videoRtpPacket.spatialLayerIndex = ssrcToSpatialLayerQuality[videoRtpPacket.ssrc] ?: -1
            }
            if (videoRtpPacket.isKeyframe) {
                logger.cdebug { "Received a keyframe for ssrc ${videoRtpPacket.ssrc} ${videoRtpPacket.sequenceNumber}" }
                numKeyframes++
            }
        }

        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_keyframes", numKeyframes)
        }
    }
}