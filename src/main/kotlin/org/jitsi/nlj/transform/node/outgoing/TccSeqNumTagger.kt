/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.RtpExtensionType.TRANSPORT_CC
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.TccHeaderExtension

class TccSeqNumTagger(
    private val transportCcEngine: TransportCcEngine? = null,
    streamInformationStore: ReadOnlyStreamInformationStore
) : TransformerNode("TCC sequence number tagger") {
    private var currTccSeqNum: Int = 1
    private var tccExtensionId: Int? = null

    init {
        streamInformationStore.onRtpExtensionMapping(TRANSPORT_CC) {
            tccExtensionId = it
        }
    }

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        tccExtensionId?.let { tccExtId ->
            val rtpPacket = packetInfo.packetAs<RtpPacket>()
            val ext = rtpPacket.getHeaderExtension(tccExtId)
                ?: rtpPacket.addHeaderExtension(tccExtId, TccHeaderExtension.DATA_SIZE_BYTES)

            TccHeaderExtension.setSequenceNumber(ext, currTccSeqNum)
            transportCcEngine?.mediaPacketSent(currTccSeqNum, rtpPacket.length.bytes)
            currTccSeqNum++
        }

        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addString("tcc_ext_id", tccExtensionId.toString())
        }
    }
}
