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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtcp.RtcpEventNotifier
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.BufferPool
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.createChildLogger
import org.jitsi.rtp.rtcp.CompoundRtcpPacket
import org.jitsi.rtp.rtcp.RtcpByePacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSdesPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.utils.logging2.Logger

class RtcpTermination(
    private val rtcpEventNotifier: RtcpEventNotifier,
    parentLogger: Logger
) : TransformerNode("RTCP termination") {
    private val logger = parentLogger.createChildLogger(RtcpTermination::class)
    private var packetReceiveCounts = mutableMapOf<String, Int>()
    /**
     * Number of packets we failed to forward because a compound packet contained more than one
     * packet we wanted to forward. Ideally this shouldn't happen.
     */
    private var numFailedToForward = 0

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val compoundRtcp = packetInfo.packetAs<CompoundRtcpPacket>()
        var forwardedRtcp: RtcpPacket? = null

        compoundRtcp.packets.forEach { rtcpPacket ->
            when (rtcpPacket) {
                is RtcpFbPliPacket, is RtcpFbFirPacket, is RtcpSrPacket -> {
                    // We'll let these pass through and be forwarded to the conference (where they will be
                    // routed to the other endpoint(s))
                    // NOTE(brian): this should work fine as long as we can't receive 2 RTCP packets
                    // we want to forward in the same compound packet.  If we can, then we may need
                    // to turn this into a MultipleOutputNode
                    forwardedRtcp?.let {
                        logger.cinfo { "Failed to forward a packet of type ${it::class.simpleName} " +
                            ". Replaced by ${rtcpPacket::class.simpleName}." }
                        numFailedToForward++
                    }
                    forwardedRtcp = rtcpPacket
                }
                is RtcpSdesPacket, is RtcpRrPacket, is RtcpFbNackPacket, is RtcpByePacket, is RtcpFbTccPacket -> {
                    // Supported, but no special handling here (any special handling will be in
                    // notifyRtcpReceived below
                }
                else -> {
                    logger.cinfo { "TODO: not yet handling RTCP packet of type ${rtcpPacket.javaClass}" }
                }
            }
            // TODO: keep an eye on if anything in here takes a while it could slow the packet pipeline down
            packetReceiveCounts.merge(rtcpPacket::class.simpleName!!, 1, Int::plus)
            rtcpEventNotifier.notifyRtcpReceived(rtcpPacket, packetInfo.receivedTime)

            (forwardedRtcp as? RtcpSrPacket)?.let {
                logger.cdebug { "Saw an sr from ssrc=${rtcpPacket.senderSsrc}, timestamp=${it.senderInfo.rtpTimestamp}" }
                forwardedRtcp = if (it.reportCount > 0) {
                    // Eliminates any report blocks as we don't want to relay those
                    it.cloneWithoutReportBlocks()
                } else {
                    it
                }
            }
        }

        return forwardedRtcp?.let {
            if (it.buffer != packetInfo.packet.buffer) {
                // We're not using the original packet's buffer, so we can return it to the pool
                BufferPool.returnBuffer(packetInfo.packet.buffer)
            }
            packetInfo.packet = it
            packetInfo
        } ?: run {
            packetDiscarded(packetInfo)
            null
        }
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            packetReceiveCounts.forEach { type, count ->
                addNumber("num_${type}_rx", count)
            }
            addNumber("num_failed_to_forward", numFailedToForward)
        }
    }
}
