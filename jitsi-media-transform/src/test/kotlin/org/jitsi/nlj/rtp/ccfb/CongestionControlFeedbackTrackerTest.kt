/*
 * Copyright @ 2026 - present 8x8, Inc.
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

package org.jitsi.nlj.rtp.ccfb

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.PacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.ReceivedPacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.UnreceivedPacketInfo
import org.jitsi.utils.logging2.createLogger
import java.time.Duration
import java.time.Instant

private const val kSsrc = 1234L

/**
 * Unit tests for CongestionControlFeedbackTracker.
 *
 * Based on WebRTC modules/remote_bitrate_estimator/congestion_control_feedback_tracker_unittest.cc in
 * WebRTC 3987dfb7b9 (October 2026).
 */
class CongestionControlFeedbackTrackerTest : ShouldSpec() {
    private val logger = createLogger()

    private class Packet(val arrivalTime: Instant, val seq: Int = 1, val ecn: EcnMarking = EcnMarking.kNotEct)

    private fun millis(ms: Long) = Instant.ofEpochMilli(ms)

    private fun createTracker() = CongestionControlFeedbackTracker(kSsrc, logger)

    private fun CongestionControlFeedbackTracker.receivedPacket(packet: Packet) =
        receivedPacket(packet.seq, packet.arrivalTime, packet.ecn)

    private fun CongestionControlFeedbackTracker.receivedPacket(arrivalTime: Instant, seq: Int) =
        receivedPacket(seq, arrivalTime, EcnMarking.kNotEct)

    /** The arrival time offset of a packet reported as received, checking that it was. */
    private val PacketInfo.arrivalTimeOffset: Duration
        get() = this.shouldBeInstanceOf<ReceivedPacketInfo>().arrivalTimeOffset

    private val PacketInfo.ecn: EcnMarking
        get() = this.shouldBeInstanceOf<ReceivedPacketInfo>().ecn

    /** Checks that the feedback has an entry for [seq], and that it reports the packet as received or not. */
    private fun List<PacketInfo>.shouldContain(seq: Int, received: Boolean): PacketInfo {
        val matching = filter { it.sequenceNumber == seq }
        matching.shouldNotBeEmpty()
        matching.any { (it is ReceivedPacketInfo) == received } shouldBe true
        return matching.first { (it is ReceivedPacketInfo) == received }
    }

