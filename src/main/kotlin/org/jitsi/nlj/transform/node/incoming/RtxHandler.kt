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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.SsrcAssociationEvent
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.RtxPacket
import org.jitsi.util.Logger
import unsigned.toUInt
import java.util.concurrent.ConcurrentHashMap

/**
 * Handle incoming RTX packets to strip the RTX information and make them
 * look like their original packets.
 * https://tools.ietf.org/html/rfc4588
 */
class RtxHandler : TransformerNode("RTX handler") {
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

    companion object {
        val logger: Logger = Logger.getLogger(this::class.java)
    }

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        if (associatedPayloadTypes.containsKey(rtpPacket.header.payloadType)) {
            val rtxPacket = RtxPacket.parseAsRtx(rtpPacket)
//          logger.cdebug {
//             "Received RTX packet: ssrc ${rtxPacket.header.ssrc}, seq num: ${rtxPacket.header.sequenceNumber} " +
//             "rtx payload size: ${rtxPacket.payload.limit()}, padding size: ${rtxPacket.getPaddingSize()} " +
//             "buffer:\n${rtxPacket.getBuffer().toHex()}" }
            if (rtxPacket.payload.limit() - rtxPacket.paddingSize < 2) {
                logger.cdebug { "RTX packet is padding, ignore" }
                numPaddingPacketsReceived++
                return null
            }

            val originalSeqNum = rtxPacket.originalSequenceNumber
            val originalPt = associatedPayloadTypes[rtpPacket.header.payloadType]!!
            val originalSsrc = associatedSsrcs[rtpPacket.header.ssrc]!!

            val originalPacket = rtxPacket as RtpPacket
            originalPacket.header.sequenceNumber = originalSeqNum
            originalPacket.header.payloadType = originalPt
            originalPacket.header.ssrc = originalSsrc
            logger.cdebug { "Recovered RTX packet.  Original packet: $originalSsrc $originalSeqNum" }
            numRtxPacketsReceived++
            packetInfo.packet = originalPacket
            return packetInfo
        } else {
            return packetInfo
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                if (event.payloadType is RtxPayloadType) {
                    val rtxPt = event.payloadType.pt.toUInt()
                    event.payloadType.parameters["apt"]?.toByte()?.toUInt()?.let {
                        val associatedPt = it
                        logger.cinfo { "RtxHandler associating RTX payload type $rtxPt with primary $associatedPt" }
                        associatedPayloadTypes[rtxPt] = associatedPt
                    } ?: run {
                        logger.cerror { "Unable to parse RTX associated payload type from event: $event" }
                    }
                }
            }
            is RtpPayloadTypeClearEvent -> {
                associatedPayloadTypes.clear()
            }
            is SsrcAssociationEvent -> {
                if (event.type == SsrcAssociationType.RTX) {
                    logger.cinfo { "RtxHandler associating RTX ssrc ${event.secondarySsrc} with primary ${event.primarySsrc}" }
                    associatedSsrcs[event.secondarySsrc] = event.primarySsrc
                }
            }
        }
        super.handleEvent(event)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num rtx packets received: $numRtxPacketsReceived")
            addStat("num padding packets received: $numPaddingPacketsReceived")
        }
    }
}
