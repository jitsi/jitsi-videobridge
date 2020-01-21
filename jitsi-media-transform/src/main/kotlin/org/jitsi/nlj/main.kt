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
package org.jitsi.nlj

fun main(args: Array<String>) {

    /*
    val pg = PacketGenerator()
    val packets = mutableListOf<Packet>()
    repeat(20) {
        packets.add(pg.generatePacket())
    }

    val stream1 = IncomingMediaStreamTrack1()
    val stream2 = RtpReceiverImpl()
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

    println(stream2.getNodeStats())



    println("Outgoing")
    val outgoingStream2 = RtpSenderImpl()
    packets.forEach {
        if (it is RtpPacket) {
            outgoingStream2.outgoingRtpChain.processPackets(listOf(it))

        } else if (it is RtcpPacket) {
            outgoingStream2.outgoingRtcpChain.processPackets(listOf(it))
        }
    }
    */
}
