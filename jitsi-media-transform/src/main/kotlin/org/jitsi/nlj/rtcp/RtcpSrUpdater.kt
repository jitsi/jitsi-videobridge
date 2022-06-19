/*
 * Copyright @ 2019 - Present, 8x8 Inc
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
package org.jitsi.nlj.rtcp

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.transform.node.outgoing.OutgoingStatisticsTracker
import org.jitsi.rtp.rtcp.RtcpSrPacket

/**
 * Updates RTCP Sender Reports with the current octet and packet count.
 */
class RtcpSrUpdater(
    val statsTracker: OutgoingStatisticsTracker
) : TransformerNode("RtcpSrUpdater") {

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        // TODO support compound packets
        val rtcpSrPacket = packetInfo.packet as? RtcpSrPacket ?: return packetInfo

        // If we haven't sent RTP for this SSRC, drop the SR
        val ssrcStats = statsTracker.getSsrcSnapshot(rtcpSrPacket.senderSsrc) ?: return null

        rtcpSrPacket.senderInfo.sendersOctetCount = ssrcStats.octetCount.toLong()
        rtcpSrPacket.senderInfo.sendersPacketCount = ssrcStats.packetCount.toLong()

        return packetInfo
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
