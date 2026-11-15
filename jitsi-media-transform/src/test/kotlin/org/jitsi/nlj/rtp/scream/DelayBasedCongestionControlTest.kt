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
// This file uses WebRTC's naming style for constants
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.scream

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.rtp.bandwidthestimation2.PacketResult
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.rtp.scream.test.BuiltInNetworkBehaviorConfig
import org.jitsi.nlj.rtp.scream.test.CcFeedbackGenerator
import org.jitsi.nlj.util.kbps
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import org.jitsi.utils.toMicros
import java.time.Duration
import java.time.Instant

private fun createClock() = FakeClock().apply { setTime(Instant.ofEpochSecond(1234)) }

/** `TimeDelta::ms<double>()` in WebRTC. */
private fun Duration.toMsDouble(): Double = toMicros() / 1000.0

/** Unit tests for DelayBasedCongestionControl,
 * based on WebRTC modules/congestion_controller/scream/delay_based_congestion_control_unittest.cc in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 */
class DelayBasedCongestionControlTest : FreeSpec() {
    init {
        "InfiniteQueueBeforeFirstFeedback" {
            val delayController = DelayBasedCongestionControl(ScreamV2Parameters())
            delayController.queueDelay() shouldBe MAX_DURATION
        }

        "QueueDelayDoesNotIncreaseIfSendRateIsLow" {
            val clock = createClock()
            val delayController = DelayBasedCongestionControl(ScreamV2Parameters())

            // Note, network_config.queue_delay_ms is the one way delay in the simulation,
            // not a delay caused by queues.
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 1000.kbps)
                )
            )

            // Sending at a rate below link capacity should not cause queue delay to
            // increase.
            for (i in 0 until 10) {
                val sendRate = 100.kbps
                val feedback = feedbackGenerator.processUntilNextFeedback(sendRate, clock)

                delayController.update(parseScreamFeedback(feedback), alr = false)
                delayController.rtt() shouldBe 58.ms
                delayController.queueDelay() shouldBe 0.ms
                delayController.isQueueDelayDetected() shouldBe false
            }
        }

        "QueueDelayIncreaseIfSendRateIsHigh" {
            val clock = createClock()
            val delayController = DelayBasedCongestionControl(ScreamV2Parameters())
            // Note, network_config.queue_delay_ms is the one way delay in the simulation,
            // not a delay caused by queues.
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 1000.kbps)
                )
            )

            for (i in 0 until 10) {
                // Send faster than link capacity to build a queue.
                val sendRate = 2000.kbps
                val feedback = feedbackGenerator.processUntilNextFeedback(sendRate, clock)
                delayController.update(parseScreamFeedback(feedback), alr = false)
            }
            delayController.queueDelay() shouldBeGreaterThan 50.ms
            delayController.isQueueDelayDetected() shouldBe true
        }

        "ResetQueueDelay" {
            val clock = createClock()
            val delayController = DelayBasedCongestionControl(ScreamV2Parameters())
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 100.kbps)
                )
            )

            val startTime = clock.instant()
            delayController.queueDelay() shouldBe MAX_DURATION

            // Overuse the link during 1s.
            var lastSmoothedRtt = Duration.ZERO
            while (clock.instant() < startTime + 1.secs) {
                val feedback = feedbackGenerator.processUntilNextFeedback(150.kbps, clock)
                delayController.update(parseScreamFeedback(feedback), alr = false)
                lastSmoothedRtt = delayController.rtt()
            }
            val queueDelayBeforeReset = delayController.queueDelay()
            queueDelayBeforeReset shouldBeGreaterThan Duration.ZERO
            queueDelayBeforeReset shouldBeLessThan MAX_DURATION

            delayController.resetQueueDelay()
            delayController.queueDelay() shouldBe MAX_DURATION

            val feedback = feedbackGenerator.processUntilNextFeedback(150.kbps, clock)
            delayController.update(parseScreamFeedback(feedback), alr = false)
            // RTT is still increasing or equal to the last feedback.
            delayController.rtt() shouldBeGreaterThanOrEqualTo lastSmoothedRtt
            // But queue delay should be lower.
            delayController.queueDelay() shouldBeLessThan queueDelayBeforeReset
        }

        "IsQueueDrainedInTimeReturnFalseIfLongOverUse" {
            val clock = createClock()
            val delayController = DelayBasedCongestionControl(ScreamV2Parameters())
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 100.kbps)
                )
            )

            val startTime = clock.instant()
            while (clock.instant() < startTime + 30.secs && delayController.isQueueDrainedInTime(clock.instant())) {
                val feedback = feedbackGenerator.processUntilNextFeedback(150.kbps, clock)
                delayController.update(parseScreamFeedback(feedback), alr = false)
            }
            clock.instant() shouldBeLessThan startTime + 30.secs
            clock.instant() shouldBeGreaterThan startTime + 10.secs
            delayController.isQueueDrainedInTime(clock.instant()) shouldBe false
        }

        "RefWindowScaleFactorDueToMinAverageQueueDelay" {
            val clock = createClock()
            val params = ScreamV2Parameters(reduceJitter = false)
            val delayController = DelayBasedCongestionControl(params)

            val kQueueDelyMinThreshold = params.queueDelayMinThreshold

            // Helper to establish queue delay.
            // Assumes base delay is 100ms.
            fun feedPackets(qdelay: Duration, count: Int) {
                for (i in 0 until count) {
                    clock.elapse(10.ms)
                    val msg = TransportPacketsFeedback()
                    msg.feedbackTime = clock.instant()
                    val packet = PacketResult()
                    packet.receiveTime = clock.instant()
                    packet.sentPacket.sendTime = clock.instant() - 100.ms - qdelay
                    packet.sentPacket.sequenceNumber = i.toLong()
                    msg.packetFeedbacks.add(packet)
                    delayController.update(parseScreamFeedback(msg), alr = false)
                }
            }

            // Establish base delay (100ms) with qdelay=0.
            feedPackets(Duration.ZERO, 100)
            delayController.refWindowScaleFactorDueToAvgMinDelay() shouldBe 1.0

            // Drive queue delay to kQueueDelyMinThreshold.
            feedPackets(kQueueDelyMinThreshold + 10.ms, 250)
            delayController.refWindowScaleFactorDueToAvgMinDelay() shouldBe (0.1 plusOrMinus 0.01)

            // Drive queue delay to `0.5 * kQueueDelyMinThreshold`.
            // Expected: 0.1 + 1.2 * 0.5 = 0.7
            feedPackets(kQueueDelyMinThreshold.dividedBy(2), 250)
            delayController.refWindowScaleFactorDueToAvgMinDelay() shouldBe (0.7 plusOrMinus 0.01)

            // Drive queue delay to `0.25 * kQueueDelyMinThreshold`.
            // Expected: 0.1 + 1.2 * 0.75 = 1.0.
            feedPackets(kQueueDelyMinThreshold.dividedBy(4), 250)
            delayController.refWindowScaleFactorDueToAvgMinDelay() shouldBe (1.0 plusOrMinus 0.01)
        }

        "RefWindowScaleFactorDueToLatencyDifference" {
            val clock = createClock()
            val params = ScreamV2Parameters(reduceJitter = false)
            val delayController = DelayBasedCongestionControl(params)

            val kLatencyThreshold = params.latencyDiffThreshold

            // Helper to establish latency differences within feedbacks.
            // Assumes a base one-way delay of 100ms.
            fun feedPackets(latencyDiff: Duration, count: Int) {
                for (i in 0 until count) {
                    clock.elapse(10.ms)
                    val msg = TransportPacketsFeedback()
                    msg.feedbackTime = clock.instant()

                    val packet1 = PacketResult()
                    packet1.receiveTime = clock.instant()
                    packet1.sentPacket.sendTime = clock.instant() - 100.ms
                    packet1.sentPacket.sequenceNumber = (i * 2).toLong()

                    val packet2 = PacketResult()
                    packet2.receiveTime = clock.instant()
                    packet2.sentPacket.sendTime = clock.instant() - 100.ms - latencyDiff
                    packet2.sentPacket.sequenceNumber = (i * 2 + 1).toLong()

                    msg.packetFeedbacks.add(packet1)
                    msg.packetFeedbacks.add(packet2)
                    delayController.update(parseScreamFeedback(msg), alr = false)
                }
            }

            // Establish 0 latency difference.
            feedPackets(Duration.ZERO, 100)
            delayController.refWindowScaleFactorDueToLatencyDifference() shouldBe 1.0

            // Drive to `kLatencyThreshold`.
            feedPackets(kLatencyThreshold + 10.ms, 250)
            delayController.refWindowScaleFactorDueToLatencyDifference() shouldBe (0.1 plusOrMinus 0.01)

            // Drive to `0.5 * kLatencyThreshold`.
            // Expected: 0.1 + 1.2 * 0.5 = 0.7
            feedPackets(kLatencyThreshold.dividedBy(2), 250)
            delayController.refWindowScaleFactorDueToLatencyDifference() shouldBe (0.7 plusOrMinus 0.01)

            // Drive to `0.25 * kLatencyThreshold`.
            // Expected: 0.1 + 1.2 * 0.75 = 1.0.
            feedPackets(kLatencyThreshold.dividedBy(4), 250)
            delayController.refWindowScaleFactorDueToLatencyDifference() shouldBe (1.0 plusOrMinus 0.01)
        }

        "PacketsSentOutsideBurstDoNotReduceRefWindowScaleFactor" {
            val clock = createClock()
            val params = ScreamV2Parameters()
            val delayController = DelayBasedCongestionControl(params)

            // Feed packets with 50ms delay difference, but sent 100ms apart (> 25ms burst
            // delta).
            for (i in 0 until 10) {
                clock.elapse(100.ms)
                val msg = TransportPacketsFeedback()
                msg.feedbackTime = clock.instant()

                val packet1 = PacketResult()
                packet1.receiveTime = clock.instant() - 50.ms
                packet1.sentPacket.sendTime = clock.instant() - 150.ms
                packet1.sentPacket.sequenceNumber = (i * 2).toLong()

                val packet2 = PacketResult()
                packet2.receiveTime = clock.instant()
                packet2.sentPacket.sendTime = clock.instant() - 50.ms
                packet2.sentPacket.sequenceNumber = (i * 2 + 1).toLong()

                msg.packetFeedbacks.add(packet1)
                msg.packetFeedbacks.add(packet2)
                delayController.update(parseScreamFeedback(msg, params), alr = false)
            }

            // Because send delta is 100ms > 25ms, latency_difference is 0 and scale
            // factor remains 1.0.
            delayController.refWindowScaleFactorDueToLatencyDifference() shouldBe 1.0
        }

        "RttDecaysSlowerInAlr" {
            val clock = createClock()
            val delayControllerAlr = DelayBasedCongestionControl(ScreamV2Parameters())
            val delayControllerNoAlr = DelayBasedCongestionControl(ScreamV2Parameters())

            fun feedFeedback(controller: DelayBasedCongestionControl, rtt: Duration, alr: Boolean) {
                val msg = TransportPacketsFeedback()
                msg.feedbackTime = clock.instant()
                val packet = PacketResult()
                packet.receiveTime = clock.instant()
                packet.sentPacket.sendTime = clock.instant() - rtt
                packet.sentPacket.sequenceNumber = 0
                msg.packetFeedbacks.add(packet)
                controller.update(parseScreamFeedback(msg), alr)
            }

            // Establish initial smoothed RTT of 200ms.
            feedFeedback(delayControllerAlr, 200.ms, alr = false)
            feedFeedback(delayControllerNoAlr, 200.ms, alr = false)

            delayControllerAlr.rtt() shouldBe 200.ms
            delayControllerNoAlr.rtt() shouldBe 200.ms

            // Advance time.
            clock.elapse(100.ms)

            // Send feedback with a lower RTT sample of 100ms.
            feedFeedback(delayControllerAlr, 100.ms, alr = true)
            feedFeedback(delayControllerNoAlr, 100.ms, alr = false)

            // With ALR, RTT decay is slower, so the resulting smoothed RTT is higher.
            delayControllerAlr.rtt() shouldBeGreaterThan delayControllerNoAlr.rtt()

            // Verify actual values.
            // no_alr: 100 * 0.125 + 200 * 0.875 = 187.5ms
            // alr: 100 * (1/128) + 200 * (127/128) = 199.21875ms
            delayControllerNoAlr.rtt().toMsDouble() shouldBe (187.5 plusOrMinus 0.1)
            delayControllerAlr.rtt().toMsDouble() shouldBe (199.2 plusOrMinus 0.1)
        }

        "RttIncreasesSlowerInAlr" {
            val clock = createClock()
            val delayControllerAlr = DelayBasedCongestionControl(ScreamV2Parameters())
            val delayControllerNoAlr = DelayBasedCongestionControl(ScreamV2Parameters())

            fun feedFeedback(controller: DelayBasedCongestionControl, rtt: Duration, alr: Boolean) {
                val msg = TransportPacketsFeedback()
                msg.feedbackTime = clock.instant()
                val packet = PacketResult()
                packet.receiveTime = clock.instant()
                packet.sentPacket.sendTime = clock.instant() - rtt
                packet.sentPacket.sequenceNumber = 0
                msg.packetFeedbacks.add(packet)
                controller.update(parseScreamFeedback(msg), alr)
            }

            // Establish initial smoothed RTT of 100ms.
            feedFeedback(delayControllerAlr, 100.ms, alr = false)
            feedFeedback(delayControllerNoAlr, 100.ms, alr = false)

            delayControllerAlr.rtt() shouldBe 100.ms
            delayControllerNoAlr.rtt() shouldBe 100.ms

            // Advance time.
            clock.elapse(100.ms)

            // Send feedback with a higher RTT sample of 200ms.
            feedFeedback(delayControllerAlr, 200.ms, alr = true)
            feedFeedback(delayControllerNoAlr, 200.ms, alr = false)

            // With ALR, RTT increase is slower, so the resulting smoothed RTT is lower.
            delayControllerAlr.rtt() shouldBeLessThan delayControllerNoAlr.rtt()

            // Verify actual values.
            // no_alr: 200 * 0.125 + 100 * 0.875 = 112.5ms
            // alr: 200 * (1/128) + 100 * (127/128) = 100.78125ms
            delayControllerNoAlr.rtt().toMsDouble() shouldBe (112.5 plusOrMinus 0.1)
            delayControllerAlr.rtt().toMsDouble() shouldBe (100.8 plusOrMinus 0.1)
        }

        // TODO: bugs.webrtc.org/447037083 - add tests for clock drift in feedback NTP
        // time.

        "LatencyDifferenceWithNegativeOneWayDelay" {
            val clock = createClock()
            val delayController = DelayBasedCongestionControl(ScreamV2Parameters())

            // Send packets where receiver clock is behind sender clock (negative OWD),
            // but there is zero latency variation between packets.
            for (i in 0 until 50) {
                clock.elapse(10.ms)
                val msg = TransportPacketsFeedback()
                msg.feedbackTime = clock.instant()

                val packet1 = PacketResult()
                packet1.receiveTime = clock.instant()
                packet1.sentPacket.sendTime = clock.instant() + 100.ms
                packet1.sentPacket.sequenceNumber = (i * 2).toLong()

                val packet2 = PacketResult()
                packet2.receiveTime = clock.instant()
                packet2.sentPacket.sendTime = clock.instant() + 100.ms
                packet2.sentPacket.sequenceNumber = (i * 2 + 1).toLong()

                msg.packetFeedbacks.add(packet1)
                msg.packetFeedbacks.add(packet2)
                delayController.update(parseScreamFeedback(msg), alr = false)
            }

            delayController.latencyDifferenceAvg() shouldBe Duration.ZERO
            delayController.refWindowScaleFactorDueToLatencyDifference() shouldBe 1.0
        }

        "ReduceJitterTracksShortAndLongQueueDelayTrend" {
            val clock = createClock()
            val delayController = DelayBasedCongestionControl(ScreamV2Parameters(reduceJitter = true))

            fun sendFeedbackWithQdelay(qdelay: Duration) {
                clock.elapse(25.ms)
                val msg = TransportPacketsFeedback()
                msg.feedbackTime = clock.instant()
                val packet = PacketResult()
                packet.receiveTime = clock.instant()
                packet.sentPacket.sendTime = clock.instant() - 50.ms - qdelay
                packet.sentPacket.sequenceNumber = 0
                msg.packetFeedbacks.add(packet)
                delayController.update(parseScreamFeedback(msg), alr = false)
            }

            sendFeedbackWithQdelay(qdelay = Duration.ZERO)
            delayController.refWindowScaleFactorDueToLatencyDifference() shouldBe 1.0

            // Simulate rising queue delay.
            var startTime = clock.instant()
            while (clock.instant() < startTime + 500.ms) {
                sendFeedbackWithQdelay(qdelay = 20.ms)
            }
            delayController.refWindowScaleFactorDueToLatencyDifference() shouldBeLessThan 0.8

            // When queue delay stabilizes, the scale factor recovers back above 0.95
            // despite queue delay staying high.
            startTime = clock.instant()
            while (delayController.refWindowScaleFactorDueToLatencyDifference() <= 0.95 &&
                clock.instant() < startTime + 30.secs
            ) {
                sendFeedbackWithQdelay(qdelay = 20.ms)
            }
            delayController.refWindowScaleFactorDueToLatencyDifference() shouldBeGreaterThan 0.95
            Duration.between(startTime, clock.instant()) shouldBeLessThan 20.secs
            Duration.between(startTime, clock.instant()) shouldBeGreaterThan 10.secs
        }
    }
}
