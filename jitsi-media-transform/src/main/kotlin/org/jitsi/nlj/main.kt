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
package org.jitsi.nlj

import org.jitsi.nlj.transform.IncomingMediaStreamTrack1
import org.jitsi.nlj.transform2.IncomingMediaStreamTrack2
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpHeader
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpSrPacket
import java.nio.ByteBuffer
import java.util.*

class PacketGenerator {
    private var currSequenceNumber = 1
    private var lastRtcpSequenceNumber = 1

    fun generatePacket(): Packet {
        if (Random().nextInt(100) > 90) {
            // Simulate loss
            currSequenceNumber++
        }
        return if (currSequenceNumber - lastRtcpSequenceNumber > 10) {
            lastRtcpSequenceNumber = currSequenceNumber
            RtpPacket.fromValues {
                header = RtpHeader.fromValues {
                    version = 2
                    hasPadding = false
                    hasExtension = false
                    csrcCount = 3
                    marker = true
                    payloadType = 96
                    sequenceNumber = currSequenceNumber
                    timestamp = 98765
                    ssrc = 1234567
                    csrcs = listOf<Long>(1, 2, 3)
                    extensions = mapOf()
                }
                payload = ByteBuffer.allocate(50)
            }
        } else {
            RtcpSrPacket.fromValues {
                header = RtcpHeader.fromValues {
                    version = 2
                    hasPadding = false
                    reportCount = 2
                    payloadType = 200
                    length = 42
                    senderSsrc = 12345
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    val pg = PacketGenerator()
    val packets = mutableListOf<Packet>()
    repeat(20) {
        packets.add(pg.generatePacket())
    }

    val stream1 = IncomingMediaStreamTrack1()
    val stream2 = IncomingMediaStreamTrack2()
    // IncomingMediaStreamTrack currently implements the following simulated packet pipeline:
    //
    //                                             RTP   / --> Packet loss monitor --> RTP handler
    //                                                  /
    // --> Packet Stats --> SRTP --> RTP/RTCP splitter
    //                                                  \
    //                                              RTCP \ --> RTCP handler

    packets.forEach {
        stream2.processPackets(listOf(it))
    }

    println(stream2.getStats())
}
