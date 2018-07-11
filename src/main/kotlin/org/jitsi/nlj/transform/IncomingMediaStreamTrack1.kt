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
package org.jitsi.nlj.transform

import org.jitsi.nlj.IncomingMediaStreamTrack
import org.jitsi.rtp.Packet
import kotlin.system.measureNanoTime

/**
 * The thinking is that this will represent an incoming
 * media stream where stream is defined as the data
 * associated with a webrtc MediaStreamTrack (e.g. multiple
 * simulcast streams/ssrcs would be associated with the
 * same track)
 * TODO: do i have the above definition right?
 *
 * For now I am naming it specifically around 'Incoming',
 * but we'll see if it makes sense to combine it with
 * outgoing logic down the line
 */
class IncomingMediaStreamTrack1 : IncomingMediaStreamTrack {
    val incomingTransformChain: List<Transformer>
    init {
        val packetStats = PacketStats()
        val fecHandler = FecHandler()
        fecHandler.subscribe(packetStats::onRecoveredPacket)

        val rtpPath = listOf(
            PacketLossMonitor(),
            fecHandler,
            RtpHandler()
        )
        val rtcpPath = listOf(
            RtcpHandler()
        )
        incomingTransformChain = listOf(
            packetStats,
            SrtpTransformer(),
            RtpRtcpSplitter(rtpPath, rtcpPath)
        )
    }

    override fun processPackets(pkts: List<Packet>) {
        val time = measureNanoTime {
            incomingTransformChain.forEach { it.processPackets(pkts)}
        }
        println("Entire transform chain took $time nanos")
    }

    override fun getStats(): String {
        return with(StringBuffer()) {
            incomingTransformChain.forEach {
                append(it.getStats())
            }
            toString()
        }
    }
}

