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
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.RtxPacket
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cerror
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpPacket
import unsigned.toUInt
import java.util.concurrent.ConcurrentHashMap

class RetransmissionSender : TransformerNode("Retransmission sender") {
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
    private var numRetransmittedRtxPackets = 0
    private var numRetransmittedPlainPackets = 0

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        numRetransmissionsRequested++
        var rtxRetransmission = false
        val rtxPt = associatedPayloadTypes[rtpPacket.payloadType.toPositiveInt()]
        // note(george) this instance gets notified about both remote/local ssrcs (see Transeiver.addSsrcAssociation)
        // so, in the case of firefox, we end up having rtx (associated) ssrcs but no rtx (associated) payload type.
        if (rtxPt != null) {
            val rtxSsrc = associatedSsrcs[rtpPacket.ssrc]
            if (rtxSsrc != null) {
                // Get a default value of 1 to start if it isn't present in the map.  If it is present
                // in the map, get the value and increment it by 1
                val rtxSeqNum = rtxStreamSeqNums.merge(rtxSsrc, 1, Integer::sum)!!

                RtxPacket.addOriginalSequenceNumber(rtpPacket)

                rtpPacket.ssrc = rtxSsrc
                rtpPacket.payloadType = rtxPt
                rtpPacket.sequenceNumber = rtxSeqNum
                logger.cdebug {
                    "${hashCode()} sending RTX packet with ssrc $rtxSsrc with pt $rtxPt and seqNum " +
                            "$rtxSeqNum with original ssrc ${rtpPacket.ssrc}, original sequence number " +
                            "${rtpPacket.sequenceNumber} and original payload type: ${rtpPacket.payloadType}"
                }
                packetInfo.resetPayloadVerification()
                numRetransmittedRtxPackets++
                rtxRetransmission = true
            }
        }

        if (!rtxRetransmission) {
            logger.cdebug { "${hashCode()} plain retransmission packet with original ssrc " +
                    "${rtpPacket.ssrc}, original sequence number ${rtpPacket.sequenceNumber} and original " +
                    "payload type: ${rtpPacket.payloadType}" }

            numRetransmittedPlainPackets++
        }

        return packetInfo
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                if (event.payloadType is RtxPayloadType) {
                    val rtxPt = event.payloadType.pt.toUInt()
                    event.payloadType.parameters["apt"]?.toByte()?.toUInt()?.let {
                        val associatedPt = it
                        logger.cdebug { "${hashCode()} associating RTX payload type " +
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
                    logger.cdebug { "${hashCode()} associating RTX ssrc " +
                            "${event.secondarySsrc} with primary ${event.primarySsrc}" }
                    associatedSsrcs[event.primarySsrc] = event.secondarySsrc
                }
            }
        }
        super.handleEvent(event)
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_retransmissions_requested", numRetransmissionsRequested)
            addNumber("num_retransmissions_rtx_sent", numRetransmittedRtxPackets)
            addNumber("num_retransmissions_plain_sent", numRetransmittedPlainPackets)
        }
    }
}
