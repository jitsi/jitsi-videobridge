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

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacketBuilder
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.time.FakeClock
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
    }
}
