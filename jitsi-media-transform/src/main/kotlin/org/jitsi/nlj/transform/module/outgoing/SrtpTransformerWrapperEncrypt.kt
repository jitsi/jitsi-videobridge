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

class SrtpTransformerWrapperEncrypt : Module("SRTP encrypt wrapper") {
    var srtpTransformer: SinglePacketTransformer? = null
    private var cachedPackets = mutableListOf<Packet>()
    override fun doProcessPackets(p: List<Packet>) {
        println("BRIAN: encrypt wrapper got ${p.size} packets.  srtpTransformer? $srtpTransformer")
        val outPackets = mutableListOf<SrtpPacket>()

        if (cachedPackets.isNotEmpty() && srtpTransformer != null) {
            cachedPackets.forEachAs<RtpPacket> {
                val rtpPacket = doEncrypt(it) ?: return@forEachAs
                outPackets.add(rtpPacket)
            }
            cachedPackets.clear()
        }

        if (srtpTransformer == null) {
            cachedPackets.addAll(p)
            return
        }
        p.forEachAs<RtpPacket> {
            val rtpPacket = doEncrypt(it) ?: return@forEachAs
            outPackets.add(rtpPacket)
        }
        next(outPackets)
    }

    private fun doEncrypt(srtpPacket: RtpPacket): SrtpPacket? {
        val rp = RawPacket(srtpPacket.buf.array(), 0, srtpPacket.buf.array().size)
//        println("BRIAN: encrypting ${rp.ssrcAsLong} ${rp.sequenceNumber} packet with size ${rp.length}")
        val output = srtpTransformer?.transform(rp) ?: return null
//        println("BRIAN: encrypted packet ${output.ssrcAsLong} ${output.sequenceNumber} now has size ${output.length}")
        val outPacket = SrtpPacket(ByteBuffer.wrap(output.buffer, output.offset, output.length))
//        println("BRIAN: encrypted packet parsed as SrtpPacket ${outPacket.header.ssrc} ${outPacket.header.sequenceNumber} now has size ${outPacket.size}")

        return outPacket
    }
}
