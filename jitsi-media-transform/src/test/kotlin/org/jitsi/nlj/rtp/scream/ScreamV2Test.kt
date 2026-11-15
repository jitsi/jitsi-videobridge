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
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.rtp.bandwidthestimation2.PacketResult
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.rtp.scream.test.BuiltInNetworkBehaviorConfig
import org.jitsi.nlj.rtp.scream.test.CcFeedbackGenerator
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.min
import org.jitsi.nlj.util.rateOf
import org.jitsi.nlj.util.scaledBy
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.EcnMarking
import org.jitsi.utils.NEVER
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.max
import org.jitsi.utils.mins
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import java.time.Instant
import kotlin.math.pow

private val kPacketSize = 1000.bytes

private fun createClock() = FakeClock().apply { setTime(Instant.ofEpochSecond(1234)) }

private fun createFeedback(
    feedbackTime: Instant,
    rtt: Duration,
    numberOfEct1Packets: Int,
    numberOfPacketsInFlight: Int
): TransportPacketsFeedback {
    var sequenceNumber = 0L
    val feedback = TransportPacketsFeedback()
    feedback.feedbackTime = feedbackTime
    val sendTime = feedbackTime - rtt

    feedback.dataInFlight = kPacketSize * numberOfPacketsInFlight

    for (i in 0 until numberOfEct1Packets) {
        val result = PacketResult()
        result.sentPacket.sendTime = sendTime
        result.sentPacket.size = kPacketSize
        result.ecn = EcnMarking.kEct1
        result.receiveTime = sendTime + rtt.dividedBy(2)
        result.sentPacket.sequenceNumber = sequenceNumber++
        feedback.packetFeedbacks.add(result)
    }

    return feedback
}

/** A copy of a feedback which shares nothing with it. Assigning one does this in WebRTC. */
private fun TransportPacketsFeedback.deepCopy(): TransportPacketsFeedback {
    val copy = TransportPacketsFeedback()
    copy.feedbackTime = feedbackTime
    copy.dataInFlight = dataInFlight
    copy.packetFeedbacks = packetFeedbacks.map { it.copy() }.toMutableList()
    return copy
}

private class AdaptsToLinkCapacityParams(
    val networkConfig: BuiltInNetworkBehaviorConfig,
    val sendAsEct1: Boolean = true,
    val adaptionTime: Duration,
    val timeToRunAfterAdaptionTime: Duration = 5.secs
)

private class AdaptsToLinkCapacityResult {
    var dataRate = Bandwidth.ZERO
    var minRateAfterAdaption = Bandwidth.ZERO
    var maxRateAfterAdaption = Bandwidth.ZERO
    var maxSmoothedRttAfterAdaptation: Duration = Duration.ZERO
}

/** Unit tests for ScreamV2,
 * based on WebRTC modules/congestion_controller/scream/scream_v2_unittest.cc in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 *
 * Field trial settings are passed as ScreamV2Parameters.
 */
class ScreamV2Test : FreeSpec() {
    private val logger = createLogger()

