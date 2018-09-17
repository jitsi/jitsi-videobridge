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
package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.nlj.transform.node.AbstractSrtpTransformerNode
import org.jitsi.impl.neomedia.transform.SinglePacketTransformer
import org.jitsi.nlj.PacketInfo
import org.jitsi.rtp.SrtcpPacket
import org.jitsi.service.neomedia.RawPacket
import java.nio.ByteBuffer

class SrtcpTransformerEncryptNode : AbstractSrtpTransformerNode("SRTCP Encrypt wrapper") {
    override fun doTransform(pkts: List<PacketInfo>, transformer: SinglePacketTransformer): List<PacketInfo> {
        val encryptedPackets = mutableListOf<PacketInfo>()
        pkts.forEach {
            val packetBuf = it.packet.getBuffer()
            //TODO: if this rtcp packet was from a compound rtcp packet, the array backing
            // the packetBuf will have previous compound packets in it.  Although we pass
            // the proper offset as packetBuf.arrayOffset, not all methods in the transformer
            // properly take that offset into account.  for now, we'll make a new copy of the
            // buffer.  in the future we should clean up the transformer methods to take
            // the offset into account correctly
            val bufCopy = ByteBuffer.allocate(packetBuf.limit())
            bufCopy.put(packetBuf).flip()
            val rp = RawPacket(bufCopy.array(), bufCopy.arrayOffset(), bufCopy.limit())
            transformer.transform(rp)?.let { encryptedRawPacket ->
                val srtcpPacket = SrtcpPacket(
                    ByteBuffer.wrap(
                        encryptedRawPacket.buffer,
                        encryptedRawPacket.offset,
                        encryptedRawPacket.length))
                it.packet = srtcpPacket
                encryptedPackets.add(it)
            }
        }
        return encryptedPackets
    }
}
