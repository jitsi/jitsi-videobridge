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
package org.jitsi.nlj.rtp.scream

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.rtp.bandwidthestimation2.PacketResult
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.utils.NEVER
import org.jitsi.utils.ms
import java.time.Duration
import java.time.Instant

/** Unit tests for ScreamFeedback,
 * based on WebRTC modules/congestion_controller/scream/scream_feedback_unittest.cc in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 */
class ScreamFeedbackTest : FreeSpec() {
    init {
        "ParsesEmptyFeedback" {
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1000)
            msg.dataInFlight = 5000.bytes

            val parsed = parseScreamFeedback(msg)

            parsed.feedbackTime shouldBe Instant.ofEpochMilli(1000)
            parsed.dataInFlight shouldBe 5000.bytes
            parsed.numReceivedPackets shouldBe 0
            parsed.numCeMarkedPackets shouldBe 0
            parsed.ackedNotMarkedSize shouldBe DataSize.ZERO
            parsed.delayMetrics shouldBe null
            parsed.numLostPackets shouldBe 0
            parsed.numRecoveredPackets shouldBe 0
        }

        "ParsesReceivedPacketsMetrics" {
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1050)
            msg.dataInFlight = 2000.bytes

            // Packet 1: Received, Not CE-marked
            val packet1 = PacketResult()
            packet1.sentPacket.size = 1000.bytes
            packet1.sentPacket.sendTime = Instant.ofEpochMilli(900)
            packet1.receiveTime = Instant.ofEpochMilli(950)
            packet1.arrivalTimeOffset = 5.ms
            packet1.ecn = EcnMarking.kNotEct

            // Packet 2: Received, CE-marked
            val packet2 = PacketResult()
            packet2.sentPacket.size = 1200.bytes
            packet2.sentPacket.sendTime = Instant.ofEpochMilli(910)
            packet2.receiveTime = Instant.ofEpochMilli(970)
            packet2.arrivalTimeOffset = 10.ms
            packet2.ecn = EcnMarking.kCe

            msg.packetFeedbacks.add(packet1)
            msg.packetFeedbacks.add(packet2)

            val params = ScreamV2Parameters()
            val parsed = parseScreamFeedback(msg, params)

            parsed.numReceivedPackets shouldBe 2
            parsed.numCeMarkedPackets shouldBe 1

            // Only packet1 is not CE-marked, so size should be 1000 bytes
            parsed.ackedNotMarkedSize shouldBe 1000.bytes

            // Packet 1 one-way delay = 950 - 900 = 50ms
            // Packet 2 one-way delay = 970 - 910 = 60ms
            // Both packets are within the 25ms tail window.
            parsed.delayMetrics shouldNotBe null
            val delayMetrics = parsed.delayMetrics!!
            delayMetrics.minOneWayDelay shouldBe 50.ms
            delayMetrics.maxOneWayDelay shouldBe 60.ms
            delayMetrics.maxOneWayDelay - delayMetrics.minOneWayDelay shouldBe 10.ms

            // hold_time = last_packet.receive_time + offset - first_packet.receive_time
            // hold_time = 970 + 10 - 950 = 30ms
            delayMetrics.feedbackHoldTime shouldBe 30.ms

