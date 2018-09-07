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

import org.jitsi.nlj.NackHandler
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.transform.node.Node
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbNackPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbTccPacket

class RtcpTermination : Node("RTCP termination") {
    var nackHandler: NackHandler? = null
    override fun doProcessPackets(p: List<PacketInfo>) {
        val outPackets = mutableListOf<PacketInfo>()
        //TODO: we don't need to use forEachAs here i don't think
        p.forEachAs<RtcpPacket> { packetInfo, pkt ->
            when (pkt) {
                is RtcpRrPacket, is RtcpSrPacket, is RtcpFbTccPacket -> {
                    // Process & terminate
//                    println("BRIAN: terminating ${it.javaClass} rtcp packet")
                }
                is RtcpFbNackPacket -> {
                    println("BRIAN: received nack for packets: ${pkt.missingSeqNums}")
                    nackHandler?.onNackPacket(pkt)
                }
                is RtcpFbPliPacket, is RtcpFbFirPacket -> {
                    // We'll let these pass through and be forwarded to the sender who will be
                    // responsible for translating/aggregating them
                    println("BRIAN: passing through ${pkt::class} rtcp packet: ${pkt.getBuffer().toHex()}")
                    outPackets.add(packetInfo)
                }
            }
        }
        next(outPackets)
    }
}
