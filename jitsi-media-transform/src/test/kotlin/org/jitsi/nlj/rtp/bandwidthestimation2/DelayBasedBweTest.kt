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
import io.kotest.matchers.equals.shouldNotBeEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.kbps
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms

/**
 * Unit tests for Delay-Based BWE,
 * based on WebRTC modules/congestion_controller/goog_cc/delay_based_bwe_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 */
class DelayBasedBweTest : FreeSpec() {
    val logger = createLogger()
    val diagnosticContext = DiagnosticContext()

    init {
        "ProbeDetection" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)

            var nowMs = test.clock.millis()

            // First burst sent at 8 * 1000 / 10 = 800 kbps.
            repeat(kNumProbesCluster0) {
                test.clock.elapse(10.ms)
                nowMs = test.clock.millis()
                test.incomingFeedback(nowMs, nowMs, 1000, kPacingInfo0)
            }
            test.bitrateObserver.updated shouldBe true

            // Second burst sent at 8 * 1000 / 5 = 1600 kbps.
            repeat(kNumProbesCluster1) {
                test.clock.elapse(5.ms)
                nowMs = test.clock.millis()
                test.incomingFeedback(nowMs, nowMs, 1000, kPacingInfo1)
            }
            test.bitrateObserver.updated shouldBe true
            test.bitrateObserver.latestBitrate shouldBeGreaterThan 1500000
        }

        "ProbeDetectionNonPacedPackets" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            var nowMs = test.clock.millis()
            // First burst sent at 8 * 1000 / 10 = 800 kbps, but with every other packet
            // not being paced which could mess things up.
            repeat(kNumProbesCluster0) {
                test.clock.elapse(5.ms)
                nowMs = test.clock.millis()
                test.incomingFeedback(nowMs, nowMs, 1000, kPacingInfo0)
                // Non-paced packet, arriving 5 ms after.
                test.clock.elapse(5.ms)
                test.incomingFeedback(nowMs, nowMs, 100, PacedPacketInfo())
            }

            test.bitrateObserver.updated shouldBe true
            test.bitrateObserver.latestBitrate shouldBeGreaterThan 800000
        }

        "ProbeDetectionFasterArrival" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            var nowMs = test.clock.millis()
            // First burst sent at 8 * 1000 / 10 = 800 kbps.
            // Arriving at 8 * 1000 / 5 = 1600 kbps.
            var sendTimeMs = 0L
            repeat(kNumProbesCluster0) {
                test.clock.elapse(1.ms)
                sendTimeMs += 10
                nowMs = test.clock.millis()
                test.incomingFeedback(nowMs, sendTimeMs, 1000, kPacingInfo0)
            }

            test.bitrateObserver.updated shouldBe false
        }

        "ProbeDetectionSlowerArrival" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            var nowMs = test.clock.millis()
            // First burst sent at 8 * 1000 / 5 = 1600 kbps.
            // Arriving at 8 * 1000 / 7 = 1142 kbps.
            // Since the receive rate is significantly below the send rate, we expect to
            // use 95% of the estimated capacity.
            var sendTimeMs = 0L
            repeat(kNumProbesCluster1) {
                test.clock.elapse(7.ms)
                sendTimeMs += 5
                nowMs = test.clock.millis()
                test.incomingFeedback(nowMs, sendTimeMs, 1000, kPacingInfo1)
            }

            test.bitrateObserver.updated shouldBe true
            test.bitrateObserver.latestBitrate shouldBeInRange
                ((kTargetUtilizationFraction * 1140000).toLong() plusOrMinus 10000)
        }

        "ProbeDetectionSlowerArrivalHighBitrate" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            var nowMs = test.clock.millis()
            // Burst sent at 8 * 1000 / 1 = 8000 kbps.
            // Arriving at 8 * 1000 / 2 = 4000 kbps.
            // Since the receive rate is significantly below the send rate, we expect to
            // use 95% of the estimated capacity.
            var sendTimeMs = 0L
            repeat(kNumProbesCluster1) {
                test.clock.elapse(2.ms)
                sendTimeMs += 1
                nowMs = test.clock.millis()
                test.incomingFeedback(nowMs, sendTimeMs, 1000, kPacingInfo1)
            }

            test.bitrateObserver.updated shouldBe true
            test.bitrateObserver.latestBitrate shouldBeInRange
                ((kTargetUtilizationFraction * 4000000).toLong() plusOrMinus 10000)
        }

        "GetExpectedBwePeriodMs" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            val defaultInterval = test.bitrateEstimator.getExpectedBwePeriod()
            defaultInterval.toMillis() shouldBeGreaterThan 0
            test.capacityDropTestHelper(1, true, 533, 0)
            val interval = test.bitrateEstimator.getExpectedBwePeriod()
            interval.toMillis() shouldBeGreaterThan 0
            interval shouldNotBeEqual defaultInterval
        }

        "InitialBehavior" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            test.initialBehaviorTestHelper(730000)
        }

        "InitializeResult" {
            val result = DelayBasedBwe.Result()
            result.delayDetectorState shouldBe BandwidthUsage.kBwNormal
        }

        "RateIncreaseReordering" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            test.rateIncreaseReorderingTestHelper(730000)
        }

        "RateIncreaseRtpTimestamps" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            test.rateIncreaseRtpTimestampsTestHelper(617)
        }

        "CapacityDropOneStream" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            test.capacityDropTestHelper(1, false, 500, 0)
        }

        "CapacityDropPosOffsetChange" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            test.capacityDropTestHelper(1, false, 867, 30000)
        }

        "CapacityDropNegOffsetChange" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            test.capacityDropTestHelper(1, false, 933, -30000)
        }

        "CapacityDropOneStreamWrap" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            test.capacityDropTestHelper(1, true, 533, 0)
        }

        "TestTimestampGrouping" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            test.testTimestampGroupingTestHelper()
        }

        "TestShortTimeoutAndWrap" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            // Simulate a client leaving and rejoining the call after 35 seconds. This
            // will make abs send time wrap, so if streams aren't timed out properly
            // the next 30 seconds of packets will be out of order.
            test.testWrappingHelper(35)
        }

        "TestLongTimeoutAndWrap" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            // Simulate a client leaving and rejoining the call after some multiple of
            // 64 seconds later. This will cause a zero difference in abs send times due
            // to the wrap, but a big difference in arrival time, if streams aren't
            // properly timed out.
            test.testWrappingHelper(10 * 64)
        }

        "TestInitialOveruse" {
            val test = OneDelayBasedBweTest(logger, diagnosticContext)
            val kStartBitrate = 300.kbps
            val kInitialCapacity = 200.kbps
            // High FPS to ensure that we send a lot of packets in a short time.
            val kFps = 90

            test.streamGenerator.addStream(RtpStream(kFps, kStartBitrate.bps))
            test.streamGenerator.setCapacityBps(kInitialCapacity.bps)

            // Needed to initialize the AimdRateControl.
            test.bitrateEstimator.setStartBitrate(kStartBitrate)

            // Produce 40 frames (in 1/3 second) and give them to the estimator.
            var bitrateBps = kStartBitrate.bps
            var seenOveruse = false
            for (i in 0 until 40) {
                val overuse = test.generateAndProcessFrame(bitrateBps)
                if (overuse) {
                    test.bitrateObserver.updated shouldBe true
                    test.bitrateObserver.latestBitrate shouldBeLessThanOrEqual kInitialCapacity.bps
                    test.bitrateObserver.latestBitrate shouldBeGreaterThan (0.8 * kInitialCapacity.bps).toLong()
                    bitrateBps = test.bitrateObserver.latestBitrate
                    seenOveruse = true
                    break
                } else if (test.bitrateObserver.updated) {
                    bitrateBps = test.bitrateObserver.latestBitrate
                    test.bitrateObserver.reset()
                }
            }
            seenOveruse shouldBe true
            test.bitrateObserver.latestBitrate shouldBeLessThanOrEqual kInitialCapacity.bps
            test.bitrateObserver.latestBitrate shouldBeGreaterThan (0.8 * kInitialCapacity.bps).toLong()
        }
    }

    companion object {
        const val kNumProbesCluster0 = 5
        const val kNumProbesCluster1 = 8
        val kPacingInfo0 = PacedPacketInfo(0, kNumProbesCluster0, 2000)
        val kPacingInfo1 = PacedPacketInfo(1, kNumProbesCluster1, 4000)
        const val kTargetUtilizationFraction = 0.95
    }
}
