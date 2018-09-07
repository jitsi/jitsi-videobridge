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

import org.jitsi.impl.neomedia.rtp.RawPacketCache
import org.jitsi.nlj.util.getByteBuffer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbNackPacket

/**
 * When a nack packet is received, the [NackHandler] will try to retrieve the
 * nacked packets from the cache and then send them to the RTX output pipeline.
 */
class NackHandler(
    private val packetCache: RawPacketCache,
    private val onNackedPacketsReady: PacketHandler
) {

    fun onNackPacket(nackPacket: RtcpFbNackPacket) {
        println("Nack handler processing nack")
        val nackedPackets = mutableListOf<Packet>()
        val ssrc = nackPacket.mediaSourceSsrc
        nackPacket.missingSeqNums.forEach { missingSeqNum ->
            println("Nack handler checking cache " + packetCache.hashCode() +
                    " for packet $ssrc $missingSeqNum in cache")
            val packet = packetCache.get(ssrc, missingSeqNum)
            if (packet != null) {
                nackedPackets.add(RtpPacket(packet.getByteBuffer()))
            }
        }
        if (nackedPackets.isNotEmpty()) {
            println("NackHandler found ${nackedPackets.size} that can be retransmitted")
            onNackedPacketsReady.processPackets(nackedPackets)
        }
    }
}
