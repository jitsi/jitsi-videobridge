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
import org.jitsi.nlj.transform.node.FilterNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.rtp.rtcp.RtcpByePacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSdesPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine

class RtcpTermination(
    private val rtcpEventNotifier: RtcpEventNotifier,
    private val transportCcEngine: TransportCCEngine? = null
) : FilterNode("RTCP termination") {
    private var packetReceiveCounts = mutableMapOf<String, Int>()

    override fun accept(packetInfo: PacketInfo): Boolean {
        var accept = false

        val pkt = packetInfo.packet
        when (pkt) {
            is RtcpFbTccPacket -> handleTccPacket(pkt)
            is RtcpFbNackPacket -> {
                println("Nack received for ${pkt.mediaSourceSsrc} ${pkt.missingSeqNums}")
            }
            is RtcpSrPacket -> {
            }
            is RtcpRrPacket -> {
            }
            is RtcpByePacket -> {
                logger.cinfo { "BRIAN: got BYE packet:\n$pkt" }
                //TODO
            }
            is RtcpSdesPacket -> {
            }
            is RtcpFbPliPacket, is RtcpFbFirPacket -> {
                // We'll let these pass through and be forwarded to the sender who will be
                // responsible for translating/aggregating them
                logger.cdebug { "BRIAN: passing through ${pkt::class} rtcp packet: ${pkt.buffer.toHex()}" }
                accept = true
            }
            else -> {
                logger.cinfo { "TODO: not yet handling RTCP packet of type ${pkt.javaClass}"}
            }
        }
        //TODO: keep an eye on if anything in here takes a while it could slow the packet pipeline down
        packetReceiveCounts.merge(packetInfo.packet::class.simpleName!!, 1, Int::plus)
        rtcpEventNotifier.notifyRtcpReceived(packetInfo.packetAs<RtcpPacket>(), packetInfo.receivedTime)

        return accept
    }

    private fun handleTccPacket(tccPacket: RtcpFbTccPacket) {
        transportCcEngine?.tccReceived(tccPacket)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            packetReceiveCounts.forEach {type, count ->
                addStat("num $type rx: $count")
            }
        }
    }
}
