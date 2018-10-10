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
package org.jitsi.rtp

import io.kotlintest.specs.ShouldSpec
import io.pkts.Pcap
import io.pkts.packet.UDPPacket
import io.pkts.protocol.Protocol
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.util.RtpProtocol
import java.nio.ByteBuffer

//TODO: turn this into more of a real test?  or just have available as a 'tool' somewhere?
internal class PcapParsingTest : ShouldSpec() {
    init {
//        val pcap = Pcap.openStream("/Users/bbaldino/Downloads/chrome_flexfec_and_video_capture.pcap")
//
//        pcap.loop { pkt ->
//            if (pkt.hasProtocol(Protocol.UDP)) {
//                val udpPacket = pkt.getPacket(Protocol.UDP) as UDPPacket
//                val buf = ByteBuffer.wrap(udpPacket.payload.array)
//                var p: Packet? = null
//                if (RtpProtocol.isRtp(buf)) {
//                    p = RtpPacket(buf)
//                } else if (RtpProtocol.isRtcp(buf)) {
//                    p = RtcpPacket.fromBuffer(buf)
//                }
//                println(p)
//            }
//            true
//        }
    }
}
