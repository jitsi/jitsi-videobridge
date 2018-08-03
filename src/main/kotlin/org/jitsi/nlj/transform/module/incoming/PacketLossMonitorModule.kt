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
package org.jitsi.nlj.transform.module.incoming

import org.jitsi.nlj.transform.module.Module
import org.jitsi.nlj.transform.module.forEachAs
import org.jitsi.nlj.util.appendLnIndent
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket

class PacketLossMonitorModule : Module("Packet loss monitor") {
    var lastSeqNumSeen: Int? = null
    var lostPackets = 0

    override fun doProcessPackets(p: List<Packet>) {
        if (debug) {
            println("Packet loss monitor")
        }
        p.forEachAs<RtpPacket> { pkt ->
            lastSeqNumSeen?.let {
                if (pkt.header.sequenceNumber > it + 1) {
                    if (debug) {
                        println("lost packets ${(it + 1 until pkt.header.sequenceNumber).joinToString(" ")} for stream ${pkt.header.ssrc}")
                    }
                    lostPackets += (pkt.header.sequenceNumber - it - 1)
                }
            }
            lastSeqNumSeen = pkt.header.sequenceNumber
        }
        next(p)
    }


    override fun getStats(indent: Int): String {
        return with (StringBuffer()) {
            append(super.getStats(indent))
            appendLnIndent(indent + 2, "lost packets: $lostPackets")
            toString()
        }
    }
}
