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

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.SsrcAssociationEvent
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cerror
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.RtxPacket
import unsigned.toUInt
import java.util.concurrent.ConcurrentHashMap

class RetransmissionSender : Node("Retransmission sender") {
    /**
     * Maps the video payload types to their RTX payload types
     */
    private val associatedPayloadTypes: ConcurrentHashMap<Int, Int> = ConcurrentHashMap()
    /**
     * Map the original media ssrcs to their RTX stream ssrcs
     */
    private val associatedSsrcs: ConcurrentHashMap<Long, Long> = ConcurrentHashMap()

    /**
     * A map of rtx stream ssrc to the current sequence number for that stream
     */
    private val rtxStreamSeqNums: MutableMap<Long, Int> = mutableMapOf()

    private var numRetransmissionsRequested = 0
    private var numRetransmittedPackets = 0

    /**
     * Creates RTX packets from the given RTP packets and passes them down the pipeline
     */
    override fun doProcessPackets(p: List<PacketInfo>) {
        val outPackets = mutableListOf<PacketInfo>()
        p.forEachAs<RtpPacket> { packetInfo, pkt ->
            logger.cdebug { "Retransmission sender ${hashCode()} retransmitting packet with original ssrc " +
                    "${pkt.header.ssrc}, original sequence number ${pkt.header.sequenceNumber} and original " +
                    "payload type: ${pkt.header.payloadType}" }
            numRetransmissionsRequested++
            val rtxSsrc = associatedSsrcs[pkt.header.ssrc] ?: run {
                logger.cerror { "Retransmission sender ${hashCode()} could not find an associated RTX ssrc for original packet ssrc " +
                        pkt.header.ssrc }
                return@forEachAs
            }
            val rtxPt = associatedPayloadTypes[pkt.header.payloadType] ?: run {
                logger.cerror { "Retransmission sender ${hashCode()} could not find an associated RTX payload type for original payload type " +
                        pkt.header.payloadType }
                return@forEachAs
            }
            // Get a default value of 1 to start if it isn't present in the map.  If it is present
            // in the map, get the value and increment it by 1
            val rtxSeqNum = rtxStreamSeqNums.merge(rtxSsrc, 1, Integer::sum)!!

            val rtxPacket = RtxPacket.fromRtpPacket(pkt)
            rtxPacket.header.ssrc = rtxSsrc
            rtxPacket.header.payloadType = rtxPt
            rtxPacket.header.sequenceNumber = rtxSeqNum
            logger.cdebug { "Retransmission sender ${hashCode()} sending RTX packet with " +
                    "ssrc $rtxSsrc with pt $rtxPt and seqNum $rtxSeqNum" }
            packetInfo.packet = rtxPacket
            numRetransmittedPackets++

            outPackets.add(packetInfo)
        }

        next(outPackets)
    }

    override fun handleEvent(event: Event) {
        when(event) {
            is RtpPayloadTypeAddedEvent -> {
                if (event.payloadType is RtxPayloadType) {
                    val rtxPt = event.payloadType.pt.toUInt()
                    event.payloadType.parameters["apt"]?.toByte()?.toUInt()?.let {
                        val associatedPt = it
                        logger.cinfo { "Retransmission sender ${hashCode()} associating RTX payload type " +
                                "$rtxPt with primary $associatedPt" }
                        associatedPayloadTypes[associatedPt] = rtxPt
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
                    logger.cinfo { "Retransmission sender ${hashCode()} associating RTX ssrc " +
                            "${event.secondarySsrc} with primary ${event.primarySsrc}" }
                    associatedSsrcs[event.primarySsrc] = event.secondarySsrc
                }
            }
        }
        super.handleEvent(event)
    }

    override fun getNodeStats(): NodeStatsBlock {
        val parentStats = super.getNodeStats()
        return NodeStatsBlock(name).apply {
            addAll(parentStats)
            addStat("num retransmissions requested: $numRetransmissionsRequested")
            addStat("num retransmissions sent: $numRetransmittedPackets")
        }
    }
}
