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
package org.jitsi.nlj.transform.module.incoming

import org.jitsi.nlj.srtp_og.RawPacket
import org.jitsi.nlj.transform.module.AbstractSrtpTransformerWrapper
import org.jitsi.nlj.transform_og.SinglePacketTransformer
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtcp.RtcpPacket
import java.nio.ByteBuffer

class SrtcpTransformerWrapperDecrypt : AbstractSrtpTransformerWrapper("SRTCP Decrypt wrapper") {
    override fun doTransform(pkts: List<Packet>, transformer: SinglePacketTransformer): List<Packet> {
        val decryptedRtcpPackets = mutableListOf<RtcpPacket>()
        pkts.forEach {
            val packetBuf = it.getBuffer()
            val rp = RawPacket(packetBuf.array(), 0, packetBuf.limit())
            transformer.reverseTransform(rp)?.let { decryptedRawPacket ->
                val rtcpPacket = RtcpPacket.fromBuffer(ByteBuffer.wrap(
                    decryptedRawPacket.buffer,
                    decryptedRawPacket.offset,
                    decryptedRawPacket.length))
                decryptedRtcpPackets.add(rtcpPacket)
            }
        }
        return decryptedRtcpPackets
    }
}