    private fun runAdaptToLinkCapacityTest(params: AdaptsToLinkCapacityParams): AdaptsToLinkCapacityResult {
        val result = AdaptsToLinkCapacityResult()
        val kStartTime = Instant.ofEpochSecond(1234)
        val clock = FakeClock().apply { setTime(kStartTime) }
        val scream = ScreamV2(logger)
        val feedbackGenerator = CcFeedbackGenerator(
            CcFeedbackGenerator.Config(
                networkConfig = params.networkConfig,
                sendAsEct1 = params.sendAsEct1,
                packetSize = 255.bytes
            )
        )

        var sendRate = 100.kbps
        while (clock.instant() < kStartTime + params.adaptionTime) {
            val feedback = feedbackGenerator.processUntilNextFeedback(
                sendRate,
                clock,
                { packet -> scream.onPacketSent(packet.dataInFlight) }
            )
            scream.onTransportPacketsFeedback(feedback)
            sendRate = scream.targetRate()
        }
        result.dataRate = sendRate
        result.minRateAfterAdaption = sendRate
        result.maxRateAfterAdaption = sendRate

        val timeAfterAdaption = clock.instant()
        while (clock.instant() < timeAfterAdaption + params.timeToRunAfterAdaptionTime) {
            val feedback = feedbackGenerator.processUntilNextFeedback(
                sendRate,
                clock,
                { packet -> scream.onPacketSent(packet.dataInFlight) }
            )
            scream.onTransportPacketsFeedback(feedback)
            sendRate = scream.targetRate()
            result.minRateAfterAdaption = min(result.minRateAfterAdaption, sendRate)
            result.maxRateAfterAdaption = max(result.maxRateAfterAdaption, sendRate)
            result.maxSmoothedRttAfterAdaptation = max(result.maxSmoothedRttAfterAdaptation, scream.rtt())
        }
        logger.info(
            " rate_after_adaption ${result.dataRate} max_rate_after_adaption: ${result.maxRateAfterAdaption} " +
                "min_rate_after_adaption: ${result.minRateAfterAdaption}"
        )
        return result
    }

