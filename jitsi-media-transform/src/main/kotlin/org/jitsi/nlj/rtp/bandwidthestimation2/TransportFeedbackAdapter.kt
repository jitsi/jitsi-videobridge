/*
 * Copyright @ 2019 - present 8x8, Inc.
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
@file:Suppress("ktlint:standard:enum-entry-name-case")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.PacketOrigin
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.RtpSequenceIndexTracker
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.ReceivedPacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.ReceivedPacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.TimeUtils
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.max
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant
import java.util.*

private val kSendTimeHistoryWindow = 60.secs

/** Transport feedback adapter,
 * based loosely on WebRTC modules/congestion_controller/rtp/transport_feedback_adapter.{h,cc} in
 * WebRTC tag branch-heads/6613 (Chromium 128)
 * modified to use Jitsi types for objects outside the congestion controller,
 * and not using Network Routes.
 */
private data class PacketFeedback(
    // Time corresponding to when this object was created.
    val creationTime: Instant = Instant.MIN,
    val sent: SentPacket,
    val ssrc: Long = 0,
    val rtpSequenceNumber: Int = 0,
    val isRetransmission: Boolean = false
) {
    // Jitsi extension: whether the packet was previously reported lost
    var reportedLost: Boolean = false
}

private class InFlightBytesTracker {
    fun addInFlightPacketBytes(packet: PacketFeedback) {
        require(packet.sent.sendTime.isFinite())
        inFlightData += packet.sent.size
    }

    fun removeInFlightPacketBytes(packet: PacketFeedback) {
        if (packet.sent.sendTime.isInfinite()) {
            return
        }
        check(packet.sent.size <= inFlightData)
        inFlightData -= packet.sent.size
    }

    fun getOutstandingData(): DataSize = inFlightData

    var inFlightData: DataSize = DataSize.ZERO
}

/** TransportFeedbackAdapter converts RTCP feedback packets to RTCP agnostic per
 * packet send/receive information.
 * It supports [RtcpFbCcfbPacket] according to RFC 8888 and
 * [RtcpFbTccPacket] according to
 * https://datatracker.ietf.org/doc/html/draft-holmer-rmcat-transport-wide-cc-extensions-01
 */
