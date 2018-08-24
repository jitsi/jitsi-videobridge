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
import org.jitsi.rtp.SrtcpPacket
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.rtcp.RtcpPacket
import java.nio.ByteBuffer

class SrtcpTransformerWrapperEncrypt : Module("SRTCP Encrypt wrapper") {
    var srtcpTransformer: SinglePacketTransformer? = null

    private var cachedPackets = mutableListOf<Packet>()
    override fun doProcessPackets(p: List<Packet>) {
        val outPackets = mutableListOf<Packet>()
        srtcpTransformer?.let { pktTransformer ->
            outPackets.addAll(encryptPackets(cachedPackets, pktTransformer))
            cachedPackets.clear()
            outPackets.addAll(encryptPackets(p, pktTransformer))
            if (outPackets.isNotEmpty()) {
                next(outPackets)
            }
        } ?: run {
            cachedPackets.addAll(p)
        }
    }

    private fun encryptPackets(packets: List<Packet>, pktTransformer: SinglePacketTransformer): List<SrtcpPacket> {
        val encryptedPackets = mutableListOf<SrtcpPacket>()
        packets.forEachAs<RtcpPacket> {
            doEncrypt(it, pktTransformer)?.let(encryptedPackets::add)
        }
        return encryptedPackets
    }

    private fun doEncrypt(rtcpPacket: RtcpPacket, transformer: SinglePacketTransformer): SrtcpPacket? {
//        println("BRIAN: decrypting rtcp packet.  packet length is ${rtcpPacket.getBuffer().limit()}, rtcp header length" +
//                " is ${rtcpPacket.header.length}")
        val packetBuf = rtcpPacket.getBuffer()
        val rp = RawPacket(packetBuf.array(), 0, packetBuf.limit())
//        println("BRIAN: encrypting ${RawPacket.getRTCPSSRC(rp)} rtcp packet with size ${rp.length} and buffer before decrypt: " +
//                packetBuf.toHex())
        val output = transformer.transform(rp) ?: return null
//        println("BRIAN: encrypted raw rtcp packet ${RawPacket.getRTCPSSRC(output)} ${output.sequenceNumber} now has size ${output.length} " +
//            "and buffer\n" + ByteBuffer.wrap(output.buffer, output.offset, output.length).toHex())
        try {
//            println("BRIAN: about to parse decrypted packet into RtcpPacket")
            val outPacket = SrtcpPacket(ByteBuffer.wrap(output.buffer, output.offset, output.length))
//            println("BRIAN: decrypted packet parsed as RtcpPacket ${outPacket.hashCode()} now has size ${outPacket.size} and buffer after decrypt: " +
//                "(size: ${outPacket.getBuffer().limit()}):\n" + outPacket.getBuffer().toHex())
            return outPacket
        } catch (e: Error) {
            println("BRIAN: exception parsing decrypted rtcp packet: $e")
            return null
        }
    }
}
