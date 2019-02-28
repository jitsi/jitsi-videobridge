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
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpByePacket
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbNackPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.sdes.RtcpSdesPacket
import org.jitsi_modified.impl.neomedia.rtp.TransportCCEngine

class RtcpTermination(
    private val rtcpEventNotifier: RtcpEventNotifier,
    private val transportCcEngine: TransportCCEngine? = null
) : Node("RTCP termination") {
    private var numNacksReceived = 0
    private var numFirsReceived = 0
    private var numPlisReceived = 0
    private var numRrsReceiver = 0
    private var numSrsReceived = 0
    private var numSdesReceived = 0

    override fun doProcessPackets(p: List<PacketInfo>) {
        val outPackets = mutableListOf<PacketInfo>()
        p.forEach { packetInfo ->
            val pkt = packetInfo.packet
            when (pkt) {
                is RtcpFbTccPacket -> handleTccPacket(pkt)
                is RtcpFbNackPacket -> {
                    numNacksReceived++
                }
                is RtcpSrPacket -> {
                    numSrsReceived++
                }
                is RtcpRrPacket -> {
                    numRrsReceiver++
                }
                is RtcpByePacket -> {
                    logger.cinfo { "BRIAN: got BYE packet:\n$pkt" }
                    //TODO
                }
                is RtcpSdesPacket -> {
                    numSdesReceived++
                }
                is RtcpFbPliPacket, is RtcpFbFirPacket -> {
                    if (pkt is RtcpFbPliPacket) {
                        numPlisReceived++
                    } else {
                        numFirsReceived++
                    }
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
            rtcpEventNotifier.notifyRtcpReceived(packetInfo)
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
            addStat("num NACK packets rx: $numNacksReceived")
            addStat("num PLI packets rx: $numPlisReceived")
            addStat("num FIR packets rx: $numFirsReceived")
            addStat("num SR packets rx: $numSrsReceived")
            addStat("num RR packets rx: $numRrsReceiver")
            addStat("num SDES packets rx: $numSdesReceived")
        }
    }
}
