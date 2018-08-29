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

import org.jitsi.nlj.srtp_og.RawPacket
import org.jitsi.nlj.transform.node.AbstractSrtpTransformerNode
import org.jitsi.nlj.transform_og.SinglePacketTransformer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.SrtpPacket
import java.nio.ByteBuffer

class SrtpTransformerEncryptNode : AbstractSrtpTransformerNode("SRTP Encrypt wrapper") {
    override fun doTransform(pkts: List<Packet>, transformer: SinglePacketTransformer): List<Packet> {
        val encryptedPackets = mutableListOf<SrtpPacket>()
        pkts.forEach {
            val packetBuf = it.getBuffer()
            val rp = RawPacket(packetBuf.array(), packetBuf.arrayOffset(), packetBuf.limit())
            transformer.transform(rp)?.let { encryptedRawPacket ->
                val srtpPacket = SrtpPacket(
                    ByteBuffer.wrap(
                        encryptedRawPacket.buffer,
                        encryptedRawPacket.offset,
                        encryptedRawPacket.length))
                encryptedPackets.add(srtpPacket)
            }
        }
        return encryptedPackets
    }
}
