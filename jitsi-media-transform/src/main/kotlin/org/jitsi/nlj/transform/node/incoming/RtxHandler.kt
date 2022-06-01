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
package org.jitsi.nlj.transform.node.incoming

import java.util.concurrent.ConcurrentHashMap
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.RtxPacket
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.utils.logging2.cdebug
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Handle incoming RTX packets to strip the RTX information and make them
 * look like their original packets.
 * https://tools.ietf.org/html/rfc4588
 */
class RtxHandler(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : TransformerNode("RTX handler") {
    private val logger = createChildLogger(parentLogger)
    private var numPaddingPacketsReceived = 0
    private var numRtxPacketsReceived = 0
    /**
     * Maps the Integer payload type of RTX to the [RtxPayloadType] instance.  We do this
     * so we can look up the associated (original) payload type from the [RtxPayloadType]
     * instance quickly via the Int RTX payload type in an incoming RTX packet.
     */
    private val rtxPtToRtxPayloadType = ConcurrentHashMap<Int, RtxPayloadType>()

    init {
        streamInformationStore.onRtpPayloadTypesChanged { currentPayloadTypes ->
            rtxPtToRtxPayloadType.clear()
            currentPayloadTypes.values
                .filterIsInstance<RtxPayloadType>()
                .map {
                    rtxPtToRtxPayloadType[it.pt.toPositiveInt()] = it
                }
        }
    }

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        val rtxPayloadType = rtxPtToRtxPayloadType[rtpPacket.payloadType.toPositiveInt()] ?: return packetInfo
        val originalSsrc = streamInformationStore.getLocalPrimarySsrc(rtpPacket.ssrc) ?: return packetInfo
        if (packetInfo.shouldDiscard) {
            /* Don't try to interpret an rtx [shouldDiscard] packet - SrtpTransformer may have skipped decrypting
             * it, which means its originalSeqNum is gibberish.
             * This doesn't need to wait until [DiscardableDiscarder], because we don't care about continuity of
             * RTX sequence numbers.
             */
            return null
        }
        // We do this check only after verifying we determine it's an RTX packet by finding
        // the associated payload type and SSRC above
        if (rtpPacket.payloadLength - rtpPacket.paddingSize < 2) {
            logger.cdebug { "RTX packet is padding, ignore" }
            numPaddingPacketsReceived++
            return null
        }
        val originalSeqNum = RtxPacket.getOriginalSequenceNumber(rtpPacket)
        val originalPt = rtxPayloadType.associatedPayloadType

        // Move the payload 2 bytes to the left
        RtxPacket.removeOriginalSequenceNumber(rtpPacket)
        rtpPacket.sequenceNumber = originalSeqNum
        rtpPacket.payloadType = originalPt
        rtpPacket.ssrc = originalSsrc

        logger.cdebug { "Recovered RTX packet.  Original packet: $originalSsrc $originalSeqNum" }
        numRtxPacketsReceived++
        packetInfo.resetPayloadVerification()
        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_rtx_packets_received", numRtxPacketsReceived)
            addNumber("num_padding_packets_received", numPaddingPacketsReceived)
            addString("rtx_payload_types", rtxPtToRtxPayloadType.values.toString())
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