    init {
        should("include received packets in feedback in sequence number order") {
            val packet1 = Packet(arrivalTime = millis(123), seq = 2)
            val packet2 = Packet(arrivalTime = millis(125), seq = 1)

            val tracker = createTracker()
            tracker.receivedPacket(packet1)
            tracker.receivedPacket(packet2)

            val feedbackTime = millis(567)
            val feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(feedbackTime, feedbackInfo)
            feedbackInfo shouldHaveSize 2
            feedbackInfo[0].ssrc shouldBe kSsrc
            feedbackInfo[0].sequenceNumber shouldBe packet2.seq
            feedbackInfo[0].arrivalTimeOffset shouldBe Duration.between(packet2.arrivalTime, feedbackTime)
            feedbackInfo[1].sequenceNumber shouldBe packet1.seq
            feedbackInfo[1].arrivalTimeOffset shouldBe Duration.between(packet1.arrivalTime, feedbackTime)
        }

        should("report the first received packet's arrival time but ECN from a CE packet if duplicate") {
            val packet1 = Packet(arrivalTime = millis(123), seq = 1, ecn = EcnMarking.kEct1)
            val packet2 = Packet(arrivalTime = millis(125), seq = 1, ecn = EcnMarking.kCe)
            val packet3 = Packet(arrivalTime = millis(126), seq = 1, ecn = EcnMarking.kEct1)

            val tracker = createTracker()
            tracker.receivedPacket(packet1)
            tracker.receivedPacket(packet2)
            tracker.receivedPacket(packet3)

            val feedbackTime = millis(567)
            val feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(feedbackTime, feedbackInfo)
            feedbackInfo shouldHaveSize 1
            feedbackInfo[0].arrivalTimeOffset shouldBe Duration.between(packet1.arrivalTime, feedbackTime)
            feedbackInfo[0].ecn shouldBe EcnMarking.kCe
        }

        should("report the first arrival time but ECN from CE when received between feedback") {
            val tracker = createTracker()

            val packet = Packet(arrivalTime = millis(123), seq = 1, ecn = EcnMarking.kEct1)
            tracker.receivedPacket(packet)
            tracker.receivedPacket(Packet(arrivalTime = millis(123), seq = 2, ecn = EcnMarking.kEct1))

            val feedbackInfo = mutableListOf<PacketInfo>()
            val feedbackTime1 = millis(567)
            tracker.addPacketsToFeedback(feedbackTime1, feedbackInfo)
            feedbackInfo.shouldContain(seq = 1, received = true).let {
                it.arrivalTimeOffset shouldBe Duration.between(packet.arrivalTime, feedbackTime1)
                it.ecn shouldBe EcnMarking.kEct1
            }

            // Re-receive packet with sequence number=1, but now with CE marking.
            tracker.receivedPacket(Packet(arrivalTime = millis(600), seq = 1, ecn = EcnMarking.kCe))

            // Expect that in the new feedback such packet would be re-reported with 'CE'
            // marking.
            feedbackInfo.clear()
            val feedbackTime2 = millis(700)
            tracker.addPacketsToFeedback(feedbackTime2, feedbackInfo)
            feedbackInfo.shouldContain(seq = 1, received = true).let {
                it.arrivalTimeOffset shouldBe Duration.between(packet.arrivalTime, feedbackTime2)
                it.ecn shouldBe EcnMarking.kCe
            }
        }

        should("generate continuous sequence numbers") {
            val packet1 = Packet(arrivalTime = millis(123), seq = 1)
            // Packet with sequence number 2 is lost or reordered.
            val packet2 = Packet(arrivalTime = millis(125), seq = 3)

            val tracker = createTracker()
            tracker.receivedPacket(packet1)
            tracker.receivedPacket(packet2)

            val feedbackInfo = mutableListOf<PacketInfo>()
            val feedbackTime = millis(567)
            tracker.addPacketsToFeedback(feedbackTime, feedbackInfo)
            feedbackInfo shouldHaveSize 3
            feedbackInfo[0].sequenceNumber shouldBe 1
            feedbackInfo[0].arrivalTimeOffset shouldBe Duration.between(packet1.arrivalTime, feedbackTime)
            feedbackInfo[1].sequenceNumber shouldBe 2
            feedbackInfo[1].shouldBeInstanceOf<UnreceivedPacketInfo>()
            feedbackInfo[2].sequenceNumber shouldBe 3
            feedbackInfo[2].arrivalTimeOffset shouldBe Duration.between(packet2.arrivalTime, feedbackTime)
        }

        should("generate continuous sequence numbers between feedback packets") {
            val packet1 = Packet(arrivalTime = millis(123), seq = 1)
            val packet2 = Packet(arrivalTime = millis(125), seq = 3)

            val tracker = createTracker()
            tracker.receivedPacket(packet1)

            val feedbackInfo = mutableListOf<PacketInfo>()
            var feedbackTime = millis(567)
            tracker.addPacketsToFeedback(feedbackTime, feedbackInfo)
            feedbackInfo shouldHaveSize 1
            feedbackInfo[0].sequenceNumber shouldBe 1
            feedbackInfo[0].arrivalTimeOffset shouldBe Duration.between(packet1.arrivalTime, feedbackTime)

            feedbackInfo.clear()
            feedbackTime = millis(678)
            tracker.receivedPacket(packet2)
            tracker.addPacketsToFeedback(feedbackTime, feedbackInfo)
            feedbackInfo shouldHaveSize 2
            feedbackInfo[0].sequenceNumber shouldBe 2
            feedbackInfo[0].shouldBeInstanceOf<UnreceivedPacketInfo>()
            feedbackInfo[1].sequenceNumber shouldBe 3
            feedbackInfo[1].arrivalTimeOffset shouldBe Duration.between(packet2.arrivalTime, feedbackTime)
        }

        should("generate repeated sequence numbers on reordering between feedback") {
            val packet1 = Packet(arrivalTime = millis(123), seq = 2)
            val packet2 = Packet(arrivalTime = millis(125), seq = 1)
            val packet3 = Packet(arrivalTime = millis(125), seq = 3)

            val tracker = createTracker()
            tracker.receivedPacket(packet1)

            val feedbackInfo = mutableListOf<PacketInfo>()
            var feedbackTime = millis(567)
            tracker.addPacketsToFeedback(feedbackTime, feedbackInfo)
            feedbackInfo shouldHaveSize 1
            feedbackInfo[0].sequenceNumber shouldBe 2
            feedbackInfo[0].arrivalTimeOffset shouldBe Duration.between(packet1.arrivalTime, feedbackTime)

            feedbackInfo.clear()
            feedbackTime = millis(678)
            tracker.receivedPacket(packet2)
            tracker.receivedPacket(packet3)
            tracker.addPacketsToFeedback(feedbackTime, feedbackInfo)
            feedbackInfo shouldHaveSize 3
            feedbackInfo[0].sequenceNumber shouldBe 1
            feedbackInfo[0].arrivalTimeOffset shouldBe Duration.between(packet2.arrivalTime, feedbackTime)
            feedbackInfo[1].sequenceNumber shouldBe 2
            feedbackInfo[1].arrivalTimeOffset shouldBe Duration.between(packet1.arrivalTime, feedbackTime)
            feedbackInfo[2].sequenceNumber shouldBe 3
            feedbackInfo[2].arrivalTimeOffset shouldBe Duration.between(packet3.arrivalTime, feedbackTime)
        }

        should("ignore packets received with too small a sequence number") {
            val tracker = createTracker()
            tracker.receivedPacket(arrivalTime = millis(125), seq = 1065)
            // Packet with backward sequence number jump by more than '64' is ignored
            // as misordered too much.
            tracker.receivedPacket(arrivalTime = millis(130), seq = 1000)

            val feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(135), feedbackInfo)
            feedbackInfo shouldHaveSize 1
            feedbackInfo[0].sequenceNumber shouldBe 1065
        }

