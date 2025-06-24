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
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.bps
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import java.time.Instant

/**
 * Unit tests for Loss-Based BWE,
 * based on WebRTC modules/congestion_controller/goog_cc/send_side_bandwidth_estimation_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Tests of WebRTC's RtcEventLog and tests of field trial parameters are skipped.
 */

class SendSideBandwidthEstimationTest : FreeSpec() {
    val logger = createLogger()
    val diagnosticContext = DiagnosticContext()

    fun testProbing(useDelayBased: Boolean) {
        val bwe = SendSideBandwidthEstimation(logger, diagnosticContext)
        var nowMs = 0L
        bwe.setMinMaxBitrate(100000.bps, 1500000.bps)
        bwe.setSendBitrate(200000.bps, Instant.ofEpochMilli(nowMs))

        val kRembBps = 1000000
        val kSecondRembBps = kRembBps + 500000

        bwe.updatePacketsLost(packetsLost = 0, numberOfPackets = 1, Instant.ofEpochMilli(nowMs))
        bwe.updateRtt(50.ms, Instant.ofEpochMilli(nowMs))

        // Initial REMB applies immediately.
        if (useDelayBased) {
            bwe.updateDelayBasedEstimate(Instant.ofEpochMilli(nowMs), kRembBps.bps)
        } else {
            bwe.updateReceiverEstimate(Instant.ofEpochMilli(nowMs), kRembBps.bps)
        }
        bwe.updateEstimate(Instant.ofEpochMilli(nowMs))
        bwe.targetRate().bps shouldBe kRembBps

        // Second REMB doesn't apply immediately.
        nowMs += 2001
        if (useDelayBased) {
            bwe.updateDelayBasedEstimate(Instant.ofEpochMilli(nowMs), kSecondRembBps.bps)
        } else {
            bwe.updateReceiverEstimate(Instant.ofEpochMilli(nowMs), kSecondRembBps.bps)
        }
        bwe.updateEstimate(Instant.ofEpochMilli(nowMs))
        bwe.targetRate().bps shouldBe kRembBps
    }

