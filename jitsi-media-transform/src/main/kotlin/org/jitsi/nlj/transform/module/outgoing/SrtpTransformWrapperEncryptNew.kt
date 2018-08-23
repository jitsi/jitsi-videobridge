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
package org.jitsi.nlj.transform.module.outgoing

import org.jitsi.nlj.srtp_og.RawPacket
import org.jitsi.nlj.srtp_og.SinglePacketTransformer
import org.jitsi.nlj.transform.module.Module
import org.jitsi.nlj.transform.module.forEachAs
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.SrtpPacket
import java.nio.ByteBuffer

//TODO: having replay issues
class SrtpTransformerWrapperEncryptNew : Module("SRTP encrypt wrapper") {
    var srtpTransformer: SinglePacketTransformer? = null
    private var cachedPackets = mutableListOf<Packet>()
    override fun doProcessPackets(p: List<Packet>) {
        val outPackets = mutableListOf<SrtpPacket>()

        srtpTransformer?.let { pktTransformer ->
            outPackets.addAll(encryptPackets(cachedPackets, pktTransformer))
            outPackets.addAll(encryptPackets(p, pktTransformer))
            next(outPackets)
        } ?: run {
            cachedPackets.addAll(p)
        }
    }

    private fun encryptPackets(packets: List<Packet>, pktTransformer: SinglePacketTransformer): List<SrtpPacket> {
        val encryptedPackets = mutableListOf<SrtpPacket>()
        packets.forEachAs<RtpPacket> {
            val encPacket = doEncrypt(it, pktTransformer)
            if (encPacket != null) {
                encryptedPackets.add(encPacket)
            }
        }
        return encryptedPackets
    }

    private fun doEncrypt(rtpPacket: RtpPacket, transformer: SinglePacketTransformer): SrtpPacket? {
        val packetBuf = rtpPacket.getBuffer()
        val rp = RawPacket(packetBuf.array(), 0, packetBuf.limit())
        val output = srtpTransformer?.transform(rp) ?: return null
        return SrtpPacket(ByteBuffer.wrap(output.buffer, output.offset, output.length))
    }
}