class TransportFeedbackAdapter(
    parentLogger: Logger
) {
    val logger = parentLogger.createChildLogger(javaClass.name)

    fun addPacket(packet: PacketInfo, tccSeqNum: Long, overheadBytes: DataSize, creationTime: Instant) {
        val rtpPacket = packet.packetAs<RtpPacket>()
        // Update seqNumUnwrapper's notion of the ROC, and verify that it's in sync
        val truncatedSeqNum = (tccSeqNum and 0xFFFF).toInt()
        val unwrappedSeqNum = seqNumUnwrapper.update(truncatedSeqNum)
        check(unwrappedSeqNum == tccSeqNum)
        val feedback = PacketFeedback(
            creationTime = creationTime,
            sent = SentPacket(
                sequenceNumber = tccSeqNum,
                size = packet.packet.length.bytes + overheadBytes,
                pacingInfo = packet.probingInfo as? PacedPacketInfo ?: PacedPacketInfo()
            ),
            ssrc = rtpPacket.ssrc,
            rtpSequenceNumber = rtpPacket.sequenceNumber,
            isRetransmission = packet.packetOrigin == PacketOrigin.Retransmission
        )
        while (history.isNotEmpty() &&
            Duration.between(history.firstEntry().value.creationTime, creationTime) > kSendTimeHistoryWindow
        ) {
            // TODO(sprang): Warn if erasing (too many) old items?
            if (history.firstEntry().value.sent.sequenceNumber > lastAckSeqNum) {
                inFlight.removeInFlightPacketBytes(history.firstEntry().value)
            }
            val packet = history.firstEntry().value
            rtpToTransportSequenceNumber.remove(
                SsrcAndRtpSequenceNumber(packet.ssrc, packet.rtpSequenceNumber)
            )
            history.remove(history.firstEntry().key)
        }
        // Note that it can happen that the same SSRC and sequence number is sent
        // again. e.g, audio retransmission.
        rtpToTransportSequenceNumber[SsrcAndRtpSequenceNumber(feedback.ssrc, feedback.rtpSequenceNumber)] =
            feedback.sent.sequenceNumber
        history[feedback.sent.sequenceNumber] = feedback
    }

    fun processSentPacket(sentPacket: SentPacketInfo): SentPacket? {
        val sendTime = sentPacket.sendTime
        // TODO(srte): Only use one way to indicate that packet feedback is used.
        if (sentPacket.info.includedInFeedback || sentPacket.packetId != -1L) {
            val seqNum = sentPacket.packetId
            val it = history[seqNum]
            if (it != null) {
                val packetRetransmit = it.sent.sendTime.isFinite()
                it.sent.sendTime = sendTime
                lastSendTime = max(lastSendTime, sendTime)
                // TODO(srte): Don't do this on retransmit.
                if (pendingUntrackedSize != DataSize.ZERO) {
                    if (sendTime < lastUntrackedSendTime) {
                        logger.warn(
                            "appending acknowledged data for out of order packet.  (Diff: " +
                                "${Duration.between(sendTime, lastUntrackedSendTime)}.)"
                        )
                    }
                    pendingUntrackedSize += sentPacket.info.packetSizeBytes.bytes
                }
                if (!packetRetransmit) {
                    if (it.sent.sequenceNumber > lastAckSeqNum) {
                        inFlight.addInFlightPacketBytes(it)
                        it.sent.dataInFlight = inFlight.getOutstandingData()
                        return it.sent
                    }
                }
            }
        } else if (sentPacket.info.includedInAllocation) {
            if (sendTime < lastSendTime) {
                logger.warn("ignoring untracked data for out of order packet")
            }
            pendingUntrackedSize += sentPacket.info.packetSizeBytes.bytes
            lastUntrackedSendTime = max(lastUntrackedSendTime, sendTime)
        }
        return null
    }

    fun processTransportFeedback(feedback: RtcpFbTccPacket, feedbackReceiveTime: Instant): TransportPacketsFeedback? {
        if (feedback.GetPacketStatusCount() == 0) {
            logger.info { "Empty transport feedback packet received" }
            return null
        }

        // Add timestamp deltas to a local time base selected on first packet arrival.
        // This won't be the true time base, but makes it easier to manually inspect
        // time stamps.
        if (lastTransportFeedbackBaseTime.isInfinite()) {
            currentOffset = feedbackReceiveTime
        } else {
            val delta = feedback.GetBaseDelta(lastTransportFeedbackBaseTime)
            // Protect against assigning current_offset_ negative value.
            if (delta < Duration.between(currentOffset, Instant.EPOCH)) {
                logger.warn("Unexpected feedback timestamp received: $feedbackReceiveTime")
                currentOffset = feedbackReceiveTime
            } else {
                currentOffset += delta
            }
        }
        lastTransportFeedbackBaseTime = feedback.BaseTime()

        val packetResultVector = ArrayList<PacketResult>()
        packetResultVector.ensureCapacity(feedback.GetPacketStatusCount())

        var failedLookups = 0

        var deltaSinceBase = Duration.ZERO

        feedback.forEach { report ->
            val seqNum = seqNumUnwrapper.interpret(report.seqNum)

            val packetFeedback = retrievePacketFeedback(seqNum, received = report is ReceivedPacketReport)
            if (packetFeedback == null) {
                ++failedLookups
                return@forEach
            }

            val previouslyReportedLost = packetFeedback.reportedLost

            val result = PacketResult()
            result.sentPacket = packetFeedback.sent

            if (report is ReceivedPacketReport) {
                deltaSinceBase += report.deltaDuration
                result.receiveTime = currentOffset + deltaSinceBase
                history.remove(seqNum)
            } else {
                packetFeedback.reportedLost = true
            }
            result.rtpPacketInfo = PacketResult.RtpPacketInfo(
                packetFeedback.ssrc,
                packetFeedback.rtpSequenceNumber,
                packetFeedback.isRetransmission
            )
            result.previouslyReportedLost = previouslyReportedLost
            packetResultVector.add(result)
        }

        if (failedLookups > 0) {
            logger.info(
                "Failed to lookup send time for $failedLookups packet${if (failedLookups > 1) "s" else ""}. " +
                    "Packets reordered or send time history too small?"
            )
        }

        return toTransportFeedback(packetResultVector, feedbackReceiveTime, supportsEcn = false)
    }

    fun processCongestionControlFeedback(
        feedback: RtcpFbCcfbPacket,
        feedbackReceiveTime: Instant
    ): TransportPacketsFeedback? {
        if (feedback.packets.isEmpty()) {
            logger.info { "Empty congestion control feedback packet received" }
            return null
        }
        if (currentOffset.isInfinite()) {
            currentOffset = feedbackReceiveTime
        }
        val feedbackDelta = lastFeedbackCompactNtpTime?.let {
            Duration.ofMillis(
                TimeUtils.ntpShortToMs(feedback.reportTimestampCompactNtp) -
                    TimeUtils.ntpShortToMs(it)
            )
        } ?: Duration.ZERO
        lastFeedbackCompactNtpTime = feedback.reportTimestampCompactNtp
        if (feedbackDelta < Duration.ZERO) {
            logger.warn("Unexpected feedback ntp time delta $feedbackDelta")
            currentOffset = feedbackReceiveTime
        } else {
            currentOffset += feedbackDelta
        }

        var failedLookups = 0
        var supportsEcn = true
        val packetResultVector = mutableListOf<PacketResult>()
        feedback.packets.forEach { packetInfo ->
            val packetFeedback = retrievePacketFeedback(
                SsrcAndRtpSequenceNumber(ssrc = packetInfo.ssrc, packetInfo.sequenceNumber),
                received = packetInfo is ReceivedPacketInfo
            )
            if (packetFeedback == null) {
                ++failedLookups
                return@forEach
            }
            val result = PacketResult()
            result.sentPacket = packetFeedback.sent
            if (packetInfo is ReceivedPacketInfo) {
                result.receiveTime = currentOffset - packetInfo.arrivalTimeOffset
                result.ecn = packetInfo.ecn
                supportsEcn = supportsEcn && packetInfo.ecn != EcnMarking.kNotEct
            }
            result.rtpPacketInfo = PacketResult.RtpPacketInfo(
                ssrc = packetFeedback.ssrc,
                rtpSequenceNumber = packetFeedback.rtpSequenceNumber,
                isRetransmission = packetFeedback.isRetransmission,
            )
            packetResultVector.add(result)
        }

        if (failedLookups > 0) {
            logger.warn {
                "Failed to lookup send time for $failedLookups packets. " +
                    "Packets reordered or send time history too small?"
            }
        }

        // Feedback is expected to be sorted in send order.
        packetResultVector.sortWith { lhs, rhs ->
            compareValuesBy(lhs, rhs) { it.sentPacket.sequenceNumber }
        }

        return toTransportFeedback(packetResultVector, feedbackReceiveTime, supportsEcn)
    }

    fun getOutstandingData() = inFlight.getOutstandingData()

    private data class SsrcAndRtpSequenceNumber(
        val ssrc: Long,
        val rtpSequenceNumber: Int
    ) : Comparable<SsrcAndRtpSequenceNumber> {
        override fun compareTo(other: SsrcAndRtpSequenceNumber): Int {
            return compareValuesBy(this, other, { it.ssrc }, { it.rtpSequenceNumber })
        }
    }

    private fun toTransportFeedback(
        packetResults: MutableList<PacketResult>,
        feedbackReceiveTime: Instant,
        supportsEcn: Boolean
    ): TransportPacketsFeedback? {
        val msg = TransportPacketsFeedback()
        msg.feedbackTime = feedbackReceiveTime
        if (packetResults.isEmpty()) {
            return null
        }
        msg.packetFeedbacks = packetResults
        msg.dataInFlight = inFlight.getOutstandingData()
        msg.transportSupportsEcn = supportsEcn

        return msg
    }

    private fun retrievePacketFeedback(transportSeqNum: Long, received: Boolean): PacketFeedback? {
        if (transportSeqNum > lastAckSeqNum) {
            // Starts at history_.begin() if last_ack_seq_num_ < 0, since any
            // valid sequence number is >= 0.
            for (it in history.subMap(lastAckSeqNum, false, transportSeqNum, true)) {
                inFlight.removeInFlightPacketBytes(it.value)
            }
            lastAckSeqNum = transportSeqNum
        }

        val packetFeedback = history[transportSeqNum]

        if (packetFeedback == null) {
            logger.debug {
                "No history entry found for seqNum $transportSeqNum"
            }
            return null
        }

        if (packetFeedback.sent.sendTime.isInfinite()) {
            // TODO(srte): Fix the tests that makes this happen and make this a
            //  DCHECK.
            logger.error(
                "Received feedback before packet with seqNum $transportSeqNum was indicated as sent"
            )
            return null
        }

        if (received) {
            // Note: Lost packets are not removed from history because they might
            // be reported as received by a later feedback.
            rtpToTransportSequenceNumber.remove(
                SsrcAndRtpSequenceNumber(packetFeedback.ssrc, packetFeedback.rtpSequenceNumber)
            )
            history.remove(transportSeqNum)
        }

        return packetFeedback
    }

    private fun retrievePacketFeedback(key: SsrcAndRtpSequenceNumber, received: Boolean): PacketFeedback? {
        val transportSeqNum = rtpToTransportSequenceNumber[key]
        if (transportSeqNum == null) {
            return null
        }
        return retrievePacketFeedback(transportSeqNum, received)
    }

    private var pendingUntrackedSize = DataSize.ZERO
    private var lastSendTime = Instant.MIN
    private var lastUntrackedSendTime = Instant.MIN
    private val seqNumUnwrapper = RtpSequenceIndexTracker()

    private var lastAckSeqNum = -1L
    private val inFlight = InFlightBytesTracker()

    private var currentOffset = Instant.MIN

    // `last_transport_feedback_base_time` is only used for transport feedback to
    // track base time.
    private var lastTransportFeedbackBaseTime = Instant.MIN

    // Used by RFC 8888 congestion control feedback to track base time.
    private var lastFeedbackCompactNtpTime: Long? = null

    // Map SSRC and RTP sequence number to transport sequence number.
    private val rtpToTransportSequenceNumber = TreeMap<SsrcAndRtpSequenceNumber, Long>()
    private val history = TreeMap<Long, PacketFeedback>()

    /** Jitsi local */
    fun getStatisitics(): StatisticsSnapshot {
        return StatisticsSnapshot(
            inFlight.inFlightData,
            pendingUntrackedSize,
            lastSendTime,
            lastUntrackedSendTime,
            lastAckSeqNum,
            history.size,
            currentOffset,
            lastTransportFeedbackBaseTime,
        )
    }

    class StatisticsSnapshot(
        val inFlight: DataSize,
        val pendingUntrackedSize: DataSize,
        val lastSendTime: Instant,
        val lastUntrackedSendTime: Instant,
        val lastAckSeqNum: Long,
        val historySize: Int,
        val currentOffset: Instant,
        val lastTransportFeedbackBaseTime: Instant
    ) {
        fun toJson(): OrderedJsonObject {
            return OrderedJsonObject().apply {
                put("in_flight_bytes", inFlight.bytes)
                put("pending_untracked_size", pendingUntrackedSize.bytes)
                put("last_send_time", lastSendTime.toEpochMilliOrInf())
                put("last_untracked_send_time", lastUntrackedSendTime.toEpochMilliOrInf())
                put("last_ack_seq_num", lastAckSeqNum)
                put("history_size", historySize)
                put("current_offset", currentOffset.toEpochMilliOrInf())
                put("last_transport_feedback_base_time", lastTransportFeedbackBaseTime.toEpochMilliOrInf())
            }
        }
    }
}

private fun Instant.toEpochMilliOrInf(): Number {
    return try {
        this.toEpochMilli()
    } catch (e: ArithmeticException) {
        if (this < Instant.EPOCH) {
            Double.NEGATIVE_INFINITY
        } else {
            Double.POSITIVE_INFINITY
        }
    }
}
