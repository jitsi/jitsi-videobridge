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

package org.jitsi.nlj.rtp.scream.test

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.kbps
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.utils.ms
import org.jitsi.utils.time.FakeClock
import org.jitsi.utils.toRoundedMillis
import java.time.Duration
import java.time.Instant

private val kPacketSize = 1000.bytes

private fun createClock() = FakeClock().apply { setTime(Instant.ofEpochSecond(1234)) }

/** Unit tests for CcFeedbackGenerator,
 * based on WebRTC modules/congestion_controller/scream/test/cc_feedback_generator_unittest.cc in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 */
class CcFeedbackGeneratorTest : FreeSpec() {
    init {
        "BasicFeedback" {
            // Link capacity of 1000Kbps means it will take 1000*8/1000 = 8ms to send one
            // packet through the narrow section + 25ms fixed one way delay.
            // With perfect pacing, smoothed_rtt should be 58ms.
            // With a send rate of 500Kbps, where each packet is 1000bytes, one packet is
            // sent every 16ms.
            // The first feedback packet is expected to be sent as soon as the first
            // packet has been delivered and received 25ms later. At that time, there
            // should be 3 packets in flight (3*16=48ms).
            val clock = createClock()
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 1000.kbps),
                    timeBetweenFeedback = 50.ms
                )
            )

            val feedback1 = feedbackGenerator.processUntilNextFeedback(sendRate = 500.kbps, clock)
            feedback1.feedbackTime shouldBe clock.instant()
            feedback1.dataInFlight shouldBe kPacketSize * 3
            feedback1.packetFeedbacks.size shouldBe 4
            feedback1.packetFeedbacks[0].arrivalTimeOffset shouldBe 50.ms
            for (packet in feedback1.packetFeedbacks) {
                Duration.between(packet.sentPacket.sendTime, packet.receiveTime) shouldBe (25 + 8).ms
                packet.sentPacket.size shouldBe kPacketSize
                packet.ecn shouldBe EcnMarking.kEct1
            }

            val feedback2 = feedbackGenerator.processUntilNextFeedback(sendRate = 500.kbps, clock)
            Duration.between(feedback1.feedbackTime, feedback2.feedbackTime).toRoundedMillis() shouldBe 50
        }

        "SendsFirstFeedbackAfterTimeBetweenFeedback" {
            val clock = createClock()
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 1000.kbps),
                    timeBetweenFeedback = 50.ms
                )
            )

            val feedback = feedbackGenerator.processUntilNextFeedback(sendRate = 500.kbps, clock)
            feedback.packetFeedbacks.isEmpty() shouldBe false
            feedback.packetFeedbacks[0].arrivalTimeOffset shouldBe 50.ms
        }

        "CeMarksPacketsIfSendRateIsTooHigh" {
            val clock = createClock()
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 1000.kbps)
                )
            )

            var numberOfCeMarks = 0
            for (i in 0 until 5) {
                val feedback = feedbackGenerator.processUntilNextFeedback(sendRate = 1100.kbps, clock)
                numberOfCeMarks += CcFeedbackGenerator.countCeMarks(feedback)
            }
            numberOfCeMarks shouldBeGreaterThanOrEqual 2
        }

        "NoCeMarksIfSendRateIsBelowLinkCapacity" {
            val clock = createClock()
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 1000.kbps),
                    timeBetweenFeedback = 25.ms
                )
            )

            var numberOfCeMarks = 0
            for (i in 0 until 5) {
                val feedback = feedbackGenerator.processUntilNextFeedback(sendRate = 1000.kbps, clock)
                numberOfCeMarks += CcFeedbackGenerator.countCeMarks(feedback)
            }
            numberOfCeMarks shouldBe 0
        }

        "DropPacketsIfSendRateIsTooHigh" {
            val clock = createClock()
            val networkConfig = BuiltInNetworkBehaviorConfig(
                // Only one packet is allowed to be queued
                queueLengthPackets = 1,
                queueDelayMs = 25,
                linkCapacity = 1000.kbps
            )
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(networkConfig = networkConfig, sendAsEct1 = false)
            )

            var numberOfLostPackets = 0
            for (i in 0 until 5) {
                val feedback = feedbackGenerator.processUntilNextFeedback(sendRate = 1100.kbps, clock)
                numberOfLostPackets += feedback.lostWithSendInfo().size
            }
            numberOfLostPackets shouldBeGreaterThanOrEqual 2
        }

        "InvokesPacketSentCallbackWithDataInflight" {
            val clock = createClock()
            val networkConfig = BuiltInNetworkBehaviorConfig()
            val feedbackGenerator = CcFeedbackGenerator(CcFeedbackGenerator.Config(networkConfig = networkConfig))

            var lastDataInFlight = DataSize.ZERO
            val feedback = feedbackGenerator.processUntilNextFeedback(
                sendRate = 1000.kbps,
                clock,
                sentPacketCb = { packet -> lastDataInFlight = packet.dataInFlight }
            )

            // After feedback, data in flight should be less than after sending last
            // packet.
            lastDataInFlight shouldBeGreaterThan feedback.dataInFlight
        }

        "SupportsPacketReordering" {
            val clock = createClock()
            val networkConfig = BuiltInNetworkBehaviorConfig(
                queueDelayMs = 25,
                delayStandardDeviationMs = 25,
                linkCapacity = 1000.kbps,
                allowReordering = true
            )
            val feedbackGenerator = CcFeedbackGenerator(CcFeedbackGenerator.Config(networkConfig = networkConfig))

            var lostCount = 0
            var recoveredCount = 0
            for (i in 0 until 50) {
                val feedback = feedbackGenerator.processUntilNextFeedback(sendRate = 1000.kbps, clock)
                for (result in feedback.packetFeedbacks) {
                    if (result.reportedLostForTheFirstTime) {
                        ++lostCount
                        result.isReceived() shouldBe false
                        result.reportedRecoveredForTheFirstTime shouldBe false
                    }
                    if (result.reportedRecoveredForTheFirstTime) {
                        ++recoveredCount
                        result.isReceived() shouldBe true
                        result.reportedLostForTheFirstTime shouldBe false
                    }
                }
            }
            lostCount shouldBeGreaterThan 0
            recoveredCount shouldBeGreaterThan 0

            // Drain the network to ensure all delayed out-of-order packets are delivered
            // and recovered.
            var drainIterations = 0
            while (lostCount > recoveredCount && drainIterations < 5) {
                ++drainIterations
                val feedback = feedbackGenerator.processUntilNextFeedback(sendRate = Bandwidth.ZERO, clock)
                for (result in feedback.packetFeedbacks) {
                    if (result.reportedLostForTheFirstTime) {
                        ++lostCount
                    }
                    if (result.reportedRecoveredForTheFirstTime) {
                        ++recoveredCount
                    }
                }
            }
            lostCount shouldBe recoveredCount
        }

        "DataInFlightExcludesLostPackets" {
            val clock = createClock()
            val networkConfig = BuiltInNetworkBehaviorConfig(
                queueLengthPackets = 1,
                queueDelayMs = 25,
                linkCapacity = 1000.kbps
            )
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(networkConfig = networkConfig, sendAsEct1 = false)
            )

            // Send at a rate higher than capacity to cause packet loss.
            var feedback = TransportPacketsFeedback()
            var lostPackets = 0
            for (i in 0 until 5) {
                feedback = feedbackGenerator.processUntilNextFeedback(sendRate = 1100.kbps, clock)
                lostPackets += feedback.lostWithSendInfo().size
            }
            lostPackets shouldBeGreaterThan 0

            // Now send at a sustainable rate to let the queue stabilize.
            for (i in 0 until 10) {
                feedback = feedbackGenerator.processUntilNextFeedback(sendRate = 500.kbps, clock)
            }

            // At 500Kbps, ideal data in flight is around 3 to 4 packets.
            // The previously lost packets should not be counted in data_in_flight.
            feedback.dataInFlight shouldBeLessThanOrEqualTo kPacketSize * 4
        }
    }
}
