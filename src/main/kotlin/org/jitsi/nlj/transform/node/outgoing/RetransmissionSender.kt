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
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.rtp.RtxPacket
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.ModifierNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.utils.logging2.cdebug
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.util.concurrent.ConcurrentHashMap

class RetransmissionSender(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : ModifierNode("Retransmission sender") {
    private val logger = createChildLogger(parentLogger)
    /**
     * Maps an original payload type (Int) to its [RtxPayloadType]
     */
    private val origPtToRtxPayloadType: MutableMap<Int, RtxPayloadType> = ConcurrentHashMap<Int, RtxPayloadType>()
    /**
     * A map of rtx stream ssrc to the current sequence number for that stream
     */
    private val rtxStreamSeqNums: MutableMap<Long, Int> = mutableMapOf()

    private var numRetransmissionsRequested = 0
    private var numRetransmittedRtxPackets = 0
    private var numRetransmittedPlainPackets = 0

    init {
        streamInformationStore.onRtpPayloadTypesChanged { currentRtpPayloadTypes ->
            origPtToRtxPayloadType.clear()
            currentRtpPayloadTypes.values
                .filterIsInstance<RtxPayloadType>()
                .map {
                    origPtToRtxPayloadType[it.associatedPayloadType] = it
                }
        }
    }

    /**
     * Transform an original RTP packet into an RTX-encapsulated form of that packet.
     */
    override fun modify(packetInfo: PacketInfo): PacketInfo {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        numRetransmissionsRequested++
        val rtxPt = origPtToRtxPayloadType[rtpPacket.payloadType.toPositiveInt()]
            ?: return retransmitPlain(packetInfo)
        val rtxSsrc = streamInformationStore.getRemoteSecondarySsrc(rtpPacket.ssrc, SsrcAssociationType.RTX)
            ?: return retransmitPlain(packetInfo)

        return retransmitRtx(packetInfo, rtxPt.pt.toPositiveInt(), rtxSsrc)
    }

    private fun retransmitRtx(packetInfo: PacketInfo, rtxPt: Int, rtxSsrc: Long): PacketInfo {
        // Get a default value of 1 to start if it isn't present in the map.  If it is present
        // in the map, get the value and increment it by 1
        val rtxSeqNum = rtxStreamSeqNums.merge(rtxSsrc, 1, Integer::sum)!!
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        logger.cdebug {
            "${hashCode()} sending RTX packet with ssrc $rtxSsrc with pt $rtxPt and seqNum " +
                    "$rtxSeqNum with original ssrc ${rtpPacket.ssrc}, original sequence number " +
                    "${rtpPacket.sequenceNumber} and original payload type: ${rtpPacket.payloadType}"
        }
        RtxPacket.addOriginalSequenceNumber(rtpPacket)
        rtpPacket.ssrc = rtxSsrc
        rtpPacket.payloadType = rtxPt
        rtpPacket.sequenceNumber = rtxSeqNum

        packetInfo.resetPayloadVerification()
        numRetransmittedRtxPackets++

        return packetInfo
    }

    private fun retransmitPlain(packetInfo: PacketInfo): PacketInfo {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        logger.cdebug { "${hashCode()} plain retransmission packet with original ssrc " +
                "${rtpPacket.ssrc}, original sequence number ${rtpPacket.sequenceNumber} and original " +
                "payload type: ${rtpPacket.payloadType}" }

        numRetransmittedPlainPackets++
        // No work needed
        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_retransmissions_requested", numRetransmissionsRequested)
            addNumber("num_retransmissions_rtx_sent", numRetransmittedRtxPackets)
            addNumber("num_retransmissions_plain_sent", numRetransmittedPlainPackets)
            addString(
                "rtx_payload_types(orig -> rtx)", this@RetransmissionSender.origPtToRtxPayloadType.toString()
            )
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
