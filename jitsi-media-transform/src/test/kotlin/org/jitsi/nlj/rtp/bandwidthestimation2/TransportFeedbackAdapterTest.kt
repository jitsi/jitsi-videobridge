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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.roundDownTo
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.time.FakeClock
import org.jitsi.utils.times
import java.time.Duration
import java.time.Instant

/** Unit tests for TransportFeedbackAdapter,
 * based loosely on WebRTC modules/congestion_controller/rtp/transport_feedback_adapter_unittest.cc in
 * WebRTC tag branch-heads/6613 (Chromium 128)
 * modified to use Jitsi types for objects outside the congestion controller,
 * and not using Network Routes.
 */
private const val kSsrc = 8492L
private val kPacingInfo0 = PacedPacketInfo(0, 5, 2000)
private val kPacingInfo1 = PacedPacketInfo(1, 8, 4000)
private val kPacingInfo2 = PacedPacketInfo(2, 14, 7000)
private val kPacingInfo3 = PacedPacketInfo(3, 20, 10000)
private val kPacingInfo4 = PacedPacketInfo(4, 22, 10000)

private fun comparePacketFeedbackVectors(truth: List<PacketResult>, input: List<PacketResult>) {
    input.size shouldBe truth.size
    // truth contains the input data for the test, and input is what will be
    // sent to the bandwidth estimator. truth.arrival_tims_ms is used to
    // populate the transport feedback messages. As these times may be changed
    // (because of resolution limits in the packets, and because of the time
    // base adjustment performed by the TransportFeedbackAdapter at the first
    // packet, the truth[x].arrival_time and input[x].arrival_time may not be
    // equal. However, the difference must be the same for all x.
    val arrivalTimeDelta = Duration.between(input[0].receiveTime, truth[0].receiveTime)
    for (i in truth.indices) {
        withClue("feedback vector packet $i") {
            assert(truth[i].isReceived())
            if (input[i].isReceived()) {
                Duration.between(input[i].receiveTime, truth[i].receiveTime) shouldBe arrivalTimeDelta
            }
            input[i].sentPacket.sendTime shouldBe truth[i].sentPacket.sendTime
            input[i].sentPacket.sequenceNumber shouldBe truth[i].sentPacket.sequenceNumber
            input[i].sentPacket.size shouldBe truth[i].sentPacket.size
            input[i].sentPacket.pacingInfo shouldBe truth[i].sentPacket.pacingInfo
        }
    }
}

private fun createPacket(
    receiveTimeMs: Long,
    sendTimeMs: Long,
    sequenceNumber: Int,
    payloadSize: Long,
    pacingInfo: PacedPacketInfo
): PacketResult {
    val res = PacketResult().apply {
        receiveTime = Instant.ofEpochMilli(receiveTimeMs)
        sentPacket.sendTime = Instant.ofEpochMilli(sendTimeMs)
        sentPacket.sequenceNumber = sequenceNumber.toLong()
        sentPacket.size = payloadSize.bytes
        sentPacket.pacingInfo = pacingInfo
    }
    return res
}

class OneTransportFeedbackAdapterTest {
    private val logger = createLogger()

    fun onSentPacket(packetFeedback: PacketResult) {
        adapter.addPacket(
            packetFeedback.sentPacket.sequenceNumber.toInt(),
            packetFeedback.sentPacket.size,
            packetFeedback.sentPacket.pacingInfo,
            clock.instant()
        )
        adapter.processSentPacket(
            SentPacketInfo(
                packetFeedback.sentPacket.sequenceNumber.toInt(),
                packetFeedback.sentPacket.sendTime,
                PacketInfo()
            )
        )
    }

    val clock = FakeClock()
    val adapter = TransportFeedbackAdapter(logger)
}

