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

import org.jitsi.nlj.rtcp.NackHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtcp.RtcpListener
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpByePacket
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSdesPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbNackPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbTccPacket
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine
import java.util.concurrent.ConcurrentHashMap

class RtcpTermination(
    private val transportCcEngine: TransportCCEngine? = null
) : Node("RTCP termination") {
    //TODO: change this to use rtcpListeners
    var nackHandler: NackHandler? = null
    private var numNacksReceived = 0
    /**
     * Set of entities interested in being notified of received RTCP packets
     */
    private val rtcpListeners: MutableSet<RtcpListener> = ConcurrentHashMap.newKeySet()

    /**
     * [RtcpTermination] is responsible for all of the primary RTCP handling and termination.  There are, however,
     * other entities which may be interested in RTCP packets for other reasons, so we allow other entities to
     * be notified when RTCP packets are received.
     */
    fun subscribeToRtcp(rtcpListener: RtcpListener) = rtcpListeners.add(rtcpListener)

    override fun doProcessPackets(p: List<PacketInfo>) {
        val outPackets = mutableListOf<PacketInfo>()
        p.forEach { packetInfo ->
            val pkt = packetInfo.packet
            when (pkt) {
                is RtcpFbTccPacket -> handleTccPacket(pkt)
                is RtcpFbNackPacket -> {
                    numNacksReceived++
                    //TODO: nack handler should just be done as a normal RTCP subscriber?
                    nackHandler?.onNackPacket(pkt)
                }
                is RtcpSrPacket -> {
                    //TODO
                }
                is RtcpRrPacket -> {
                    //TODO
                }
                is RtcpByePacket -> {
                    logger.cinfo { "BRIAN: got BYE packet:\n ${pkt.getBuffer().toHex()}"}
                    //TODO
                }
                is RtcpSdesPacket -> {
                    //TODO
                }
                is RtcpFbPliPacket, is RtcpFbFirPacket -> {
                    // We'll let these pass through and be forwarded to the sender who will be
                    // responsible for translating/aggregating them
                    logger.cdebug { "BRIAN: passing through ${pkt::class} rtcp packet: ${pkt.getBuffer().toHex()}" }
                    outPackets.add(packetInfo)
                }
                else -> {
                    logger.cinfo { "TODO: not yet handling RTCP packet of type ${pkt.javaClass}"}
                }
            }
            //TODO: keep an eye on if anything in here takes a while it could slow the packet pipeline down
            rtcpListeners.forEach { it.onRtcpPacket(packetInfo) }
        }
        next(outPackets)
    }

    private fun handleTccPacket(tccPacket: RtcpFbTccPacket) {
        transportCcEngine?.tccReceived(tccPacket)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num nack packets rx: $numNacksReceived")
        }
    }
}
