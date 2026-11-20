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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.per
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.ReceivedPacketInfo
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacket
import org.jitsi.utils.abs
import org.jitsi.utils.div
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import java.time.Instant

/**
 * Unit tests for CongestionControlFeedbackGenerator.
 *
 * Based on WebRTC modules/remote_bitrate_estimator/congestion_control_feedback_generator_unittest.cc in
 * WebRTC 3987dfb7b9 (October 2026).
 */
class CongestionControlFeedbackGeneratorTest : ShouldSpec() {
    private val logger = createLogger()

    private class Packet(
        val arrivalTime: Instant,
        val marker: Boolean,
        val ssrc: Long = 1234,
        val seq: Int = 1,
        val ecn: EcnMarking = EcnMarking.kNotEct
    )

    private fun CongestionControlFeedbackGenerator.onReceivedPacket(packet: Packet) =
        onReceivedPacket(packet.ssrc, packet.seq, packet.marker, packet.arrivalTime, packet.ecn)

    private fun createClock() = FakeClock().apply { setTime(Instant.ofEpochSecond(0, 123_456_000)) }

    init {
        // If possible feedback should  be sent when a packet with marker bit is
        // received in order to provide feedback as soon as possible after receiving a
        // complete frame. On good networks, this means that a sender may receive
        // feedback for every sent frame.
        should("send feedback after a packet with marker bit is received") {
            val sent = mutableListOf<RtcpFbCcfbPacket>()
            val clock = createClock()
            val generator = CongestionControlFeedbackGenerator(clock, logger) { sent.add(it) }

            generator.process(clock.instant()) shouldBeGreaterThan 10.ms
            clock.elapse(10.ms)

            generator.onReceivedPacket(Packet(clock.instant(), marker = false))
            sent shouldHaveSize 0
            generator.onReceivedPacket(Packet(clock.instant(), marker = true))
            sent shouldHaveSize 1
        }

        should("send feedback delayed if no packet with marker bit is received") {
            val sentTimes = mutableListOf<Instant>()
            val clock = createClock()
            val generator = CongestionControlFeedbackGenerator(clock, logger) { sentTimes.add(clock.instant()) }

            var timeToNext = generator.process(clock.instant())
            timeToNext shouldBe 25.ms
            clock.elapse(10.ms)

            generator.onReceivedPacket(Packet(clock.instant(), marker = false))
            // Expect feedback to be delayed another 25ms since no packet with marker is
            // received.
            val expectedFeedbackTime = clock.instant() + 25.ms

            clock.elapse(timeToNext - 10.ms)
            timeToNext = generator.process(clock.instant())
            clock.elapse(timeToNext)
            generator.process(clock.instant())

            sentTimes shouldBe listOf(expectedFeedbackTime)
        }

        should("send feedback after the minimum time if packets with marker bit are received") {
            val sentTimes = mutableListOf<Instant>()
            val kSmallTimeInterval = 2.ms
            val clock = createClock()
            val generator = CongestionControlFeedbackGenerator(clock, logger) { sentTimes.add(clock.instant()) }

            var timeToNextProcess = generator.process(clock.instant())
            val firstFeedbackTime = clock.instant()

            // 3 packets are received, with an interval kSmallTimeInterval.
            repeat(3) {
                generator.onReceivedPacket(Packet(clock.instant(), marker = true))
                clock.elapse(kSmallTimeInterval)
                timeToNextProcess -= kSmallTimeInterval
            }
            clock.elapse(timeToNextProcess)
            timeToNextProcess = generator.process(clock.instant())
            clock.elapse(timeToNextProcess)
            generator.process(clock.instant())

            // Next feedback can not be sent until 25ms after the previous
            sentTimes shouldBe listOf(firstFeedbackTime, firstFeedbackTime + 25.ms)
        }

        should("use less than 500 kbit/s for feedback for 30K packets") {
            val clock = createClock()
            var numberOfFeedbackPackets = 0
            var totalFeedbackSize = 0.bytes
            val generator = CongestionControlFeedbackGenerator(clock, logger) {
                numberOfFeedbackPackets++
                totalFeedbackSize += it.length.bytes
            }

            val startTime = clock.instant()
            var lastProcessTime = clock.instant()
            var timeToNextProcess = generator.process(clock.instant())
            var rtpSequenceNumber = 0
            // Receive 30 packet per ms in 1s => 30'0000 packets.
            while (clock.instant() < startTime + 1.secs) {
                repeat(30) {
                    generator.onReceivedPacket(
                        Packet(clock.instant(), marker = true, ssrc = 1234, seq = rtpSequenceNumber)
                    )
                    rtpSequenceNumber = (rtpSequenceNumber + 1) and 0xFFFF
                }
                if (clock.instant() >= lastProcessTime + timeToNextProcess) {
                    lastProcessTime = clock.instant()
                    timeToNextProcess = generator.process(clock.instant())
                }
                clock.elapse(1.ms)
            }
            totalFeedbackSize.per(1.secs) shouldBeLessThanOrEqualTo 500.kbps
            numberOfFeedbackPackets shouldBeGreaterThanOrEqual 39
        }

        should("send feedback every 25 ms for 200 Mbit/s") {
            val clock = createClock()
            var numberOfFeedbackPackets = 0
            var totalFeedbackSize = 0.bytes
            var lastFeedbackTime: Instant? = null
            val intervals = mutableSetOf<Duration>()
            val generator = CongestionControlFeedbackGenerator(clock, logger) {
                numberOfFeedbackPackets++
                totalFeedbackSize += (it.length + 42).bytes
                lastFeedbackTime?.let { last -> intervals.add(Duration.between(last, clock.instant())) }
                lastFeedbackTime = clock.instant()
            }

            val startTime = clock.instant()
            var lastProcessTime = clock.instant()
            var timeToNextProcess = generator.process(clock.instant())
            var rtpSequenceNumber = 0
            // 200 Mbps with 1000-byte packets means 25000 packets/s.
            // That's 25 packets per millisecond.
            // We run for 1 second.
            while (clock.instant() < startTime + 1.secs) {
                for (i in 0 until 25) {
                    generator.onReceivedPacket(
                        Packet(clock.instant(), marker = i == 24, ssrc = 1234, seq = rtpSequenceNumber)
                    )
                    rtpSequenceNumber = (rtpSequenceNumber + 1) and 0xFFFF
                }
                if (clock.instant() >= lastProcessTime + timeToNextProcess) {
                    lastProcessTime = clock.instant()
                    timeToNextProcess = generator.process(clock.instant())
                }
                clock.elapse(1.ms)
            }

            intervals shouldBe setOf(25.ms)
            // With 25ms intervals, we expect exactly 40 feedback packets in 1 second.
            numberOfFeedbackPackets shouldBe 40

            // Each feedback packet reports 625 packets:
            // Size = header(20) + reports(625 * 2) + padding(2) + overhead(42) = 1314
            // bytes. 40 packets * 1314 bytes = 52560 bytes = 420.48 kbps.
            val duration = Duration.between(startTime, clock.instant())
            val averageBitrate = totalFeedbackSize.per(duration)
            averageBitrate.kbps shouldBe (420.0 plusOrMinus 10.0)
        }

        should("generate an RTCP packet from two SSRCs with missing packets and wrap") {
            val sent = mutableListOf<RtcpFbCcfbPacket>()
            val clock = createClock()
            val kSmallTimeInterval = 2.ms
            val generator = CongestionControlFeedbackGenerator(clock, logger) { sent.add(it) }

            var timeToNextProcess = generator.process(clock.instant())
            // Receive packets out of order, with missing packets (between  0xFFA and 1 =
            // 6  and FFFC and 1 = 4) => total 14 packets is expected in the feedback.
            val kReceivedPackets = listOf(
                // Reordered packet.
                Packet(clock.instant() + kSmallTimeInterval, marker = false, ssrc = 123, seq = 0xFFFA),
                Packet(clock.instant(), marker = false, ssrc = 123, seq = 1),
                // Reordered packet.
                Packet(clock.instant() + kSmallTimeInterval, marker = false, ssrc = 234, seq = 0xFFFC),
                Packet(clock.instant(), marker = false, ssrc = 234, seq = 1)
            )

            val receiveTimeSorted = kReceivedPackets.sortedBy { it.arrivalTime }
            for (packet in receiveTimeSorted) {
                val timeToReceive = Duration.between(clock.instant(), packet.arrivalTime)
                timeToNextProcess -= timeToReceive
                clock.elapse(timeToReceive)
                generator.onReceivedPacket(packet)
            }
            clock.elapse(timeToNextProcess)
            timeToNextProcess = generator.process(clock.instant())
            clock.elapse(timeToNextProcess)
            generator.process(clock.instant())

            sent shouldHaveSize 1
            val packets = sent[0].packets
            packets shouldHaveSize 14
            packets.filter { it.ssrc == 123L }.map { it.sequenceNumber } shouldBe
                listOf(0xFFFA, 0xFFFB, 0xFFFC, 0xFFFD, 0xFFFE, 0xFFFF, 0, 1)
            packets.filter { it.ssrc == 234L }.map { it.sequenceNumber } shouldBe
                listOf(0xFFFC, 0xFFFD, 0xFFFE, 0xFFFF, 0, 1)
            packets.filterIsInstance<ReceivedPacketInfo>() shouldHaveSize 4
        }

        should("report the first received packet's arrival time but ECN from a CE packet if duplicate") {
            val sent = mutableListOf<RtcpFbCcfbPacket>()
            val sentTimes = mutableListOf<Instant>()
            val clock = createClock()
            val kSmallTimeInterval = 2.ms
            val generator = CongestionControlFeedbackGenerator(clock, logger) {
                sent.add(it)
                sentTimes.add(clock.instant())
            }

            var timeToNextProcess = generator.process(clock.instant())
            val packet1 = Packet(clock.instant(), marker = false, ssrc = 1, seq = 2, ecn = EcnMarking.kEct1)
            generator.onReceivedPacket(packet1)

            val packet2 = Packet(
                clock.instant() + kSmallTimeInterval,
                marker = false,
                ssrc = 1,
                seq = 2,
                ecn = EcnMarking.kCe
            )
            timeToNextProcess -= kSmallTimeInterval
            clock.elapse(kSmallTimeInterval)
            generator.onReceivedPacket(packet2)

            clock.elapse(timeToNextProcess)
            timeToNextProcess = generator.process(clock.instant())
            clock.elapse(timeToNextProcess)
            generator.process(clock.instant())

            sent shouldHaveSize 1
            sent[0].packets shouldHaveSize 1
            val reported = sent[0].packets[0].shouldBeInstanceOf<ReceivedPacketInfo>()
            reported.ecn shouldBe EcnMarking.kCe
            // The offset has been through the packet's 1/1024 second resolution.
            val expectedOffset = Duration.between(packet1.arrivalTime, sentTimes[0])
            abs(reported.arrivalTimeOffset - expectedOffset) shouldBeLessThan 1.secs / 1024
        }

        should("limit feedback to a fraction of the send BWE") {
            val clock = createClock()
            var numberOfFeedbackPackets = 0
            var totalFeedbackSize = 0.bytes
            // Enable 5% feedback fraction limit
            val generator = CongestionControlFeedbackGenerator(clock, logger, maxFeedbackFraction = 0.05) {
                numberOfFeedbackPackets++
                totalFeedbackSize += (it.length + 42).bytes
            }

            // Notify the generator that send BWE is 100 kbps.
            // 5% limit means CCFB capacity is 5 kbps = 625 bytes/sec.
            generator.onSendBandwidthEstimateChanged(100.kbps, isBandwidthLimited = true, transportOverhead = 42.bytes)

            val startTime = clock.instant()
            var lastProcessTime = clock.instant()
            var timeToNextProcess = generator.process(clock.instant())
            var rtpSequenceNumber = 0

            // Receive 1 packet every 20 ms for 10 seconds
            while (clock.instant() < startTime + 10.secs) {
                generator.onReceivedPacket(
                    Packet(clock.instant(), marker = true, ssrc = 1234, seq = rtpSequenceNumber++)
                )
                if (clock.instant() >= lastProcessTime + timeToNextProcess) {
                    lastProcessTime = clock.instant()
                    timeToNextProcess = generator.process(clock.instant())
                }
                clock.elapse(20.ms)
            }

            // Verify total feedback rate is strictly bounded by 5% of 100 kbps (5000
            // bps). We allow slightly over 5000 bps (e.g. 5500 bps) to account for packet
            // block granularity.
            totalFeedbackSize.per(10.secs).bps shouldBeLessThanOrEqualTo 5500
            numberOfFeedbackPackets shouldBeGreaterThanOrEqual 1
        }

        should("send feedback at least every 250 ms despite the fraction limit") {
            val clock = createClock()
            var numberOfFeedbackPackets = 0
            var lastFeedbackTime: Instant? = null
            val intervals = mutableSetOf<Duration>()
            // Enable 5% feedback fraction limit
            val generator = CongestionControlFeedbackGenerator(clock, logger, maxFeedbackFraction = 0.05) {
                numberOfFeedbackPackets++
                lastFeedbackTime?.let { last -> intervals.add(Duration.between(last, clock.instant())) }
                lastFeedbackTime = clock.instant()
            }

            // Notify the generator that send BWE is extremely low (10 kbps).
            // 5% limit means CCFB capacity is 500 bps = 62.5 bytes/sec.
            generator.onSendBandwidthEstimateChanged(10.kbps, isBandwidthLimited = true, transportOverhead = 42.bytes)

            val startTime = clock.instant()
            var lastProcessTime = clock.instant()
            var timeToNextProcess = generator.process(clock.instant())
            var rtpSequenceNumber = 0

            // Receive 1 packet every 10 ms for 1 second.
            while (clock.instant() < startTime + 1.secs) {
                if (Duration.between(startTime, clock.instant()).toMillis() % 10 == 0L) {
                    generator.onReceivedPacket(
                        Packet(clock.instant(), marker = true, ssrc = 1234, seq = rtpSequenceNumber++)
                    )
                }
                if (clock.instant() >= lastProcessTime + timeToNextProcess) {
                    lastProcessTime = clock.instant()
                    timeToNextProcess = generator.process(clock.instant())
                }
                clock.elapse(1.ms)
            }

            // Pacing debt delay is ~1050ms, but clamped to 250ms.
            intervals shouldBe setOf(250.ms)
            // 1000ms / 250ms = 4 feedback packets.
            numberOfFeedbackPackets shouldBe 4
        }

        should("ignore the fraction limit when not bandwidth limited") {
            val clock = createClock()
            var numberOfFeedbackPackets = 0
            var lastFeedbackTime: Instant? = null
            val intervals = mutableSetOf<Duration>()
            // Enable 5% feedback fraction limit
            val generator = CongestionControlFeedbackGenerator(clock, logger, maxFeedbackFraction = 0.05) {
                numberOfFeedbackPackets++
                lastFeedbackTime?.let { last -> intervals.add(Duration.between(last, clock.instant())) }
                lastFeedbackTime = clock.instant()
            }

            // Notify the generator that send BWE is extremely low (10 kbps) but we are
            // NOT bandwidth limited.
            generator.onSendBandwidthEstimateChanged(10.kbps, isBandwidthLimited = false, transportOverhead = 42.bytes)

            val startTime = clock.instant()
            var lastProcessTime = clock.instant()
            var timeToNextProcess = generator.process(clock.instant())
            var rtpSequenceNumber = 0

            // Receive 1 packet every 10 ms for 1 second.
            while (clock.instant() < startTime + 1.secs) {
                if (Duration.between(startTime, clock.instant()).toMillis() % 10 == 0L) {
                    generator.onReceivedPacket(
                        Packet(clock.instant(), marker = true, ssrc = 1234, seq = rtpSequenceNumber++)
                    )
                }
                if (clock.instant() >= lastProcessTime + timeToNextProcess) {
                    lastProcessTime = clock.instant()
                    timeToNextProcess = generator.process(clock.instant())
                }
                clock.elapse(1.ms)
            }

            // Pacing delay should be 25ms (minTimeBetweenFeedback)
            // because the 10kbps limit is ignored.
            intervals shouldBe setOf(25.ms)
            // 1000ms / 25ms = 40 feedback packets.
            numberOfFeedbackPackets shouldBe 40
        }
    }
}
