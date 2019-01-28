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

import org.jitsi.impl.neomedia.transform.SinglePacketTransformer
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.AbstractSrtpTransformerNode
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.toRawPacket
import org.jitsi.rtp.RtpPacket
import org.jitsi.rtp.SrtpPacket
import org.jitsi.rtp.util.ByteBufferUtils

class SrtpTransformerEncryptNode : AbstractSrtpTransformerNode("SRTP Encrypt wrapper") {
    private var numEncryptFailures = 0
    override fun doTransform(pkts: List<PacketInfo>, transformer: SinglePacketTransformer): List<PacketInfo> {
        pkts.forEach {
            val rp = it.packet.toRawPacket()
            transformer.transform(rp)?.let { encryptedRawPacket ->
                val srtpPacket = SrtpPacket(
                    ByteBufferUtils.wrapSubArray(
                        encryptedRawPacket.buffer,
                        encryptedRawPacket.offset,
                        encryptedRawPacket.length
                    )
                )
                // Change the PacketInfo to contain the new packet
                it.packet = srtpPacket
            } ?: run {
                logger.cerror { "SRTP encryption failed for packet ${it.packetAs<RtpPacket>().header.ssrc} ${it.packetAs<RtpPacket>().header.sequenceNumber}" }
                numEncryptFailures++
            }
        }
        return pkts
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num encrypt failures: $numEncryptFailures")
        }
    }
}
