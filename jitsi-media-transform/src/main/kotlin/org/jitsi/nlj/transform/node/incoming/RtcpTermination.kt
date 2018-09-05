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

import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.forEachAs
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbNackPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbTccPacket

class RtcpTermination : Node("RTCP termination") {
    override fun doProcessPackets(p: List<Packet>) {
        val outPackets = mutableListOf<RtcpPacket>()
        p.forEachAs<RtcpPacket> {
            when (it) {
                is RtcpRrPacket, is RtcpSrPacket, is RtcpFbTccPacket -> {
                    // Process & terminate
//                    println("BRIAN: terminating ${it.javaClass} rtcp packet")
                }
                //TODO: not dealing with nacks for now, as they cause decrypt issues (i think because the packets
                // are being retransmitted and causing replay issues)
                /*is RtcpFbNackPacket,*/ is RtcpFbPliPacket, is RtcpFbFirPacket -> {
                    // Pass through
                    // (nacks we'll eventually do processing on and try to retransmit, the
                    // others will go all the way through)
                    println("BRIAN: passing through ${it::class} rtcp packet: ${it.getBuffer().toHex()}")
                    outPackets.add(it)
                }
            }
        }
        next(outPackets)
    }
}
