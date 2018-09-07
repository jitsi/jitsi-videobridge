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

import org.jitsi.service.neomedia.RawPacket
import org.jitsi.nlj.transform.node.AbstractSrtpTransformerNode
import org.jitsi.impl.neomedia.transform.SinglePacketTransformer
import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.Packet
import org.jitsi.rtp.UnparsedPacket
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpPacket
import java.nio.ByteBuffer

class SrtcpTransformerDecryptNode : AbstractSrtpTransformerNode("SRTCP decrypt") {
    override fun doTransform(pkts: List<PacketInfo>, transformer: SinglePacketTransformer): List<PacketInfo> {
//        val decryptedRtcpPackets = mutableListOf<RtcpPacket>()
        val outPackets = mutableListOf<PacketInfo>()
        pkts.forEach {
            val packetBuf = it.packet.getBuffer()
            val rp = RawPacket(packetBuf.array(), 0, packetBuf.limit())
            transformer.reverseTransform(rp)?.let { decryptedRawPacket ->
//                println("Creating RTCP packet from decrypted buffer:\n" +
//                        ByteBuffer.wrap(decryptedRawPacket.buffer, decryptedRawPacket.offset, decryptedRawPacket.length).toHex())
//                val rtcpPacket = RtcpPacket.fromBuffer(
//                    ByteBuffer.wrap(
//                        decryptedRawPacket.buffer,
//                        decryptedRawPacket.offset,
//                        decryptedRawPacket.length
//                    )
//                )
                val packet = UnparsedPacket(
                    ByteBuffer.wrap(
                        decryptedRawPacket.buffer,
                        decryptedRawPacket.offset,
                        decryptedRawPacket.length
                    )
                )
//                decryptedRtcpPackets.add(rtcpPacket)
                it.packet = packet
                outPackets.add(it)
            }
        }
//        return decryptedRtcpPackets
        return outPackets
    }
}
