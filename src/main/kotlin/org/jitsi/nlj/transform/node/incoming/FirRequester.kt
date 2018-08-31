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
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPliPacket

class FirRequester(private val rtcpSender: (RtcpPacket) -> Unit) : Node("FIR requester") {
    var numPacketSinceLastFir = 0
    var numFirsSent = 0
    var mediaSsrc: Long? = null
    // Every 300 packets, request an FIR
    override fun doProcessPackets(p: List<Packet>) {
        if (mediaSsrc == null) {
            mediaSsrc = (p.get(0) as RtpPacket).header.ssrc
        }
        numPacketSinceLastFir += p.size
        if (numPacketSinceLastFir >= 300) {
//            val firPacket = RtcpFbFirPacket(mediaSsrc!!, numFirsSent++)
            println("BRIAN sending fir packet for stream $mediaSsrc")
//            rtcpSender(firPacket)
            val pliPacket = RtcpFbPliPacket(mediaSsrc!!)
            rtcpSender(pliPacket)
            numPacketSinceLastFir = 0
        }

        next(p)
    }
}
