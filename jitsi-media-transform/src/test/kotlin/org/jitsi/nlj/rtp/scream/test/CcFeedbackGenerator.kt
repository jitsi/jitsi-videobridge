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
package org.jitsi.nlj.rtp.scream.test

import io.kotest.assertions.fail
import org.jitsi.nlj.rtp.bandwidthestimation2.PacketResult
import org.jitsi.nlj.rtp.bandwidthestimation2.SentPacket
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.rtp.scream.dataSentAt
import org.jitsi.nlj.rtp.scream.microsToDuration
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.utils.NEVER
import org.jitsi.utils.isInfinite
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.TreeMap

/** The time in microseconds since the epoch, as Timestamp::us() in WebRTC. */
fun Instant.toEpochMicros(): Long = epochSecond * 1_000_000 + nano / 1_000

/** The time which is this number of microseconds since the epoch, as Timestamp::Micros() in WebRTC. */
fun instantOfEpochMicros(micros: Long): Instant = Instant.EPOCH + microsToDuration(micros)

/** Feedback generator,
 * based on WebRTC modules/congestion_controller/scream/test/cc_feedback_generator.{h,cc} in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 *
 * Simulates sending packets with a given send rate over a simulated network and
 * generates TransportPacketsFeedback that is supposed to match
 * TransportPacketsFeedback from rtcp::CongestionControlFeedback (RFC8888).
 */
class CcFeedbackGenerator(config: Config) {
    class Config(
        val networkConfig: BuiltInNetworkBehaviorConfig = BuiltInNetworkBehaviorConfig(),
        val timeBetweenFeedback: Duration = 25.ms,
        val sendAsEct1: Boolean = true,
        val queue: NetworkQueue = LeakyBucketNetworkQueue(
            LeakyBucketNetworkQueue.Config(
                maxEct1SojournTimeUs = 8_000,
                targetEct1SojournTimeUs = 4_000
            )
        ),
        val packetSize: DataSize = 1000.bytes
    )

    // Processes the simulation until the next feedback message is received.
    // The function will send packets at the given send rate until the next
    // feedback message is generated.
    fun processUntilNextFeedback(
        sendRate: Bandwidth,
        clock: FakeClock,
        sentPacketCb: ((SentPacket) -> Unit)? = null,
        maxTime: Duration = 1.secs
    ): TransportPacketsFeedback {
        val endTime = clock.instant() + maxTime
        while (clock.instant() < endTime) {
            val sentPacket = maybeSendPackets(clock.instant(), sendRate)
            processNetwork(clock.instant())
            if (sentPacketCb != null && sentPacket != null) {
                sentPacketCb(sentPacket)
            }
            val feedback = maybeSendFeedback(clock.instant())
            if (feedback != null) {
                return feedback
            }
            if (sentPacket == null) {
                clock.elapse(1.ms)
            }
        }
        fail("No feedback received after $maxTime")
    }

    private fun maybeSendPackets(time: Instant, sendRate: Bandwidth): SentPacket? {
        if (lastSendBudgetUpdate.isInfinite()) {
            sendBudget = packetSize
        } else {
            sendBudget += dataSentAt(sendRate, Duration.between(lastSendBudgetUpdate, time))
        }
        lastSendBudgetUpdate = time

        // This simulator pace out packets with perfect pacing.
        if (sendBudget < packetSize) {
            return null
        }
        sendBudget -= packetSize
        val packetId = nextPacketId++
        val ecn = if (sendAsEct1) EcnMarking.kEct1 else EcnMarking.kNotEct
        var bytesInFlight = DataSize.ZERO
        for ((id, tracked) in sentPackets) {
            if (lastAckedSequenceNumber == null || id > lastAckedSequenceNumber!!) {
                bytesInFlight += tracked.sentPacket.size
            }
        }
        bytesInFlight += packetSize

        val sentPacket = SentPacket()
        sentPacket.sendTime = time
        sentPacket.size = packetSize
        sentPacket.sequenceNumber = packetId
        sentPacket.dataInFlight = bytesInFlight

        val packetResult = PacketResult()
        packetResult.sentPacket = sentPacket.copy()
        packetResult.sentWithEct1 = sendAsEct1

        sentPackets[packetId] = packetResult
        network.enqueuePacket(PacketInFlightInfo(packetSize.bits / 8, time.toEpochMicros(), packetId, ecn))
        return sentPacket
    }