    init {
        "InitialRembWithProbing" {
            testProbing(false)
        }

        "InitialDelayBasedBweWithProbing" {
            testProbing(true)
        }

        "DoesntReapplyBitrateDecreaseWithoutFollowingRemb" {
            val bwe = SendSideBandwidthEstimation(logger, diagnosticContext)
            val kMinBitrateBps = 100000L
            val kInitialBitrateBps = 1000000L
            var nowMs = 1000L
            bwe.setMinMaxBitrate(kMinBitrateBps.bps, 1500000.bps)
            bwe.setSendBitrate(kInitialBitrateBps.bps, Instant.ofEpochMilli(nowMs))

            val kFractionLoss: UByte = 128u
            val kRttMs = 50L
            nowMs += 10000

            bwe.targetRate().bps shouldBe kInitialBitrateBps
            bwe.fractionLoss() shouldBe 0u
            bwe.roundTripTime().toMillis() shouldBe 0

            // Signal heavy loss to go down in bitrate.
            bwe.updatePacketsLost(packetsLost = 50, numberOfPackets = 100, Instant.ofEpochMilli(nowMs))
            bwe.updateRtt(kRttMs.ms, Instant.ofEpochMilli(nowMs))

            // Trigger an update 2 seconds later to not be rate limited.
            nowMs += 1000
            bwe.updateEstimate(Instant.ofEpochMilli(nowMs))
            bwe.targetRate().bps shouldBeLessThan kInitialBitrateBps
            // Verify that the obtained bitrate isn't hitting the min bitrate, or this
            // test doesn't make sense. If this ever happens, update the thresholds or
            // loss rates so that it doesn't hit min bitrate after one bitrate update.
            bwe.targetRate().bps shouldBeGreaterThan kMinBitrateBps
            bwe.fractionLoss() shouldBe kFractionLoss
            bwe.roundTripTime().toMillis() shouldBe kRttMs

            // Triggering an update shouldn't apply further downgrade nor upgrade since
            // there's no intermediate receiver block received indicating whether this is
            // currently good or not.
            val lastBitrateBps = bwe.targetRate().bps
            // Trigger an update 2 seconds later to not be rate limited (but it still
            // shouldn't update).
            nowMs += 1000
            bwe.updateEstimate(Instant.ofEpochMilli(nowMs))

            bwe.targetRate().bps shouldBe lastBitrateBps
            // The old loss rate should still be applied though.
            bwe.fractionLoss() shouldBe kFractionLoss
            bwe.roundTripTime().toMillis() shouldBe kRttMs
        }

        "SettingSendBitrateOverridesDelayBasedEstimate" {
            val bwe = SendSideBandwidthEstimation(logger, diagnosticContext)
            val kMinBitrateBps = 10000L
            val kMaxBitrateBps = 10000000L
            val kInitialBitrateBps = 300000L
            val kDelayBasedBitrateBps = 350000L
            val kForcedHighBitrate = 2500000L

            var nowMs = 0L

            bwe.setMinMaxBitrate(kMinBitrateBps.bps, kMaxBitrateBps.bps)
            bwe.setSendBitrate(kInitialBitrateBps.bps, Instant.ofEpochMilli(nowMs))

            bwe.updateDelayBasedEstimate(Instant.ofEpochMilli(nowMs), kDelayBasedBitrateBps.bps)
            bwe.updateEstimate(Instant.ofEpochMilli(nowMs))
            bwe.targetRate().bps shouldBeGreaterThanOrEqualTo kInitialBitrateBps
            bwe.targetRate().bps shouldBeLessThanOrEqualTo kDelayBasedBitrateBps

            bwe.setSendBitrate(kForcedHighBitrate.bps, Instant.ofEpochMilli(nowMs))
            bwe.targetRate().bps shouldBe kForcedHighBitrate
        }

        "FractionLossIsNotOverflowed" {
            val bwe = SendSideBandwidthEstimation(logger, diagnosticContext)
            val kMinBitrateBps = 100000L
            val kInitialBitrateBps = 1000000L
            var nowMs = 1000L

            bwe.setMinMaxBitrate(kMinBitrateBps.bps, 1500000.bps)
            bwe.setSendBitrate(kInitialBitrateBps.bps, Instant.ofEpochMilli(nowMs))

            nowMs += 10000

            bwe.targetRate().bps shouldBe kInitialBitrateBps
            bwe.fractionLoss() shouldBe 0u

            // Signal negative loss.
            bwe.updatePacketsLost(packetsLost = -1, numberOfPackets = 100, Instant.ofEpochMilli(nowMs))
            bwe.fractionLoss() shouldBe 0u
        }

        "RttIsAboveLimitIfRttGreaterThanLimit" {
            val bwe = SendSideBandwidthEstimation(logger, diagnosticContext)
            val kMinBitrateBps = 10000L
            val kMaxBitrateBps = 10000000L
            val kInitialBitrateBps = 300000L
            var nowMs = 0L
            bwe.setMinMaxBitrate(kMinBitrateBps.bps, kMaxBitrateBps.bps)
            bwe.setSendBitrate(kInitialBitrateBps.bps, Instant.ofEpochMilli(nowMs))
            bwe.updatePropagationRtt(Instant.ofEpochMilli(nowMs), 5000.ms)
            bwe.isRttAboveLimit() shouldBe true
        }

        "RttIsBelowLimitIfRttLessThanLimit" {
            val bwe = SendSideBandwidthEstimation(logger, diagnosticContext)
            val kMinBitrateBps = 10000L
            val kMaxBitrateBps = 10000000L
            val kInitialBitrateBps = 300000L
            var nowMs = 0L
            bwe.setMinMaxBitrate(kMinBitrateBps.bps, kMaxBitrateBps.bps)
            bwe.setSendBitrate(kInitialBitrateBps.bps, Instant.ofEpochMilli(nowMs))
            bwe.updatePropagationRtt(Instant.ofEpochMilli(nowMs), 1000.ms)
            bwe.isRttAboveLimit() shouldBe false
        }
    }
}