class TransportFeedbackAdapterTest : FreeSpec() {
    init {
        "AdaptsFeedbackAndPopulatesSendTimes" {
            val test = OneTransportFeedbackAdapterTest()
            val packets = mutableListOf<PacketResult>()
            packets.add(createPacket(100, 200, 0, 1500, kPacingInfo0))
            packets.add(createPacket(110, 210, 1, 1500, kPacingInfo0))
            packets.add(createPacket(120, 220, 2, 1500, kPacingInfo0))
            packets.add(createPacket(130, 230, 3, 1500, kPacingInfo1))
            packets.add(createPacket(140, 240, 4, 1500, kPacingInfo1))

            packets.forEach { packet -> test.onSentPacket(packet) }

            val feedback = RtcpFbTccPacketBuilder()
            feedback.SetBase(packets[0].sentPacket.sequenceNumber.toInt(), packets[0].receiveTime)

            packets.forEach { packet ->
                feedback.AddReceivedPacket(packet.sentPacket.sequenceNumber.toInt(), packet.receiveTime) shouldBe true
            }

            val feedbackPacket = feedback.build()

            val result = test.adapter.processTransportFeedback(feedbackPacket, test.clock.instant())
            comparePacketFeedbackVectors(packets, result!!.packetFeedbacks)
        }

        "FeedbackVectorReportsUnreceived" {
            val test = OneTransportFeedbackAdapterTest()
            val sentPackets = listOf(
                createPacket(100, 220, 0, 1500, kPacingInfo0),
                createPacket(110, 210, 1, 1500, kPacingInfo0),
                createPacket(120, 220, 2, 1500, kPacingInfo0),
                createPacket(130, 230, 3, 1500, kPacingInfo0),
                createPacket(140, 240, 4, 1500, kPacingInfo0),
                createPacket(150, 250, 5, 1500, kPacingInfo0),
                createPacket(160, 260, 6, 1500, kPacingInfo0)
            )

            sentPackets.forEach { packet -> test.onSentPacket(packet) }

            // Note: Important to include the last packet, as only unreceived packets in
            // between received packets can be inferred.
            val receivedPackets = listOf(
                sentPackets[0],
                sentPackets[2],
                sentPackets[6]
            )

            val feedbackBuilder = RtcpFbTccPacketBuilder()
            feedbackBuilder.SetBase(
                receivedPackets[0].sentPacket.sequenceNumber.toInt(),
                receivedPackets[0].receiveTime
            )

            receivedPackets.forEach { packet ->
                feedbackBuilder.AddReceivedPacket(
                    packet.sentPacket.sequenceNumber.toInt(),
                    packet.receiveTime
                ) shouldBe true
            }

            val feedback = feedbackBuilder.build()

            val res = test.adapter.processTransportFeedback(feedback, test.clock.instant())
            comparePacketFeedbackVectors(sentPackets, res!!.packetFeedbacks)
        }

        "HandlesDroppedPackets" {
            val test = OneTransportFeedbackAdapterTest()
            val packets = mutableListOf<PacketResult>()
            packets.add(createPacket(100, 200, 0, 1500, kPacingInfo0))
            packets.add(createPacket(110, 210, 1, 1500, kPacingInfo1))
            packets.add(createPacket(120, 220, 2, 1500, kPacingInfo2))
            packets.add(createPacket(130, 230, 3, 1500, kPacingInfo3))
            packets.add(createPacket(140, 240, 4, 1500, kPacingInfo4))

            val kSendSideDropBefore = 1
            val kReceiveSideDropAfter = 3

            packets.forEach { packet ->
                if (packet.sentPacket.sequenceNumber >= kSendSideDropBefore) {
                    test.onSentPacket(packet)
                }
            }

            val feedbackBuilder = RtcpFbTccPacketBuilder()
            feedbackBuilder.SetBase(packets[0].sentPacket.sequenceNumber.toInt(), packets[0].receiveTime)

            packets.forEach { packet ->
                if (packet.sentPacket.sequenceNumber <= kReceiveSideDropAfter) {
                    feedbackBuilder.AddReceivedPacket(
                        packet.sentPacket.sequenceNumber.toInt(),
                        packet.receiveTime
                    ) shouldBe true
                }
            }

            val feedback = feedbackBuilder.build()

            val expectedPackets = packets.subList(kSendSideDropBefore, kReceiveSideDropAfter + 1)

            // Packets that have timed out on the send-side have lost the
            // information stored on the send-side. And they will not be reported to
            // observers since we won't know that they come from the same networks.
            val res = test.adapter.processTransportFeedback(feedback, test.clock.instant())
            comparePacketFeedbackVectors(expectedPackets, res!!.packetFeedbacks)
        }

        "SendTimeWrapsBothWays" {
            val test = OneTransportFeedbackAdapterTest()
            val kHighArrivalTime = RtcpFbTccPacket.Companion.kDeltaScaleFactor * (1 shl 8) * ((1 shl 23) - 1)
            val packets = mutableListOf<PacketResult>()
            packets.add(createPacket(kHighArrivalTime.toMillis() + 64, 210, 0, 1500, PacedPacketInfo()))
            packets.add(createPacket(kHighArrivalTime.toMillis() - 64, 210, 1, 1500, PacedPacketInfo()))
            packets.add(createPacket(kHighArrivalTime.toMillis(), 220, 2, 1500, PacedPacketInfo()))

            packets.forEach { packet ->
                test.onSentPacket(packet)
            }

            packets.forEach { packet ->
                val feedbackBuilder = RtcpFbTccPacketBuilder()
                feedbackBuilder.SetBase(packet.sentPacket.sequenceNumber.toInt(), packet.receiveTime)

                feedbackBuilder.AddReceivedPacket(
                    packet.sentPacket.sequenceNumber.toInt(),
                    packet.receiveTime
                ) shouldBe true

                var feedback = feedbackBuilder.build()
                val rawBuffer = feedback.buffer
                val offset = feedback.offset
                val length = feedback.length

                feedback = RtcpFbTccPacket(rawBuffer, offset, length)

                val expectedPackets = mutableListOf<PacketResult>()
                expectedPackets.add(packet)

                val res = test.adapter.processTransportFeedback(feedback, test.clock.instant())
                comparePacketFeedbackVectors(expectedPackets, res!!.packetFeedbacks)
            }
        }

        "HandlesArrivalReordering" {
            val test = OneTransportFeedbackAdapterTest()
            val packets = mutableListOf<PacketResult>()
            packets.add(createPacket(120, 200, 0, 1500, kPacingInfo0))
            packets.add(createPacket(110, 210, 1, 1500, kPacingInfo1))
            packets.add(createPacket(100, 220, 2, 1500, kPacingInfo2))

            packets.forEach { packet ->
                test.onSentPacket(packet)
            }

            val feedbackBuilder = RtcpFbTccPacketBuilder()
            feedbackBuilder.SetBase(packets[0].sentPacket.sequenceNumber.toInt(), packets[0].receiveTime)

            packets.forEach { packet ->
                feedbackBuilder.AddReceivedPacket(
                    packet.sentPacket.sequenceNumber.toInt(),
                    packet.receiveTime
                ) shouldBe true
            }

            val feedback = feedbackBuilder.build()

            // Adapter keeps the packets ordered by sequence number (which is itself
            // assigned by the order of transmission). Reordering by some other criteria,
            // eg. arrival time, is up to the observers.
            val res = test.adapter.processTransportFeedback(feedback, test.clock.instant())
            comparePacketFeedbackVectors(packets, res!!.packetFeedbacks)
        }

        "TimestampDeltas" {
            val test = OneTransportFeedbackAdapterTest()
            val sentPackets = mutableListOf<PacketResult>()
            // TODO(srte): Consider using us resolution in the constants.
            val kSmallDelta = RtcpFbTccPacket.kDeltaScaleFactor.roundDownTo(1.ms)
            val kLargePositiveDelta = (RtcpFbTccPacket.kDeltaScaleFactor * Short.MAX_VALUE.toInt()).roundDownTo(1.ms)
            val kLargeNegativeDelta = (RtcpFbTccPacket.kDeltaScaleFactor * Short.MIN_VALUE.toInt()).roundDownTo(1.ms)

            val packetFeedback = PacketResult()
            packetFeedback.sentPacket.sequenceNumber = 1
            packetFeedback.sentPacket.sendTime = Instant.ofEpochMilli(100)
            packetFeedback.receiveTime = Instant.ofEpochMilli(200)
            sentPackets.add(packetFeedback.copy())

            // TODO(srte): This rounding maintains previous behavior, but should ot be
            // required.
            packetFeedback.sentPacket.sendTime += kSmallDelta
            packetFeedback.receiveTime += kSmallDelta
            ++packetFeedback.sentPacket.sequenceNumber
            sentPackets.add(packetFeedback.copy())

            packetFeedback.sentPacket.sendTime += kLargePositiveDelta
            packetFeedback.receiveTime += kLargePositiveDelta
            ++packetFeedback.sentPacket.sequenceNumber
            sentPackets.add(packetFeedback.copy())

            packetFeedback.sentPacket.sendTime += kLargeNegativeDelta
            packetFeedback.receiveTime += kLargeNegativeDelta
            ++packetFeedback.sentPacket.sequenceNumber
            sentPackets.add(packetFeedback.copy())

            // Too large, delta - will need two feedback messages.
            packetFeedback.sentPacket.sendTime += kLargePositiveDelta + 1.ms
            packetFeedback.receiveTime += kLargePositiveDelta + 1.ms
            ++packetFeedback.sentPacket.sequenceNumber

            sentPackets.forEach { packet ->
                test.onSentPacket(packet)
            }
            test.onSentPacket(packetFeedback)

            // Create expected feedback and send into adapter.
            var feedbackBuilder = RtcpFbTccPacketBuilder()
            feedbackBuilder.SetBase(sentPackets[0].sentPacket.sequenceNumber.toInt(), sentPackets[0].receiveTime)

            sentPackets.forEach { packet ->
                feedbackBuilder.AddReceivedPacket(
                    packet.sentPacket.sequenceNumber.toInt(),
                    packet.receiveTime
                ) shouldBe true
            }
            feedbackBuilder.AddReceivedPacket(
                packetFeedback.sentPacket.sequenceNumber.toInt(),
                packetFeedback.receiveTime
            ) shouldBe false

            var feedback = feedbackBuilder.build()
            var rawPacket = feedback.buffer
            var offset = feedback.offset
            var length = feedback.length
            feedback = RtcpFbTccPacket(rawPacket, offset, length)

            var res = test.adapter.processTransportFeedback(feedback, test.clock.instant())
            comparePacketFeedbackVectors(sentPackets, res!!.packetFeedbacks)

            // Create a new feedback message and add the trailing item.
            feedbackBuilder = RtcpFbTccPacketBuilder()
            feedbackBuilder.SetBase(packetFeedback.sentPacket.sequenceNumber.toInt(), packetFeedback.receiveTime)
            feedbackBuilder.AddReceivedPacket(
                packetFeedback.sentPacket.sequenceNumber.toInt(),
                packetFeedback.receiveTime
            ) shouldBe true
            feedback = feedbackBuilder.build()
            rawPacket = feedback.buffer
            offset = feedback.offset
            length = feedback.length
            feedback = RtcpFbTccPacket(rawPacket, offset, length)

            res = test.adapter.processTransportFeedback(feedback, test.clock.instant())
            val expectedPackets = mutableListOf<PacketResult>()
            expectedPackets.add(packetFeedback.copy())
            comparePacketFeedbackVectors(expectedPackets, res!!.packetFeedbacks)
        }
    }
}