    private fun processNetwork(time: Instant) {
        val receivedPackets = network.dequeueDeliverablePackets(time.toEpochMicros())
        for (packet in receivedPackets) {
            if (packet.receiveTimeUs != PacketDeliveryInfo.kNotReceived) {
                packetsReceived.addLast(packet)
            }
        }
    }

    private fun maybeSendFeedback(time: Instant): TransportPacketsFeedback? {
        if (packetsReceived.isEmpty()) {
            return null
        }
        if (lastFeedbackTime.isInfinite()) {
            lastFeedbackTime = instantOfEpochMicros(packetsReceived.first.receiveTimeUs) + oneWayDelay
        }
        if (Duration.between(lastFeedbackTime, time) < timeBetweenFeedback) {
            return null
        }
        // Time to deliver feedback if there are packets to deliver.
        val feedback = TransportPacketsFeedback()
        // Deliver feedback one way delay later than when the packets were
        // received.
        while (!packetsReceived.isEmpty() &&
            Duration.between(instantOfEpochMicros(packetsReceived.first.receiveTimeUs), time) >= oneWayDelay
        ) {
            val deliveryInfo = packetsReceived.removeFirst()

            if (lastAckedSequenceNumber == null || deliveryInfo.packetId > lastAckedSequenceNumber!!) {
                lastAckedSequenceNumber = deliveryInfo.packetId
            }

            val tracked = sentPackets[deliveryInfo.packetId]
            if (tracked != null) {
                val packetResult = tracked.copy()
                packetResult.receiveTime = instantOfEpochMicros(deliveryInfo.receiveTimeUs)
                packetResult.arrivalTimeOffset = Duration.between(packetResult.receiveTime, time) - oneWayDelay
                packetResult.ecn = deliveryInfo.ecn
                if (packetResult.reportedLostForTheFirstTime) {
                    packetResult.reportedRecoveredForTheFirstTime = true
                    packetResult.reportedLostForTheFirstTime = false
                }
                feedback.packetFeedbacks.add(packetResult)
                sentPackets.remove(deliveryInfo.packetId)
            }
        }

        lastAckedSequenceNumber?.let { lastAcked ->
            val it = sentPackets.headMap(lastAcked, false).entries.iterator()
            while (it.hasNext()) {
                val tracked = it.next().value
                if (!tracked.reportedLostForTheFirstTime) {
                    tracked.reportedLostForTheFirstTime = true
                    feedback.packetFeedbacks.add(tracked.copy())
                }
                if (Duration.between(tracked.sentPacket.sendTime, time) > 2.secs) {
                    it.remove()
                }
            }
        }

        if (feedback.packetFeedbacks.isEmpty()) {
            return null
        }

        feedback.packetFeedbacks.sortBy { it.sentPacket.sequenceNumber }

        for ((id, tracked) in sentPackets) {
            if (lastAckedSequenceNumber == null || id > lastAckedSequenceNumber!!) {
                feedback.dataInFlight += tracked.sentPacket.size
            }
        }
        feedback.feedbackTime = time
        lastFeedbackTime = time
        return feedback
    }

    private val packetSize = config.packetSize
    private val timeBetweenFeedback = config.timeBetweenFeedback
    private val oneWayDelay: Duration = config.networkConfig.queueDelayMs.ms
    private val sendAsEct1 = config.sendAsEct1
    private val network = SimulatedNetwork(config.networkConfig, randomSeed = 1uL, config.queue)

    private var nextPacketId = 0L
    private var lastAckedSequenceNumber: Long? = null
    private val sentPackets = TreeMap<Long, PacketResult>()

    // `packets_received_` are packed that have been received by the remote, but
    // feedback has not yet been received by the sender. Feedback is delivered one
    // way delay later than when the packets were received.
    private val packetsReceived = ArrayDeque<PacketDeliveryInfo>()

    private var lastFeedbackTime: Instant = NEVER
    private var lastSendBudgetUpdate: Instant = NEVER
    private var sendBudget = DataSize.ZERO

    companion object {
        fun countCeMarks(feedback: TransportPacketsFeedback): Int =
            feedback.packetFeedbacks.count { it.ecn == EcnMarking.kCe }
    }
}
