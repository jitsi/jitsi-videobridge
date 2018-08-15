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

import org.jitsi.nlj.srtp_og.SinglePacketTransformer
import org.jitsi.nlj.transform.module.PacketHandler
import org.jitsi.rtp.Packet


/**
 * Not an 'RtpSender' in the sense that it sends only RTP (and not
 * RTCP) but in the sense of a webrtc 'RTCRTPSender' which handles
 * all RTP and RTP control packets.
 */
abstract class RtpSender {
    var numPacketsSent = 0
    var numBytesSent: Long = 0
    var firstPacketSentTime: Long = -1
    var lastPacketSentTime: Long = -1
    var packetSender: PacketHandler = {
        if (firstPacketSentTime == -1L) {
            firstPacketSentTime = System.currentTimeMillis()
        }
        numPacketsSent += it.size
        it.forEach { pkt -> numBytesSent += pkt.size }
        lastPacketSentTime = System.currentTimeMillis()
    }
    abstract fun sendPackets(pkts: List<Packet>)
    abstract fun getStats(): String
    abstract fun setSrtpTransformer(srtpTransformer: SinglePacketTransformer)
}
