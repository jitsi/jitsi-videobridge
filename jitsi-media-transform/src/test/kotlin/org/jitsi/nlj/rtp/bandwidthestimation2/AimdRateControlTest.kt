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
package org.jitsi.nlj.rtp.bandwidthestimation2

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.times
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant

/**
 * Unit tests of AimdRateControl
 *
 * Based on WebRTC modules/remote_bitrate_estimator/aimd_rate_control_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */

val kInitialTime = Instant.ofEpochMilli(123_456)

val kMinBwePeriod = 2.secs
val kDefaultPeriod = 3.secs
val kMaxBwePeriod = 50.secs

// After an overuse, we back off to 85% to the received bitrate.
val kFractionAfterOveruse = 0.85

class AimdRateControlTest : FreeSpec() {
    init {
        "MinNearMaxIncreaseRateOnLowBandwith" {
            val aimdRateControl = AimdRateControl()
            aimdRateControl.setEstimate(30_000.bps, kInitialTime)
            aimdRateControl.getNearMaxIncreaseRateBpsPerSecond() shouldBe 4_000
        }

        "NearMaxIncreaseRateIs5kbpsOn90kbpsAnd200msRtt" {
            val aimdRateControl = AimdRateControl()
            aimdRateControl.setEstimate(90_000.bps, kInitialTime)
            aimdRateControl.getNearMaxIncreaseRateBpsPerSecond() shouldBe 5_000
        }

        "NearMaxIncreaseRateIs5kbpsOn60kbpsAnd100msRtt" {
            val aimdRateControl = AimdRateControl()
            aimdRateControl.setEstimate(60_000.bps, kInitialTime)
            aimdRateControl.rtt = 100.ms
            aimdRateControl.getNearMaxIncreaseRateBpsPerSecond() shouldBe 5_000
        }

        "GetIncreaseRateAndBandwidthPeriod" {
            val aimdRateControl = AimdRateControl()
            val kBitrate = 300_000.bps
            aimdRateControl.setEstimate(kBitrate, kInitialTime)
            aimdRateControl.update(RateControlInput(BandwidthUsage.kBwOverusing, kBitrate), kInitialTime)
            aimdRateControl.getNearMaxIncreaseRateBpsPerSecond().shouldBe(14_000.0 plusOrMinus 1_000.0)
            aimdRateControl.getExpectedBandwidthPeriod() shouldBe kDefaultPeriod
        }

        "BweLimitedByAckedBitrate" {
            val aimdRateControl = AimdRateControl()
            val kAckedBitrate = 10_000.bps
            var now = kInitialTime
            aimdRateControl.setEstimate(kAckedBitrate, now)
            while (Duration.between(kInitialTime, now) < 20.secs) {
                aimdRateControl.update(RateControlInput(BandwidthUsage.kBwNormal, kAckedBitrate), now)
                now += 100.ms
            }
            aimdRateControl.validEstimate() shouldBe true
            aimdRateControl.latestEstimate() shouldBe 1.5 * kAckedBitrate + 10_000.bps
        }

        "BweLimitedByDecreasingAckedBitrate" {
            val aimdRateControl = AimdRateControl()
            val kAckedBitrate = 10_000.bps
            var now = kInitialTime
            aimdRateControl.setEstimate(kAckedBitrate, now)
            while (Duration.between(kInitialTime, now) < 20.secs) {
                aimdRateControl.update(RateControlInput(BandwidthUsage.kBwNormal, kAckedBitrate), now)
                now += 100.ms
            }
            aimdRateControl.validEstimate() shouldBe true
            val prevEstimate = aimdRateControl.latestEstimate()
            aimdRateControl.update(
                RateControlInput(BandwidthUsage.kBwNormal, kAckedBitrate / 2),
                now
            )
            val newEstimate = aimdRateControl.latestEstimate()
            newEstimate shouldBe prevEstimate
            newEstimate.bps shouldBeInRange ((1.5 * kAckedBitrate + 10_000.bps).bps plusOrMinus 2_000)
        }

        "DefaultPeriodUntilFirstOveruse" {
            val aimdRateControl = AimdRateControl()
            aimdRateControl.setStartBitrate(300.kbps)
            aimdRateControl.getExpectedBandwidthPeriod() shouldBe kDefaultPeriod
            aimdRateControl.update(RateControlInput(BandwidthUsage.kBwOverusing, 280.kbps), kInitialTime)
            aimdRateControl.getExpectedBandwidthPeriod() shouldNotBe kDefaultPeriod
        }

        "ExpectedPeriodAfterTypicalDrop" {
            val aimdRateControl = AimdRateControl()
            // The rate increase at 216 kbps should be 12 kbps. If we drop from
            // 216 + 4*12 = 264 kbps, it should take 4 seconds to recover. Since we
            // back off to 0.85*acked_rate-5kbps, the acked bitrate needs to be 260
            // kbps to end up at 216 kbps.
            val kInitialBitrate = 264_000.bps
            val kUpdatedBitrate = 216_000.bps
            val kAckedBitrate =
                (kUpdatedBitrate + 5_000.bps) / kFractionAfterOveruse
            var now = kInitialTime
            aimdRateControl.setEstimate(kInitialBitrate, now)
            now += 100.ms
            aimdRateControl.update(RateControlInput(BandwidthUsage.kBwOverusing, kAckedBitrate), now)
            aimdRateControl.latestEstimate() shouldBe kUpdatedBitrate
            aimdRateControl.getNearMaxIncreaseRateBpsPerSecond() shouldBe 12_000
            aimdRateControl.getExpectedBandwidthPeriod() shouldBe 4.secs
        }

        "BandwidthPeriodIsNotBelowMin" {
            val aimdRateControl = AimdRateControl()
            val kInitialBitrate = 10_000.bps
            var now = kInitialTime
            aimdRateControl.setEstimate(kInitialBitrate, now)
            now += 100.ms
            aimdRateControl.update(
                RateControlInput(BandwidthUsage.kBwOverusing, kInitialBitrate - 1.bps),
                now
            )
            aimdRateControl.getExpectedBandwidthPeriod() shouldBe kMinBwePeriod
        }

        "BandwidthPeriodIsNotAboveMaxNoSmoothingExp" {
            val aimdRateControl = AimdRateControl()
            val kInitialBitrate = 10_010_000.bps
            var now = kInitialTime
            aimdRateControl.setEstimate(kInitialBitrate, now)
            now += 100.ms
            // Make a large (10 Mbps) bitrate drop to 10 kbps.
            val kAckedBitrate = 10_000.bps / kFractionAfterOveruse
            aimdRateControl.update(RateControlInput(BandwidthUsage.kBwOverusing, kAckedBitrate), now)
            aimdRateControl.getExpectedBandwidthPeriod() shouldBe kMaxBwePeriod
        }

        "SendingRateBoundedWhenThroughputNotEstimated" {
            val aimdRateControl = AimdRateControl()
            val kInitialBitrate = 123_000.bps
            var now = kInitialTime
            aimdRateControl.update(RateControlInput(BandwidthUsage.kBwNormal, kInitialBitrate), now)
            // AimdRateControl sets the initial bit rate to what it receives after
            // five seconds has passed.
            // TODO(bugs.webrtc.org/9379): The comment in the AimdRateControl does not
            // match the constant.
            val kInitializationTime = 5.secs
            now += kInitializationTime + 1.ms
            aimdRateControl.update(RateControlInput(BandwidthUsage.kBwNormal, kInitialBitrate), now)
            for (i in 0 until 100) {
                aimdRateControl.update(RateControlInput(BandwidthUsage.kBwNormal, null), now)
                now += 100.ms
            }
            aimdRateControl.latestEstimate() shouldBeLessThanOrEqualTo kInitialBitrate * 1.5 + 10_000.bps
        }

        // Skipped: EstimateDoesNotIncreaseInAlr: uses field trial WebRTC-DontIncreaseDelayBasedBweInAlr/Enabled/
        // Skipped: SetEstimateIncreaseBweInAlr: uses field trial WebRTC-DontIncreaseDelayBasedBweInAlr/Enabled/
        // Skipped: SetEstimateUpperLimitedByNetworkEstimate: uses setNetworkStateEstimate
        // Skipped: SetEstimateDefaultUpperLimitedByCurrentBitrateIfNetworkEstimateIsLow: uses setNetworkStateEstimate
        // Skipped: SetEstimateNotUpperLimitedByCurrentBitrateIfNetworkEstimateIsLowIf: uses setNetworkStateEstimate
        // Skipped: SetEstimateLowerLimitedByNetworkEstimate: uses setNetworkStateEstimate
        // Skipped: SetEstimateIgnoredIfLowerThanNetworkEstimateAndCurrent: uses setNetworkStateEstimate
        // Skipped: EstimateIncreaseWhileNotInAlr: uses field trial WebRTC-DontIncreaseDelayBasedBweInAlr/Enabled/
        // Skipped: EstimateNotLimitedByNetworkEstimateIfDisabled: uses setNetworkStateEstimate
    }
}

infix fun Long.plusOrMinus(tolerance: Long) = LongRange(this - tolerance, this + tolerance)