            // rtt_sample = feedback_time - last_packet.send_time -
            // last_packet.arrival_time_offset rtt_sample = 1050 - 910 - 10 = 130ms
            delayMetrics.rttSample shouldBe 130.ms
        }

        "IgnoresDelayOfPacketsSentBeforeTailWindow" {
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1200)

            // Packet 1 sent at 900ms, received at 950ms (owd = 50ms).
            val packet1 = PacketResult()
            packet1.sentPacket.sendTime = Instant.ofEpochMilli(900)
            packet1.receiveTime = Instant.ofEpochMilli(950)

            // Packet 2 sent at 950ms (50ms > 25ms burst window), received at 1020ms
            // (owd = 70ms).
            val packet2 = PacketResult()
            packet2.sentPacket.sendTime = Instant.ofEpochMilli(950)
            packet2.receiveTime = Instant.ofEpochMilli(1020)

            msg.packetFeedbacks.add(packet1)
            msg.packetFeedbacks.add(packet2)

            // With default 25ms threshold and 50ms gap > 3ms max gap, packet 1 is
            // excluded from the tail window.
            val params = ScreamV2Parameters()
            val parsed = parseScreamFeedback(msg, params)

            parsed.delayMetrics shouldNotBe null
            val delayMetrics = parsed.delayMetrics!!
            delayMetrics.minOneWayDelay shouldBe 70.ms
            delayMetrics.maxOneWayDelay shouldBe 70.ms
            delayMetrics.maxOneWayDelay - delayMetrics.minOneWayDelay shouldBe Duration.ZERO
        }

        "ExtendsBurstWindowIfConsecutivePacketsWithinGap" {
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1200)

            // Packet 1 sent at 0ms (owd = 50ms).
            val p1 = PacketResult()
            p1.sentPacket.sendTime = Instant.ofEpochMilli(0)
            p1.receiveTime = Instant.ofEpochMilli(50)

            // Packet 2 sent at 1ms (owd = 52ms).
            val p2 = PacketResult()
            p2.sentPacket.sendTime = Instant.ofEpochMilli(1)
            p2.receiveTime = Instant.ofEpochMilli(53)

            // Packet 3 sent at 26ms (owd = 55ms).
            val p3 = PacketResult()
            p3.sentPacket.sendTime = Instant.ofEpochMilli(26)
            p3.receiveTime = Instant.ofEpochMilli(81)

            msg.packetFeedbacks = mutableListOf(p1, p2, p3)

            // Last packet is at 26ms.
            // Packet 2 (1ms) is within burst_window_min (25ms).
            // Packet 1 (0ms) is 26ms from the end (> 25ms, <= 50ms), and the gap to
            // Packet 2 is 1ms (<= 3ms burst_window_max_gap). It should be included.
            val params = ScreamV2Parameters()
            val parsed = parseScreamFeedback(msg, params)

            parsed.delayMetrics shouldNotBe null
            val delayMetrics = parsed.delayMetrics!!
            delayMetrics.minOneWayDelay shouldBe 50.ms
            delayMetrics.maxOneWayDelay shouldBe 55.ms
            delayMetrics.maxOneWayDelay - delayMetrics.minOneWayDelay shouldBe 5.ms
        }

        "CapsBurstWindowAtMaxWindow" {
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1200)

            // Continuously paced packets every 2ms from 0ms to 100ms.
            for (t in 0..100 step 2) {
                val p = PacketResult()
                p.sentPacket.sendTime = Instant.ofEpochMilli(t.toLong())
                // Earlier packets (< 50ms) have delay 10ms, tail packets have delay 60ms.
                p.receiveTime = Instant.ofEpochMilli((t + (if (t < 50) 10 else 60)).toLong())
                msg.packetFeedbacks.add(p)
            }

            // Last packet is at 100ms.
            // With burst_window_max = 50ms, window cannot extend before 50ms.
            // All packets in [50ms, 100ms] have delay 60ms. Packets < 50ms have delay
            // 10ms.
            val params = ScreamV2Parameters()
            val parsed = parseScreamFeedback(msg, params)

            parsed.delayMetrics shouldNotBe null
            parsed.delayMetrics!!.minOneWayDelay shouldBe 60.ms
            parsed.delayMetrics!!.maxOneWayDelay shouldBe 60.ms
        }

        "RttAndOwdIncreaseIfDelayIncreasesWithinFeedbackMessage" {
            val params = ScreamV2Parameters()

            // A feedback message where delay increases from early to late packets:
            // - Early packet sent 200ms before the tail had low delay (50ms).
            // - Mid and late packets sent within the tail burst window had increased
            //   delay (130ms and 150ms).
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1350)

            val earlyPacket = PacketResult()
            earlyPacket.sentPacket.sendTime = Instant.ofEpochMilli(1000)
            earlyPacket.receiveTime = Instant.ofEpochMilli(1050) // owd = 50ms

            val midPacket = PacketResult()
            midPacket.sentPacket.sendTime = Instant.ofEpochMilli(1190)
            midPacket.receiveTime = Instant.ofEpochMilli(1320) // owd = 130ms

            val latePacket = PacketResult()
            latePacket.sentPacket.sendTime = Instant.ofEpochMilli(1200)
            latePacket.receiveTime = Instant.ofEpochMilli(1350) // owd = 150ms

            msg.packetFeedbacks = mutableListOf(earlyPacket, midPacket, latePacket)

            val parsed = parseScreamFeedback(msg, params)

            // Because early_packet is outside the tail burst window (sent 200ms before
            // late_packet), its 50ms delay is excluded. One-way delay and RTT sample
            // reflect the increased delay in the recent burst.
            parsed.delayMetrics shouldNotBe null
            val delayMetrics = parsed.delayMetrics!!
            delayMetrics.minOneWayDelay shouldBe 130.ms
            delayMetrics.maxOneWayDelay shouldBe 150.ms
            delayMetrics.rttSample shouldBe 150.ms
        }

        "ParsesLostAndRecoveredPackets" {
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1000)

            // Packet 1: Lost for the first time
            val packet1 = PacketResult()
            packet1.receiveTime = NEVER
            packet1.reportedLostForTheFirstTime = true

            // Packet 2: Recovered for the first time
            val packet2 = PacketResult()
            packet2.sentPacket.sendTime = Instant.ofEpochMilli(850)
            packet2.receiveTime = Instant.ofEpochMilli(900)
            packet2.reportedRecoveredForTheFirstTime = true

            msg.packetFeedbacks.add(packet1)
            msg.packetFeedbacks.add(packet2)

            val parsed = parseScreamFeedback(msg)

            parsed.numLostPackets shouldBe 1
            parsed.numRecoveredPackets shouldBe 1
        }

        "ParsesNegativeOneWayDelay" {
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1050)

            val packet1 = PacketResult()
            packet1.sentPacket.sendTime = Instant.ofEpochMilli(1000)
            packet1.receiveTime = Instant.ofEpochMilli(900) // -100ms OWD
            packet1.sentPacket.size = 1000.bytes

            val packet2 = PacketResult()
            packet2.sentPacket.sendTime = Instant.ofEpochMilli(1000)
            packet2.receiveTime = Instant.ofEpochMilli(920) // -80ms OWD
            packet2.sentPacket.size = 1000.bytes

            msg.packetFeedbacks.add(packet1)
            msg.packetFeedbacks.add(packet2)

            val parsed = parseScreamFeedback(msg)

            parsed.delayMetrics shouldNotBe null
            val delayMetrics = parsed.delayMetrics!!
            delayMetrics.minOneWayDelay shouldBe (-100).ms
            delayMetrics.maxOneWayDelay shouldBe (-80).ms
            delayMetrics.maxOneWayDelay - delayMetrics.minOneWayDelay shouldBe 20.ms
        }

        "ExcludesAmbiguousReceiveTimePacketsFromDelayAndRtt" {
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1050)

            // Packet 1: Unambiguous
            val packet1 = PacketResult()
            packet1.sentPacket.sendTime = Instant.ofEpochMilli(900)
            packet1.receiveTime = Instant.ofEpochMilli(950) // 50ms OWD
            packet1.arrivalTimeOffset = 10.ms
            packet1.sentPacket.size = 1000.bytes
            packet1.ambiguousReceiveTime = false

            // Packet 2: Ambiguous (e.g. retransmission without RTX) with corrupted OWD
            val packet2 = PacketResult()
            packet2.sentPacket.sendTime = Instant.ofEpochMilli(910)
            packet2.receiveTime = Instant.ofEpochMilli(780) // -130ms OWD
            packet2.arrivalTimeOffset = 200.ms
            packet2.sentPacket.size = 1200.bytes
            packet2.ambiguousReceiveTime = true

            msg.packetFeedbacks.add(packet1)
            msg.packetFeedbacks.add(packet2)

            val parsed = parseScreamFeedback(msg)

            // Both packets are counted as received and bytes in flight are reduced.
            parsed.numReceivedPackets shouldBe 2
            parsed.received shouldBe 2200.bytes

            // Delay and RTT ignore the ambiguous packet and use the unambiguous packet.
            parsed.delayMetrics shouldNotBe null
            val delayMetrics = parsed.delayMetrics!!
            delayMetrics.minOneWayDelay shouldBe 50.ms
            delayMetrics.maxOneWayDelay shouldBe 50.ms

            // rtt_sample = feedback_time (1050) - packet1.send_time (900) - packet1.ato
            // (10) = 140ms
            delayMetrics.rttSample shouldBe 140.ms
            delayMetrics.lastPacketReceiveTime shouldBe Instant.ofEpochMilli(950)
            delayMetrics.feedbackHoldTime shouldBe 10.ms
        }

        "AllPacketsAmbiguousLeavesDelayAndRttUnset" {
            val msg = TransportPacketsFeedback()
            msg.feedbackTime = Instant.ofEpochMilli(1050)

            val packet = PacketResult()
            packet.sentPacket.sendTime = Instant.ofEpochMilli(900)
            packet.receiveTime = Instant.ofEpochMilli(770)
            packet.arrivalTimeOffset = 200.ms
            packet.sentPacket.size = 1000.bytes
            packet.ambiguousReceiveTime = true

            msg.packetFeedbacks.add(packet)

            val parsed = parseScreamFeedback(msg)

            parsed.numReceivedPackets shouldBe 1
            parsed.received shouldBe 1000.bytes
            parsed.delayMetrics shouldBe null
        }
    }
}
