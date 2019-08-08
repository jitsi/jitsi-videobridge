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

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SsrcAssociationEvent
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.RtxPacket
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.nlj.util.StreamInformationStore
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cerror
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Handle incoming RTX packets to strip the RTX information and make them
 * look like their original packets.
 * https://tools.ietf.org/html/rfc4588
 */
class RtxHandler(
    streamInformationStore: ReadOnlyStreamInformationStore
) : TransformerNode("RTX handler") {
    private var numPaddingPacketsReceived = 0
    private var numRtxPacketsReceived = 0
    /**
     * Maps the RTX payload types to their associated video payload types
     */
    private val associatedPayloadTypes: ConcurrentHashMap<Int, Int> = ConcurrentHashMap()
    /**
     * Map the RTX stream ssrcs to their corresponding media ssrcs
     */
    private val associatedSsrcs: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()

    init {
        streamInformationStore.onRtpPayloadTypesChanged { currentPayloadTypes ->
            if (currentPayloadTypes.isEmpty()) {
                associatedPayloadTypes.clear()
            } else {
                currentPayloadTypes.values.filterIsInstance<RtxPayloadType>()
                    .map { rtxPayloadType ->
                        rtxPayloadType.associatedPayloadType?.let { associatedPayloadType ->
                            associatedPayloadTypes[rtxPayloadType.pt.toInt()] = associatedPayloadType
                            logger.cdebug { "Associating RTX payload type ${rtxPayloadType.pt.toInt()} " +
                                "with primary $associatedPayloadType" }
                        } ?: run {
                            logger.cerror { "Unable to parse RTX associated payload type from payload " +
                                "type $rtxPayloadType" }
                        }
                    }
            }
        }
    }

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        if (associatedPayloadTypes.containsKey(rtpPacket.payloadType.toPositiveInt()) &&
            associatedSsrcs.containsKey(rtpPacket.ssrc)) {
//          logger.cdebug {
//             "Received RTX packet: ssrc ${rtxPacket.header.ssrc}, seq num: ${rtxPacket.header.sequenceNumber} " +
//             "rtx payload size: ${rtxPacket.payload.limit()}, padding size: ${rtxPacket.getPaddingSize()} " +
//             "buffer:\n${rtxPacket.getBuffer().toHex()}" }
            if (rtpPacket.payloadLength - rtpPacket.paddingSize < 2) {
                logger.cdebug { "RTX packet is padding, ignore" }
                numPaddingPacketsReceived++
                packetDiscarded(packetInfo)
                return null
            }

            val originalSeqNum = RtxPacket.getOriginalSequenceNumber(rtpPacket)
            val originalPt = associatedPayloadTypes[rtpPacket.payloadType.toPositiveInt()]!!
            val originalSsrc = associatedSsrcs[rtpPacket.ssrc]!!

            // Move the payload 2 bytes to the left
            RtxPacket.removeOriginalSequenceNumber(rtpPacket)
            rtpPacket.sequenceNumber = originalSeqNum
            rtpPacket.payloadType = originalPt
            rtpPacket.ssrc = originalSsrc

            logger.cdebug { "Recovered RTX packet.  Original packet: $originalSsrc $originalSeqNum" }
            numRtxPacketsReceived++
            packetInfo.resetPayloadVerification()
            return packetInfo
        } else {
            return packetInfo
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SsrcAssociationEvent -> {
                if (event.type == SsrcAssociationType.RTX) {
                    logger.cdebug { "Associating RTX ssrc ${event.secondarySsrc} with primary ${event.primarySsrc}" }
                    associatedSsrcs[event.secondarySsrc] = event.primarySsrc
                }
            }
        }
        super.handleEvent(event)
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_rtx_packets_received", numRtxPacketsReceived)
            addNumber("num_padding_packets_received", numPaddingPacketsReceived)
            addString("rtx_payload_type_associations(rtx -> orig)", associatedPayloadTypes.toString())
            addString("rtx_ssrc_associations(rtx -> orig)", associatedSsrcs.toString())
        }
    }

    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }
}
