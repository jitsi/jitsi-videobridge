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

import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.Rfc3711IndexTracker
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.isFinite
import org.jitsi.nlj.util.isInfinite
import org.jitsi.nlj.util.max
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.ReceivedPacketReport
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
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
data class PacketFeedback(
    // Time corresponding to when this object was created.
    val creationTime: Instant = Instant.MIN,
    val sent: SentPacket,
    // Time corresponding to when the packet was received. Timestamped with the
    // receiver's clock. For unreceived packet, Timestamp::PlusInfinity() is
    // used.
    var receiveTime: Instant = Instant.MAX
)

class InFlightBytesTracker {
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

class TransportFeedbackAdapter(
    parentLogger: Logger
) {
    val logger = parentLogger.createChildLogger(javaClass.name)

    fun addPacket(tccSeqNum: Int, overheadBytes: DataSize, pacingInfo: PacedPacketInfo?, creationTime: Instant) {
        val packet = PacketFeedback(
            creationTime = creationTime,
            sent = SentPacket(
                sequenceNumber = seqNumUnwrapper.update(tccSeqNum).toLong(),
                size = overheadBytes,
                pacingInfo = pacingInfo ?: PacedPacketInfo()
            )
        )
        while (history.isNotEmpty() &&
            Duration.between(history.firstEntry().value.creationTime, creationTime) > kSendTimeHistoryWindow
        ) {
            // TODO(sprang): Warn if erasing (too many) old items?
            if (history.firstEntry().value.sent.sequenceNumber > lastAckSeqNum) {
                inFlight.removeInFlightPacketBytes(history.firstEntry().value)
            }
            history.remove(history.firstEntry().key)
        }
        history[packet.sent.sequenceNumber] = packet
    }

    fun processSentPacket(sentPacket: SentPacketInfo): SentPacket? {
        val sendTime = sentPacket.sendTime
        // TODO(srte): Only use one way to indicate that packet feedback is used.
        if (sentPacket.info.includedInFeedback || sentPacket.packetId != -1) {
            val unwrappedSeqNum = seqNumUnwrapper.update(sentPacket.packetId)
            val it = history[unwrappedSeqNum.toLong()]
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
        val msg = TransportPacketsFeedback()
        msg.feedbackTime = feedbackReceiveTime
        msg.packetFeedbacks = processTransportFeedbackInner(feedback, feedbackReceiveTime)
        if (msg.packetFeedbacks.isEmpty()) {
            return null
        }
        msg.dataInFlight = inFlight.getOutstandingData()

        return msg
    }

    private fun processTransportFeedbackInner(
        feedback: RtcpFbTccPacket,
        feedbackReceiveTime: Instant
    ): MutableList<PacketResult> {
        // Add timestamp deltas to a local time base selected on first packet arrival.
        // This won't be the true time base, but makes it easier to manually inspect
        // time stamps.
        if (lastTimestamp.isInfinite()) {
            currentOffset = feedbackReceiveTime
        } else {
            val delta = feedback.GetBaseDelta(lastTimestamp)
            // Protect against assigning current_offset_ negative value.
            if (delta < Duration.between(currentOffset, Instant.EPOCH)) {
                logger.warn("Unexpected feedback timestamp received: $feedbackReceiveTime")
                currentOffset = feedbackReceiveTime
            } else {
                currentOffset += delta
            }
        }
        lastTimestamp = feedbackReceiveTime

        val packetResultVector = ArrayList<PacketResult>()
        packetResultVector.ensureCapacity(feedback.GetPacketStatusCount())

        var failedLookups = 0

        var deltaSinceBase = Duration.ZERO

        feedback.forEach { report ->
            val seqNum = seqNumUnwrapper.update(report.seqNum).toLong()

            if (seqNum > lastAckSeqNum) {
                // Starts at history_.begin() if last_ack_seq_num_ < 0, since any
                // valid sequence number is >= 0.
                for (it in history.subMap(lastAckSeqNum, false, seqNum, true)) {
                    inFlight.removeInFlightPacketBytes(it.value)
                }
                lastAckSeqNum = seqNum
            }

            val packetFeedback = history[seqNum]
            if (packetFeedback == null) {
                logger.debug("No history entry found for seqNum $seqNum")
                ++failedLookups
                return@forEach
            }

            if (packetFeedback.sent.sendTime.isInfinite()) {
                // TODO(srte): Fix the tests that makes this happen and make this a
                //  DCHECK.
                logger.error("Received feedback before packet was indicated as sent")
                return@forEach
            }

            if (report is ReceivedPacketReport) {
                deltaSinceBase += report.deltaDuration
                packetFeedback.receiveTime = currentOffset + deltaSinceBase
                history.remove(seqNum)
            }
            val result = PacketResult()
            result.sentPacket = packetFeedback.sent
            result.receiveTime = packetFeedback.receiveTime
            packetResultVector.add(result)
        }

        if (failedLookups > 0) {
            logger.warn(
                "Failed to lookup send time for $failedLookups packet${if (failedLookups > 1) "s" else ""}. " +
                    "Packets reordered or send time history too small?"
            )
        }

        return packetResultVector
    }

    private var pendingUntrackedSize = DataSize.ZERO
    private var lastSendTime = Instant.MIN
    private var lastUntrackedSendTime = Instant.MIN
    private val seqNumUnwrapper = Rfc3711IndexTracker()
    private val history = TreeMap<Long, PacketFeedback>()

    private var lastAckSeqNum = -1L
    private val inFlight = InFlightBytesTracker()

    private var currentOffset = Instant.MIN
    private var lastTimestamp = Instant.MIN

    /** Jitsi local */
    fun getStatisitics(): StatisticsSnapshot {
        return StatisticsSnapshot(
            inFlight.inFlightData,
            pendingUntrackedSize,
            lastSendTime,
            lastUntrackedSendTime,
            lastAckSeqNum,
            history.size
        )
    }

    class StatisticsSnapshot(
        val inFlight: DataSize,
        val pendingUntrackedSize: DataSize,
        val lastSendTime: Instant,
        val lastUntrackedSendTime: Instant,
        val lastAckSeqNum: Long,
        val historySize: Int
    ) {
        fun toJson(): OrderedJsonObject {
            return OrderedJsonObject().apply {
                put("in_flight_bytes", inFlight.bytes)
                put("pending_untracked_size", pendingUntrackedSize.bytes)
                put(
                    "last_send_time",
                    if (lastSendTime.isFinite()) {
                        lastSendTime.toEpochMilli()
                    } else {
                        Double.NEGATIVE_INFINITY
                    }
                )
                put(
                    "last_untracked_send_time",
                    if (lastUntrackedSendTime.isFinite()) {
                        lastUntrackedSendTime.toEpochMilli()
                    } else {
                        Double.NEGATIVE_INFINITY
                    }
                )
                put("last_ack_seq_num", lastAckSeqNum)
                put("history_size", historySize)
            }
        }
    }
}
