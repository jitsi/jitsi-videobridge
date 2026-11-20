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
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

class RetransmissionSender(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger,
    /**
     * Whether an RTX packet gets its sequence number later, from [assignRtxSequenceNumber], and not when it passes
     * through this node. A pacer can change the order of the RTX packets of a stream, because a retransmission has
     * a higher priority than padding. The packets have to be numbered in the order in which they are sent, so with
     * a pacer they are numbered after it.
     */
    private val deferSequenceNumbers: Boolean = false
) : ModifierNode("Retransmission sender") {
    private val logger = createChildLogger(parentLogger)

    /**
     * Maps an original payload type (Int) to its [RtxPayloadType]
     */
    private val origPtToRtxPayloadType: MutableMap<Int, RtxPayloadType> = ConcurrentHashMap()

    /**
     * A map of rtx stream ssrc to the current sequence number for that stream. Retransmissions in answer to a NACK
     * and retransmissions for probing come on different threads.
     */
    private val rtxStreamSeqNums: MutableMap<Long, Int> = ConcurrentHashMap()

    private val numRetransmissionsRequested = LongAdder()
    private val numRetransmittedRtxPackets = LongAdder()
    private val numRetransmittedPlainPackets = LongAdder()

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
        numRetransmissionsRequested.increment()
        val rtxPt = origPtToRtxPayloadType[rtpPacket.payloadType.toPositiveInt()]
            ?: return retransmitPlain(packetInfo)
        val rtxSsrc = streamInformationStore.getRemoteSecondarySsrc(rtpPacket.ssrc, SsrcAssociationType.RTX)
            ?: return retransmitPlain(packetInfo)

        return retransmitRtx(packetInfo, rtxPt.pt.toPositiveInt(), rtxSsrc)
    }

    /**
     * The next sequence number of the RTX stream with the SSRC [rtxSsrc]. It starts at 1 and wraps at 16 bits.
     */
    private fun nextRtxSequenceNumber(rtxSsrc: Long): Int =
        rtxStreamSeqNums.merge(rtxSsrc, 1) { seqNum, increment -> (seqNum + increment) and 0xFFFF }!!

    /**
     * Gives an RTX packet its sequence number, if sequence numbers are deferred. Other packets are not changed. This
     * has to be called in the order in which the packets are sent.
     */
    fun assignRtxSequenceNumber(packetInfo: PacketInfo) {
        if (deferSequenceNumbers && packetInfo.originalSsrc != null) {
            val rtpPacket = packetInfo.packetAs<RtpPacket>()
            rtpPacket.sequenceNumber = nextRtxSequenceNumber(rtpPacket.ssrc)
            packetInfo.resetPayloadVerification()
        }
    }

    private fun retransmitRtx(packetInfo: PacketInfo, rtxPt: Int, rtxSsrc: Long): PacketInfo {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        logger.cdebug {
            "${hashCode()} sending RTX packet with ssrc $rtxSsrc with pt $rtxPt " +
                "with original ssrc ${rtpPacket.ssrc}, original sequence number " +
                "${rtpPacket.sequenceNumber} and original payload type: ${rtpPacket.payloadType}"
        }
        RtxPacket.addOriginalSequenceNumber(rtpPacket)
        packetInfo.originalSsrc = rtpPacket.ssrc
        rtpPacket.ssrc = rtxSsrc
        rtpPacket.payloadType = rtxPt
        if (!deferSequenceNumbers) {
            rtpPacket.sequenceNumber = nextRtxSequenceNumber(rtxSsrc)
        }

        packetInfo.resetPayloadVerification()
        numRetransmittedRtxPackets.increment()

        return packetInfo
    }

    private fun retransmitPlain(packetInfo: PacketInfo): PacketInfo {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        logger.cdebug {
            "${hashCode()} plain retransmission packet with original ssrc " +
                "${rtpPacket.ssrc}, original sequence number ${rtpPacket.sequenceNumber} and original " +
                "payload type: ${rtpPacket.payloadType}"
        }

        numRetransmittedPlainPackets.increment()
        // No work needed
        return packetInfo
    }

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply {
        addNumber("num_retransmissions_requested", numRetransmissionsRequested.sum())
        addNumber("num_retransmissions_rtx_sent", numRetransmittedRtxPackets.sum())
        addNumber("num_retransmissions_plain_sent", numRetransmittedPlainPackets.sum())
        addString(
            "rtx_payload_types(orig -> rtx)",
            this@RetransmissionSender.origPtToRtxPayloadType.toString()
        )
    }

    override fun statsJson() = super.statsJson().apply {
        put("num_retransmissions_requested", numRetransmissionsRequested.sum())
        put("num_retransmissions_rtx_sent", numRetransmittedRtxPackets.sum())
        put("num_retransmissions_plain_sent", numRetransmittedPlainPackets.sum())
    }

    override fun trace(f: () -> Unit) = f.invoke()
}