    init {
        "TargetRateIncreaseToMaxOnUnConstrainedNetwork" {
            val clock = createClock()
            val scream = ScreamV2(logger)
            val kMaxDataRate = 2000.kbps
            scream.setTargetBitrateConstraints(Bandwidth.ZERO, kMaxDataRate, 300.kbps)
            var sendRate = 100.kbps
            // Configure a feedback generator simulating a network with infinite
            // capacity but 25ms one way delay.
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25))
            )

            for (i in 0 until 100) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)
                sendRate = scream.targetRate()
            }
            sendRate shouldBe kMaxDataRate
        }

        "ReferenceWindowDoesNotDecreaseAfterLowerSendRateOnUnconstrainedNetwork" {
            val clock = createClock()
            val scream = ScreamV2(logger)
            val kMaxDataRate = 2000.kbps
            scream.setTargetBitrateConstraints(Bandwidth.ZERO, kMaxDataRate, 300.kbps)
            var sendRate = 100.kbps
            // Configure a feedback generator simulating a network with infinite
            // capacity but 25ms one way delay.
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25))
            )

            for (i in 0 until 70) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)
                sendRate = scream.targetRate()
            }
            val refWindow = scream.refWindow()

            // Half the send rate, but the network is still unconstrained.
            sendRate = sendRate / 2
            for (i in 0 until 20) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)
            }
            // Still the same ref_window.
            refWindow shouldBe scream.refWindow()
        }

        "ReferenceWindowIncreaseLessPerStepOnLowRtt" {
            val clock = createClock()
            val scream1 = ScreamV2(logger)
            val scream2 = ScreamV2(logger)

            val highRttFeedback = createFeedback(
                clock.instant(),
                rtt = 100.ms,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 20
            )
            val lowRttFeedback = createFeedback(
                clock.instant(),
                rtt = 1.ms,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 20
            )

            scream1.onTransportPacketsFeedback(highRttFeedback)
            scream2.onTransportPacketsFeedback(lowRttFeedback)

            scream1.refWindow() shouldBeGreaterThan scream2.refWindow()
        }

        "ReferenceWindowIncreaseLessPerStepIfCeDetected" {
            val clock = createClock()
            val scream1 = ScreamV2(logger)
            val scream2 = ScreamV2(logger)

            val feedback = createFeedback(
                clock.instant(),
                rtt = 10.ms,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 20
            )

            val ceDetectedFeedback = feedback.deepCopy()
            ceDetectedFeedback.packetFeedbacks[0].ecn = EcnMarking.kCe

            scream1.onTransportPacketsFeedback(feedback)
            scream2.onTransportPacketsFeedback(ceDetectedFeedback)

            scream1.refWindow() shouldBeGreaterThan scream2.refWindow()
        }

        "ReferenceWindowDecreaseOnConsecutiveLossEvents" {
            val clock = createClock()
            val scream = ScreamV2(logger)

            val feedback = createFeedback(
                clock.instant(),
                rtt = 10.ms,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 20
            )

            scream.onTransportPacketsFeedback(feedback)
            var refWindow = scream.refWindow()
            clock.elapse(25.ms)

            // Send consecutive loss reports until congestion_level reaches 1.0
            // to trigger the reference window decrease.
            for (i in 0 until 3) {
                val lossFeedback = createFeedback(
                    clock.instant(),
                    rtt = 10.ms,
                    numberOfEct1Packets = 5,
                    numberOfPacketsInFlight = 5
                )
                lossFeedback.packetFeedbacks[3].receiveTime = NEVER
                lossFeedback.packetFeedbacks[3].reportedLostForTheFirstTime = true
                scream.onTransportPacketsFeedback(lossFeedback)
                clock.elapse(25.ms)
            }
            scream.refWindow() shouldBeLessThan refWindow
            refWindow = scream.refWindow()

            clock.elapse(25.ms)
            val lossFeedback2 = createFeedback(
                clock.instant(),
                rtt = 10.ms,
                numberOfEct1Packets = 5,
                numberOfPacketsInFlight = 5
            )
            lossFeedback2.packetFeedbacks[0].receiveTime = NEVER
            lossFeedback2.packetFeedbacks[0].reportedLostForTheFirstTime = false

            scream.onTransportPacketsFeedback(lossFeedback2)
            scream.refWindow() shouldBeGreaterThanOrEqualTo refWindow
        }

        "ReferenceWindowIncreaseToDataInflight" {
            val clock = createClock()
            val scream = ScreamV2(logger)

            val startTime = clock.instant()
            val feedbackInterval = 25.ms

            val feedback = createFeedback(
                clock.instant(),
                rtt = 10.ms,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 10
            )

            while (clock.instant() < startTime + 2.secs) {
                feedback.feedbackTime = clock.instant()
                scream.onTransportPacketsFeedback(feedback)
                clock.elapse(feedbackInterval)
            }
            // Target rate can increase up to 1.1 * data_in_flight + Max Segment Size(
            // default 1280 bytes) when no max target rate has been set.
            scream.refWindow() shouldBe feedback.dataInFlight.scaledBy(1.1) + 1280.bytes
        }

        "CalculatesL4sAlpha" {
            val clock = createClock()

            val scream = ScreamV2(logger)

            val startTime = clock.instant()
            val feedbackInterval = 25.ms

            val l4sAlpha = scream.l4sAlpha()
            while (clock.instant() < startTime + 2.secs) {
                val feedback = createFeedback(
                    clock.instant(),
                    rtt = 10.ms,
                    numberOfEct1Packets = 20,
                    numberOfPacketsInFlight = 20
                )
                // CE mark 20% of packets.
                for (i in 0 until 4) {
                    feedback.packetFeedbacks[i].ecn = EcnMarking.kCe
                }
                scream.onTransportPacketsFeedback(feedback)
                scream.l4sAlpha() shouldBeGreaterThan l4sAlpha
                clock.elapse(feedbackInterval)
            }

            scream.l4sAlpha() shouldBe (0.2 plusOrMinus 0.01)
        }

        "L4sAlphaScalesUpAndDownWithRtt" {
            val clock = createClock()
            val scream = ScreamV2(logger)

            val rtt = 100.ms
            val feedbackInterval = 25.ms

            // First feedback with 100% CE marks at RTT = 100ms (steps_per_rtt = 4).
            // With L4sAvgG = 1/16 and steps_per_rtt = 4, g = (1/16) / 4 = 1/64.
            val ceFeedback = createFeedback(
                clock.instant(),
                rtt,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 20
            )
            for (packet in ceFeedback.packetFeedbacks) {
                packet.ecn = EcnMarking.kCe
            }
            scream.onTransportPacketsFeedback(ceFeedback)
            scream.l4sAlpha() shouldBe ((1.0 / 16.0) / 4.0 plusOrMinus 1e-15)

            // Next 4 feedbacks (1 RTT) with 0% CE marks.
            // With L4sAvgG = 1/16 and steps_per_rtt = 4, g = (1/16) / 4 = 1/64.
            val alphaBeforeDecay = scream.l4sAlpha()
            for (i in 0 until 4) {
                clock.elapse(feedbackInterval)
                val clearFeedback = createFeedback(
                    clock.instant(),
                    rtt,
                    numberOfEct1Packets = 20,
                    numberOfPacketsInFlight = 20
                )
                scream.onTransportPacketsFeedback(clearFeedback)
            }
            val expectedDecayFactor = (1.0 - (1.0 / 16.0) / 4.0).pow(4)
            scream.l4sAlpha() shouldBe (alphaBeforeDecay * expectedDecayFactor plusOrMinus 1e-6)
        }

        "AdaptsToEcnLinkCapacity1Mbps" {
            val params = AdaptsToLinkCapacityParams(
                networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 1000.kbps),
                sendAsEct1 = true,
                adaptionTime = 4.secs
            )
            val result = runAdaptToLinkCapacityTest(params)

            result.dataRate shouldBeLessThan 1100.kbps
            result.dataRate shouldBeGreaterThan 650.kbps
            result.maxRateAfterAdaption shouldBeLessThan 1100.kbps
            result.minRateAfterAdaption shouldBeGreaterThan 650.kbps

            result.maxSmoothedRttAfterAdaptation shouldBeLessThan (25 * 2 + 25).ms
        }

        "AdaptsToLossLinkCapacity5Mbps" {
            val params = AdaptsToLinkCapacityParams(
                networkConfig = BuiltInNetworkBehaviorConfig(
                    queueLengthPackets = 3,
                    queueDelayMs = 10,
                    linkCapacity = 5000.kbps
                ),
                // Adapt only due to loss when queues overflow.
                sendAsEct1 = false,
                adaptionTime = 10.secs
            )

            val result = runAdaptToLinkCapacityTest(params)

            result.dataRate shouldBeLessThan 5400.kbps
            result.dataRate shouldBeGreaterThan 1500.kbps
            result.maxRateAfterAdaption shouldBeLessThan 5400.kbps
            result.minRateAfterAdaption shouldBeGreaterThan 1500.kbps

            result.maxSmoothedRttAfterAdaptation shouldBeLessThan (10 * 2 + 40).ms
        }

        "AdaptsToDelayLinkCapacity2Mbps" {
            val params = AdaptsToLinkCapacityParams(
                networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 10, linkCapacity = 2000.kbps),
                // Adapt only due to delay increase.
                sendAsEct1 = false,
                adaptionTime = 10.secs
            )

            val result = runAdaptToLinkCapacityTest(params)

            result.dataRate shouldBeLessThan 2500.kbps
            result.dataRate shouldBeGreaterThan 1500.kbps
            result.maxRateAfterAdaption shouldBeLessThan 2500.kbps
            result.minRateAfterAdaption shouldBeGreaterThan 1500.kbps

            result.maxSmoothedRttAfterAdaptation shouldBeLessThan (10 * 2 + 50 + 10).ms
        }

        "AdaptsToDelayLinkCapacity2MbpsWithReorderedPackets" {
            val params = AdaptsToLinkCapacityParams(
                networkConfig = BuiltInNetworkBehaviorConfig(
                    queueDelayMs = 10,
                    delayStandardDeviationMs = 5,
                    linkCapacity = 2000.kbps,
                    allowReordering = true
                ),
                // Adapt only due to delay increase.
                sendAsEct1 = false,
                adaptionTime = 10.secs
            )

            val result = runAdaptToLinkCapacityTest(params)

            result.dataRate shouldBeLessThan 2500.kbps
            result.dataRate shouldBeGreaterThan 1500.kbps
            result.maxRateAfterAdaption shouldBeLessThan 2500.kbps
            result.minRateAfterAdaption shouldBeGreaterThan 900.kbps

            result.maxSmoothedRttAfterAdaptation shouldBeLessThan (10 * 2 + 50 + 10).ms
        }

        "AdaptsToDelayLinkCapacity2MbpsLongRunning" {
            val params = AdaptsToLinkCapacityParams(
                networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 10, linkCapacity = 2000.kbps),
                // Adapt only due to delay increase.
                sendAsEct1 = false,
                adaptionTime = 5.secs,
                timeToRunAfterAdaptionTime = 15.mins
            )

            val result = runAdaptToLinkCapacityTest(params)
            result.maxSmoothedRttAfterAdaptation shouldBeLessThan (10 * 2 + 60).ms
        }

        "EntersAndExitsAlrState" {
            val clock = createClock()
            val scream = ScreamV2(logger)
            val kMaxDataRate = 2000.kbps
            scream.setTargetBitrateConstraints(Bandwidth.ZERO, kMaxDataRate, 300.kbps)

            // Configure a feedback generator simulating a network with infinite
            // capacity but 25ms one way delay.
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25))
            )

            var sendRate = 300.kbps

            // 1. Ramp up send rate to establish a high target rate / ref_window.
            for (i in 0 until 50) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)
                sendRate = scream.targetRate()
            }

            sendRate shouldBeGreaterThan 1500.kbps
            scream.isApplicationLimited() shouldBe false

            // 2. Drop send rate to 1000kbps to simulate application-limited state.
            sendRate = 1000.kbps
            for (i in 0 until 10) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)
            }

            scream.isApplicationLimited() shouldBe true

            // 3. Increase send rate back to the target rate to exit ALR.
            sendRate = scream.targetRate()
            for (i in 0 until 15) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)
                sendRate = scream.targetRate()
            }

            scream.isApplicationLimited() shouldBe false
        }

        "DisableAlrViaFieldTrial" {
            val clock = createClock()
            val scream = ScreamV2(logger, ScreamV2Parameters(enableAlr = false))
            val kMaxDataRate = 2000.kbps
            scream.setTargetBitrateConstraints(Bandwidth.ZERO, kMaxDataRate, 300.kbps)

            // Configure a feedback generator simulating a network with infinite
            // capacity but 25ms one way delay.
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25))
            )

            var sendRate = 300.kbps

            // 1. Ramp up send rate.
            for (i in 0 until 50) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)
                sendRate = scream.targetRate()
            }

            sendRate shouldBeGreaterThan 1500.kbps
            scream.isApplicationLimited() shouldBe false

            // 2. Drop send rate to 100kbps.
            sendRate = 100.kbps
            for (i in 0 until 10) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)
            }

            // ALR is disabled, so it should still not be in application-limited state.
            scream.isApplicationLimited() shouldBe false
        }

        "SpuriousLossEventsAreIgnoredIfQueueDelayIsNotDetected" {
            val clock = createClock()
            val scream = ScreamV2(logger)

            // Set up target rate and initial ref window
            scream.setTargetBitrateConstraints(Bandwidth.ZERO, 2000.kbps, 300.kbps)

            // Send initial feedback to establish baseline with low RTT (no queue delay)
            val feedback = createFeedback(
                clock.instant(),
                rtt = 10.ms,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 20
            )
            scream.onTransportPacketsFeedback(feedback)
            val refWindow = scream.refWindow()
            clock.elapse(25.ms)

            // Send feedback with a single lost packet (spurious loss, below the 0.25
            // threshold) queue delay remains 0 (not detected)
            val lossFeedback = createFeedback(
                clock.instant(),
                rtt = 10.ms,
                numberOfEct1Packets = 19,
                numberOfPacketsInFlight = 20
            )
            // Add 1 lost packet
            val lostPacket = PacketResult()
            lostPacket.sentPacket.sendTime = clock.instant() - 10.ms
            lostPacket.sentPacket.size = kPacketSize
            lostPacket.receiveTime = NEVER // Lost
            lostPacket.sentPacket.sequenceNumber = 20
            lostPacket.reportedLostForTheFirstTime = true
            lossFeedback.packetFeedbacks.add(lostPacket)

            scream.onTransportPacketsFeedback(lossFeedback)
            // Since queue delay is not detected, the loss event is ignored, and
            // ref_window should NOT decrease.
            scream.refWindow() shouldBeGreaterThanOrEqualTo refWindow
        }

        "LossEventsAreNotIgnoredIfQueueDelayIsDetected" {
            val clock = createClock()
            val scream = ScreamV2(logger)

            scream.setTargetBitrateConstraints(Bandwidth.ZERO, 2000.kbps, 300.kbps)

            // Send initial feedback to establish base delay (5ms one-way delay)
            val feedback = createFeedback(
                clock.instant(),
                rtt = 10.ms,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 20
            )
            scream.onTransportPacketsFeedback(feedback)
            clock.elapse(25.ms)

            // Send first high delay feedback to build queue delay (rtt=170ms -> owd=85ms)
            // queue_delay = 85ms - 5ms = 80ms.
            // queue_delay_avg_ = 0.25 * 80ms = 20ms.
            val highDelayFeedback1 = createFeedback(
                clock.instant(),
                rtt = 170.ms,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 20
            )
            scream.onTransportPacketsFeedback(highDelayFeedback1)
            clock.elapse(25.ms)

            // Send second high delay feedback to build queue delay above target/4 (15ms)
            // queue_delay = 85ms - 5ms = 80ms.
            // queue_delay_avg_ = 0.25 * 80ms = 20ms.
            // 20ms > 15ms, so IsQueueDelayDetected() is true.
            val highDelayFeedback2 = createFeedback(
                clock.instant(),
                rtt = 170.ms,
                numberOfEct1Packets = 20,
                numberOfPacketsInFlight = 20
            )
            scream.onTransportPacketsFeedback(highDelayFeedback2)
            val refWindowBeforeLoss = scream.refWindow()
            clock.elapse(25.ms)

            // Now send a feedback containing a loss event
            val lossFeedback = createFeedback(
                clock.instant(),
                rtt = 170.ms,
                numberOfEct1Packets = 19,
                numberOfPacketsInFlight = 20
            )
            val lostPacket = PacketResult()
            lostPacket.sentPacket.sendTime = clock.instant() - 170.ms
            lostPacket.sentPacket.size = kPacketSize
            lostPacket.receiveTime = NEVER // Lost
            lostPacket.sentPacket.sequenceNumber = 60
            lostPacket.reportedLostForTheFirstTime = true
            lossFeedback.packetFeedbacks.add(lostPacket)

            scream.onTransportPacketsFeedback(lossFeedback)
            // Since queue delay is detected, the loss event is NOT ignored, and
            // ref_window should decrease!
            scream.refWindow() shouldBeLessThan refWindowBeforeLoss
        }

        "KeepsTrackOfReceivedRateOver100msWindow" {
            val clock = createClock()
            val scream = ScreamV2(logger)

            // Initialize constraints
            scream.setTargetBitrateConstraints(Bandwidth.ZERO, 2000.kbps, 300.kbps)

            // First feedback
            val feedback1 = createFeedback(
                clock.instant(),
                rtt = 100.ms,
                numberOfEct1Packets = 5,
                numberOfPacketsInFlight = 20
            )
            scream.onTransportPacketsFeedback(feedback1)

            // feedback1 has 5 packets of 1000 bytes each = 5000 bytes.
            // At the first feedback, received_rate is initialized to PlusInfinity.
            scream.receivedRate() shouldBe Bandwidth.INFINITY

            // Advance sender clock by 200ms (feedback delayed on return path), but remote
            // packet receive time only advances by 50ms (less than 100ms window on the
            // receiver clock).
            clock.elapse(200.ms)
            val feedback2 = createFeedback(
                clock.instant(),
                rtt = 100.ms,
                numberOfEct1Packets = 3,
                numberOfPacketsInFlight = 20
            )
            for (pkt in feedback2.packetFeedbacks) {
                pkt.receiveTime = feedback1.packetFeedbacks.last().receiveTime + 50.ms
            }
            scream.onTransportPacketsFeedback(feedback2)

            // feedback2 has 3 packets of 1000 bytes each = 3000 bytes.
            // No new calculation because only 50ms has passed on the remote receive
            // clock, even though 200ms passed on the sender clock.
            // received_rate remains PlusInfinity.
            scream.receivedRate() shouldBe Bandwidth.INFINITY

            // Advance sender clock by another 250ms, but remote packet receive time only
            // advances by 51ms (so total 101ms has passed on the remote receive clock
            // since the last window update).
            clock.elapse(250.ms)
            val feedback3 = createFeedback(
                clock.instant(),
                rtt = 100.ms,
                numberOfEct1Packets = 2,
                numberOfPacketsInFlight = 20
            )
            for (pkt in feedback3.packetFeedbacks) {
                pkt.receiveTime = feedback2.packetFeedbacks.last().receiveTime + 51.ms
            }
            scream.onTransportPacketsFeedback(feedback3)

            // feedback3 has 2 packets of 1000 bytes each = 2000 bytes.
            // Total accumulated bytes since last calculation: 3000 (feedback2) + 2000
            // (feedback3) = 5000 bytes.
            // Total time duration passed on remote receive clock: 50ms + 51ms = 101ms
            // (sender local duration was 200ms + 250ms = 450ms).
            // Rate is calculated using remote receive duration: 5000 bytes / 101ms.
            scream.receivedRate() shouldBe rateOf(5000.bytes, 101.ms)
        }

        "MaxDataInFlightBoundedByMinMaxDataInFlightMss" {
            val screamDefault = ScreamV2(logger)
            // Default min_ref_window is 1000 bytes, max_segment_size is 1280 bytes, and
            // min_max_data_in_flight_mss is 4 -> 4 * 1280 = 5120 bytes.
            screamDefault.refWindow() shouldBe 1000.bytes
            screamDefault.maxDataInFlight() shouldBe (4 * 1280).bytes

            val screamCustom = ScreamV2(logger, ScreamV2Parameters(minMaxDataInFlightMss = 5))
            screamCustom.maxDataInFlight() shouldBe (5 * 1280).bytes
        }

        "DelayBackoffInhibitedWhenQueueDelayDecreases" {
            val clock = createClock()
            val scream = ScreamV2(logger)
            scream.setTargetBitrateConstraints(Bandwidth.ZERO, 2000.kbps, 1000.kbps)
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 25, linkCapacity = 1000.kbps),
                    sendAsEct1 = false
                )
            )

            // Overuse the 1 Mbps link by sending at 2 Mbps until queue delay builds up
            // well above the congestion threshold (30 ms).
            for (i in 0 until 10) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    2000.kbps,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)
            }
            scream.delayBasedCongestionControl().isQueueDelayDetected() shouldBe true
            scream.lastReferenceWindowDecreaseTime() shouldNotBe NEVER

            // Reduce send rate below link capacity (500 kbps) so the queue drains.
            var drainedFeedbacksWhileCongested = 0
            for (i in 0 until 20) {
                val prevQueueDelay = scream.delayBasedCongestionControl().queueDelay()
                val prevRefWindow = scream.refWindow()
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    500.kbps,
                    clock,
                    { packet -> scream.onPacketSent(packet.dataInFlight) }
                )
                scream.onTransportPacketsFeedback(feedback)

                if (scream.delayBasedCongestionControl().isQueueDelayDetected() &&
                    scream.delayBasedCongestionControl().queueDelay() < prevQueueDelay
                ) {
                    ++drainedFeedbacksWhileCongested
                    // Even though queue delay is still above the congestion threshold,
                    // reference window must not decrease while queue delay is decreasing.
                    scream.refWindow() shouldBeGreaterThanOrEqualTo prevRefWindow
                }
            }
            drainedFeedbacksWhileCongested shouldBeGreaterThan 0
        }
    }
}
