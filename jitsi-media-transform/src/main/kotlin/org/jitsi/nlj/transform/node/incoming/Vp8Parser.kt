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
import org.jitsi.nlj.transform.node.ModifierNode
import org.jitsi.nlj.util.StateChangeLogger
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Some [Vp8Packet] fields are not able to be determined by looking at a single VP8 packet (for example the spatial
 * layer index can only be acquired from keyframes).  This class keeps a longer-running 'memory' of the information
 * needed to fill out fields like that in [Vp8Packet]s
 *
 * TODO(brian): This class shouldn't really be a [ModifierNode], but since we put it in-line in the video
 * receive pipeline (as opposed to demuxing based on payload type and routing only known VP8 packets to
 * it), it's the most appropriate node type for now.
 */
class Vp8Parser(
    parentLogger: Logger
) : ModifierNode("Vp8 parser") {
    private val logger = createChildLogger(parentLogger)
    private val ssrcToHeight: MutableMap<Long, Int> = HashMap()
    // Stats
    private var numKeyframes: Int = 0

    private val pictureIdState = StateChangeLogger("missing picture id", logger)
    private val extendedPictureIdState = StateChangeLogger("missing extended picture ID", logger)
    private val tidWithoutTl0PicIdxState = StateChangeLogger("TID with missing TL0PICIDX", logger)

    override fun modify(packetInfo: PacketInfo): PacketInfo {
        val videoRtpPacket: VideoRtpPacket = packetInfo.packet as VideoRtpPacket
        if (videoRtpPacket is Vp8Packet) {
            // If this was part of a keyframe, it will have already had it set
            if (videoRtpPacket.height > -1) {
                // TODO: handle case where new height is from a packet older than the
                // latest height we've seen.
                ssrcToHeight.putIfAbsent(videoRtpPacket.ssrc, videoRtpPacket.height)
            } else {
                videoRtpPacket.height = ssrcToHeight[videoRtpPacket.ssrc] ?: -1
            }
            if (videoRtpPacket.isKeyframe) {
                logger.cdebug { "Received a keyframe for ssrc ${videoRtpPacket.ssrc} ${videoRtpPacket.sequenceNumber}" }
                numKeyframes++
            }

            pictureIdState.setState(videoRtpPacket.hasPictureId, videoRtpPacket) {
                "Packet Data: ${videoRtpPacket.toHex(80)}"
            }
            extendedPictureIdState.setState(videoRtpPacket.hasExtendedPictureId, videoRtpPacket) {
                "Packet Data: ${videoRtpPacket.toHex(80)}"
            }
            tidWithoutTl0PicIdxState.setState(videoRtpPacket.hasTL0PICIDX || !videoRtpPacket.hasTemporalLayerIndex, videoRtpPacket) {
                "Packet Data: ${videoRtpPacket.toHex(80)}"
            }
        }

        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_keyframes", numKeyframes)
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
