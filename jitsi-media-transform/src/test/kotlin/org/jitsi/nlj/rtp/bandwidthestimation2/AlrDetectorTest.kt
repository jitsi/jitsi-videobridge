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

package org.jitsi.nlj.rtp.bandwidthestimation2
/**
 * Unit tests for AlrDetector.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/alr_detector_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138), skipping tests of field trials.
 */

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

const val kEstimatedBitrateBps = 300000

data class TimestampHolder(
    var time: Long
)

class SimulateOutgoingTrafficIn(
    val alrDetector: AlrDetector,
    var timestampMs: TimestampHolder
) {
    fun forTimeMs(timeMs: Int): SimulateOutgoingTrafficIn {
        intervalMs = timeMs
        produceTraffic()
        return this
    }

    fun atPercentOfEstimatedBitrate(usagePercentage: Int): SimulateOutgoingTrafficIn {
        this.usagePercentage = usagePercentage
        produceTraffic()
        return this
    }

    private fun produceTraffic() {
        val intervalMs = intervalMs
        val usagePercentage = usagePercentage
        if (intervalMs == null || usagePercentage == null) {
            return
        }
        val kTimeStepMs = 10
        for (t in 0 until intervalMs step kTimeStepMs) {
            timestampMs.time += kTimeStepMs
            alrDetector.onBytesSent(
                kEstimatedBitrateBps.toLong() * usagePercentage * kTimeStepMs / (8 * 100 * 1000),
                timestampMs.time
            )
        }
        val remainderMs = intervalMs % kTimeStepMs
        if (remainderMs > 0) {
            timestampMs.time += kTimeStepMs
            alrDetector.onBytesSent(
                kEstimatedBitrateBps.toLong() * usagePercentage * kTimeStepMs / (8 * 100 * 1000),
                timestampMs.time
            )
        }
    }

    private var intervalMs: Int? = null
    private var usagePercentage: Int? = null
}

class AlrDetectorTest : FreeSpec() {
    init {
        "AlrDetection" {
            val timestampMs = TimestampHolder(1000)
            val alrDetector = AlrDetector()
            alrDetector.setEstimatedBitrate(kEstimatedBitrateBps)

            // Start in non-ALR state
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null

            // Stay in non-ALR state when usage is close to 100%.
            SimulateOutgoingTrafficIn(alrDetector, timestampMs)
                .forTimeMs(1000)
                .atPercentOfEstimatedBitrate(90)
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null

            // Verify that we ALR starts when bitrate drops below 20%.
            SimulateOutgoingTrafficIn(alrDetector, timestampMs)
                .forTimeMs(1500)
                .atPercentOfEstimatedBitrate(20)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // Verify that ALR ends when usage is above 65%.
            SimulateOutgoingTrafficIn(alrDetector, timestampMs)
                .forTimeMs(4000)
                .atPercentOfEstimatedBitrate(100)

            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null
        }

        "ShortSpike" {
            val timestampMs = TimestampHolder(1000)
            val alrDetector = AlrDetector()
            alrDetector.setEstimatedBitrate(kEstimatedBitrateBps)

            // Start in non-ALR state
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null

            // Verify that we ALR starts when bitrate drops below 20%.
            SimulateOutgoingTrafficIn(alrDetector, timestampMs)
                .forTimeMs(1000)
                .atPercentOfEstimatedBitrate(20)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // Verify that we stay in ALR region even after a short bitrate spike.
            SimulateOutgoingTrafficIn(alrDetector, timestampMs)
                .forTimeMs(100)
                .atPercentOfEstimatedBitrate(150)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // ALR ends when usage is above 65%.
            SimulateOutgoingTrafficIn(alrDetector, timestampMs)
                .forTimeMs(3000)
                .atPercentOfEstimatedBitrate(100)
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null
        }

        "BandwidthEstimateChanges" {
            val timestampMs = TimestampHolder(1000)
            val alrDetector = AlrDetector()
            alrDetector.setEstimatedBitrate(kEstimatedBitrateBps)

            // Start in non-ALR state
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null

            // ALR starts when bitrate drops below 20%.
            SimulateOutgoingTrafficIn(alrDetector, timestampMs)
                .forTimeMs(1000)
                .atPercentOfEstimatedBitrate(20)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // When bandwidth estimate drops the detector should stay in ALR mode and quit
            // it shortly afterwards as the sender continues sending the same amount of
            // traffic. This is necessary to ensure that ProbeController can still react
            // to the BWE drop by initiating a new probe.
            alrDetector.setEstimatedBitrate(kEstimatedBitrateBps / 5)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null
            SimulateOutgoingTrafficIn(alrDetector, timestampMs)
                .forTimeMs(1000)
                .atPercentOfEstimatedBitrate(50)
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null
        }
    }
}
