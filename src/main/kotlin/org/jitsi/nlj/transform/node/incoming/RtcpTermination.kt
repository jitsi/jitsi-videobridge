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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.RtcpSdesPacket
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpByePacket
import org.jitsi.rtp.rtcp.SenderInfoParser
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine

class RtcpTermination(
    private val rtcpEventNotifier: RtcpEventNotifier,
    private val transportCcEngine: TransportCCEngine? = null
) : TransformerNode("RTCP termination") {
    private var packetReceiveCounts = mutableMapOf<String, Int>()

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val compoundRtcp = packetInfo.packetAs<CompoundRtcpPacket>()
        var forwardedRtcp: RtcpPacket? = null

        compoundRtcp.packets.forEach { pkt ->
            when (pkt) {
                is RtcpFbTccPacket -> handleTccPacket(pkt)
                is RtcpFbPliPacket, is RtcpFbFirPacket, is RtcpSrPacket -> {
                    // We'll let these pass through and be forwarded to the sender who will be
                    // responsible for translating/aggregating them
                    // NOTE(brian): this should work fine as long as we can't receive 2 RTCP packets
                    // we want to forward in the same compound packet.  If we can, then we may need
                    // to turn this into a MultipleOutputNode
                    forwardedRtcp = pkt
                }
                is RtcpSdesPacket, is RtcpRrPacket, is RtcpFbNackPacket, is RtcpByePacket -> {
                    // Supported, but no special handling here (any special handling will be in
                    // notifyRtcpReceived below
                }
                else -> {
                    logger.cinfo { "TODO: not yet handling RTCP packet of type ${pkt.javaClass}" }
                }
            }
            // TODO: keep an eye on if anything in here takes a while it could slow the packet pipeline down
            packetReceiveCounts.merge(pkt::class.simpleName!!, 1, Int::plus)
            rtcpEventNotifier.notifyRtcpReceived(pkt, packetInfo.receivedTime)

            if (pkt is RtcpSrPacket) {
                // NOTE(george) effectively eliminates any report blocks as we don't want to relay those
                logger.cdebug { "saw an sr from ssrc=${pkt.senderSsrc}, timestamp=${pkt.senderInfo.rtpTimestamp}"}
                pkt.length = SenderInfoParser.SIZE_BYTES
            }
        }
        return if (forwardedRtcp != null) {
            // Manually cast to RtcpPacket as a workaround for https://youtrack.jetbrains.com/issue/KT-7186
            packetInfo.packet = forwardedRtcp as RtcpPacket
            packetInfo
        } else {
            packetDiscarded(packetInfo)
            null
        }
    }

    private fun handleTccPacket(tccPacket: RtcpFbTccPacket) {
        transportCcEngine?.tccReceived(tccPacket)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            packetReceiveCounts.forEach { type, count ->
                addStat("num $type rx: $count")
            }
        }
    }
}