        should("create feedback for packets received with a small positive jump in sequence number") {
            val tracker = createTracker()
            tracker.receivedPacket(arrivalTime = millis(125), seq = 1_000)
            tracker.receivedPacket(arrivalTime = millis(130), seq = 1_200)

            val feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(135), feedbackInfo)
            feedbackInfo.size shouldBeGreaterThanOrEqual 2
            feedbackInfo.first().sequenceNumber shouldBe 1_000
            feedbackInfo.last().sequenceNumber shouldBe 1_200
        }

        should("ignore packets received with a large positive jump in sequence number") {
            val tracker = createTracker()
            tracker.receivedPacket(arrivalTime = millis(125), seq = 1_000)
            tracker.receivedPacket(arrivalTime = millis(130), seq = 20_000)

            val feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(135), feedbackInfo)
            feedbackInfo shouldHaveSize 1
            feedbackInfo.first().sequenceNumber shouldBe 1_000
        }

        should("resume producing reports after a backward sequence number jump") {
            val tracker = createTracker()
            tracker.receivedPacket(arrivalTime = millis(130), seq = 10_000)
            tracker.receivedPacket(arrivalTime = millis(140), seq = 1000)

            var feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(150), feedbackInfo)
            // Expect packet with sn=1000 is discarded as received way out of order.
            feedbackInfo shouldHaveSize 1
            feedbackInfo[0].sequenceNumber shouldBe 10_000

            // Continue receiving packets with smaller sequence numbers and generate
            // feedbacks. eventually feedbacks should be non-empty.
            feedbackInfo = mutableListOf()
            tracker.receivedPacket(arrivalTime = millis(160), seq = 1001)
            tracker.receivedPacket(arrivalTime = millis(170), seq = 1002)
            tracker.addPacketsToFeedback(millis(180), feedbackInfo)
            // Due to large sequence number jump, first feedback after such jump might
            // be empty.

            tracker.receivedPacket(arrivalTime = millis(180), seq = 1003)
            tracker.receivedPacket(arrivalTime = millis(190), seq = 1004)
            tracker.addPacketsToFeedback(millis(200), feedbackInfo)
            feedbackInfo.shouldNotBeEmpty()
            feedbackInfo.last().sequenceNumber shouldBe 1004
        }

        should("resume producing reports after a forward sequence number jump") {
            val tracker = createTracker()
            tracker.receivedPacket(arrivalTime = millis(130), seq = 1_000)
            tracker.receivedPacket(arrivalTime = millis(140), seq = 20_000)

            var feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(150), feedbackInfo)
            // Expect packet with sn=20'000 is discarded as received way out of order.
            feedbackInfo shouldHaveSize 1
            feedbackInfo[0].sequenceNumber shouldBe 1_000

            // Continue receiving packets with larger sequence numbers and generate
            // feedbacks. eventually feedbacks should be non-empty.
            feedbackInfo = mutableListOf()
            tracker.receivedPacket(arrivalTime = millis(160), seq = 20_001)
            tracker.receivedPacket(arrivalTime = millis(170), seq = 20_002)
            tracker.addPacketsToFeedback(millis(180), feedbackInfo)
            // Due to large sequence number jump, first feedback after such jump might
            // be empty.

            tracker.receivedPacket(arrivalTime = millis(180), seq = 20_003)
            tracker.receivedPacket(arrivalTime = millis(190), seq = 20_004)
            tracker.addPacketsToFeedback(millis(200), feedbackInfo)
            feedbackInfo.shouldNotBeEmpty()
            feedbackInfo.last().sequenceNumber shouldBe 20_004
        }

        should("not reset state on periods of inactivity") {
            val tracker = createTracker()
            tracker.receivedPacket(arrivalTime = millis(130), seq = 1_000)

            var feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(140), feedbackInfo)
            feedbackInfo.shouldNotBeEmpty()

            feedbackInfo = mutableListOf()
            tracker.addPacketsToFeedback(millis(150), feedbackInfo)
            feedbackInfo.shouldBeEmpty()
            tracker.addPacketsToFeedback(millis(160), feedbackInfo)
            feedbackInfo.shouldBeEmpty()

            tracker.receivedPacket(arrivalTime = millis(170), seq = 998)
            tracker.addPacketsToFeedback(millis(180), feedbackInfo)
            feedbackInfo shouldHaveSize 3
            feedbackInfo[0].sequenceNumber shouldBe 998
            feedbackInfo[2].sequenceNumber shouldBe 1000
        }

        should("accumulate the total number of reported lost packets") {
            val tracker = createTracker()
            tracker.receivedPacket(millis(100), seq = 1)
            tracker.receivedPacket(millis(110), seq = 5)

            // Until reported in a feedback, missed packets are not counted as lost.
            tracker.getStats().numPacketsReportedLost shouldBe 0

            val feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(120), feedbackInfo)
            tracker.getStats().numPacketsReportedLost shouldBe 3 // seq = [2,3,4]

            tracker.receivedPacket(millis(130), seq = 8)
            tracker.addPacketsToFeedback(millis(140), feedbackInfo)
            tracker.getStats().numPacketsReportedLost shouldBe 5 //  [2,3,4,6,7]
        }

        should("not decrease the number of lost packets for recovered packets") {
            val tracker = createTracker()
            tracker.receivedPacket(millis(100), seq = 1)
            tracker.receivedPacket(millis(110), seq = 5)

            val feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(120), feedbackInfo)
            tracker.getStats().numPacketsReportedLost shouldBe 3 // seq = [2,3,4]

            // Recover packet#4, so that only packets #2 and #3 are lost, but total
            // number of reported loss stays the same.
            tracker.receivedPacket(millis(130), seq = 4)
            tracker.addPacketsToFeedback(millis(140), feedbackInfo)
            tracker.getStats().numPacketsReportedLost shouldBe 3
            tracker.getStats().numPacketsReportedRecovered shouldBe 1
        }

        should("count once a packet reported lost twice") {
            val tracker = createTracker()
            tracker.receivedPacket(millis(100), seq = 1)
            tracker.receivedPacket(millis(110), seq = 5)

            var feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(120), feedbackInfo)
            tracker.getStats().numPacketsReportedLost shouldBe 3 // seq = [2,3,4]

            tracker.receivedPacket(millis(130), seq = 2)
            feedbackInfo = mutableListOf()
            tracker.addPacketsToFeedback(millis(140), feedbackInfo)
            // Feedback includes information that packets #3 and #4 are lost.
            feedbackInfo.shouldContain(seq = 3, received = false)
            feedbackInfo.shouldContain(seq = 4, received = false)
            // Those losses are not counted twice.
            tracker.getStats().numPacketsReportedLost shouldBe 3
        }

        should("accumulate the total number of reported recovered packets") {
            val tracker = createTracker()
            tracker.receivedPacket(millis(100), seq = 1)
            tracker.receivedPacket(millis(110), seq = 5)

            var feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(120), feedbackInfo)
            feedbackInfo.shouldContain(seq = 2, received = false)

            tracker.receivedPacket(millis(130), seq = 2)
            // Until reported in a feedback, recovered packets are not counted.
            tracker.getStats().numPacketsReportedRecovered shouldBe 0

            feedbackInfo = mutableListOf()
            tracker.addPacketsToFeedback(millis(140), feedbackInfo)
            feedbackInfo.shouldContain(seq = 2, received = true)
            tracker.getStats().numPacketsReportedRecovered shouldBe 1

            tracker.receivedPacket(millis(150), seq = 3)
            tracker.receivedPacket(millis(160), seq = 4)
            feedbackInfo = mutableListOf()
            tracker.addPacketsToFeedback(millis(170), feedbackInfo)
            feedbackInfo.shouldContain(seq = 3, received = true)
            feedbackInfo.shouldContain(seq = 4, received = true)
            tracker.getStats().numPacketsReportedRecovered shouldBe 3
        }

        should("count once a packet reported as recovered twice") {
            val tracker = createTracker()
            tracker.receivedPacket(millis(100), seq = 1)
            tracker.receivedPacket(millis(110), seq = 5)

            var feedbackInfo = mutableListOf<PacketInfo>()
            tracker.addPacketsToFeedback(millis(120), feedbackInfo)
            feedbackInfo.shouldContain(seq = 4, received = false)

            tracker.receivedPacket(millis(130), seq = 4)
            feedbackInfo = mutableListOf()
            tracker.addPacketsToFeedback(millis(140), feedbackInfo)
            feedbackInfo.shouldContain(seq = 4, received = true)
            // Expect packet#4 is counted as recovered.
            tracker.getStats().numPacketsReportedRecovered shouldBe 1

            tracker.receivedPacket(millis(150), seq = 3)
            feedbackInfo = mutableListOf()
            tracker.addPacketsToFeedback(millis(170), feedbackInfo)
            feedbackInfo.shouldContain(seq = 3, received = true)
            feedbackInfo.shouldContain(seq = 4, received = true)
            // Expect packet#3 is counted as recovered, but packet#4 is not counted twice.
            tracker.getStats().numPacketsReportedRecovered shouldBe 2
        }
    }
}
