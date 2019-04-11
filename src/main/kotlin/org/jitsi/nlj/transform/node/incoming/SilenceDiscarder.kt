/*
 * Copyright @ 2019 - present 8x8, Inc.
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
package org.jitsi.nlj.transform.node.incoming;

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.ResumableStreamRewriter
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.forEachIf
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import java.util.concurrent.ConcurrentHashMap

/**
 * Discards RTP packets which contains silence, masking their loss in the RTP sequence numbers and timestamps of RTP
 * packets, as well as the RTP timestamp in RTCP SR packets.
 */
class SilenceDiscarder {
    val rewriters: MutableMap<Long, ResumableStreamRewriter> = ConcurrentHashMap()
    val rtpNode = RtpTransformer()
    val rtcpNode = RtcpTransformer()

    inner class RtpTransformer : TransformerNode("Silence discarder RTP") {
        override fun transform(packetInfo: PacketInfo): PacketInfo? {
            val packet = packetInfo.packet
            if (packet is AudioRtpPacket) {
                rewriters.computeIfAbsent(packet.ssrc) { ResumableStreamRewriter() }
                        .rewriteRtp(!packetInfo.silence, packet)
            }

            return if (packetInfo.silence) {
                packetDiscarded(packetInfo)
                null
            } else {
                packetInfo
            }
        }
    }

    inner class RtcpTransformer : TransformerNode("Silence discarder RTCP") {
        override fun transform(packetInfo: PacketInfo): PacketInfo? {
            val packet = packetInfo.packet
            when (packet) {
                is RtcpSrPacket -> rewriters[packet.senderSsrc]?.rewriteRtcpSr(packet)
                is CompoundRtcpPacket -> packet.packets.forEachIf<RtcpSrPacket> {
                    rewriters[packet.senderSsrc]?.rewriteRtcpSr(it) }
            }

            return if (packetInfo.silence) null else packetInfo
        }
    }
}
