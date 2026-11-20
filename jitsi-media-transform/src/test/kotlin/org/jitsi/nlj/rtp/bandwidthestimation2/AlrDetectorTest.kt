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
/*
 * Unit tests for AlrDetector.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/alr_detector_unittest.cc in
 * WebRTC tag branch-heads/8078 (Chromium 156), skipping tests of field trials.
 */

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.times
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import java.time.Instant

val kEstimatedBitrate = 300_000.bps

class SimulateOutgoingTrafficIn(
    val alrDetector: AlrDetector,
    val clock: FakeClock
) {
    fun forTime(time: Duration): SimulateOutgoingTrafficIn {
        interval = time
        produceTraffic()
        return this
    }

    fun atPercentOfEstimatedBitrate(usagePercentage: Int): SimulateOutgoingTrafficIn {
        this.usagePercentage = usagePercentage
        produceTraffic()
        return this
    }

    private fun produceTraffic() {
        val interval = interval
        val usagePercentage = usagePercentage
        if (interval == null || usagePercentage == null) {
            return
        }
        val kTimeStep = 10.ms
        var t = Duration.ZERO
        while (t < interval) {
            clock.elapse(kTimeStep)
            alrDetector.onBytesSent(
                // WebRTC's DataSize is a whole number of bytes, and dividing it by an integer truncates.
                ((kEstimatedBitrate * usagePercentage * kTimeStep).bytes.toLong() / 100).bytes,
                clock.instant()
            )
            t += kTimeStep
        }
        // As of now all tests use interval that is a multiple of 10ms.
        check(interval.toMillis() % kTimeStep.toMillis() == 0L)
    }

    private var interval: Duration? = null
    private var usagePercentage: Int? = null
}

class AlrDetectorTest : FreeSpec() {
    init {
        "AlrDetection" {
            val clock = FakeClock().apply { setTime(Instant.ofEpochSecond(1)) }
            val alrDetector = AlrDetector(clock)
            alrDetector.setEstimatedBitrate(kEstimatedBitrate)

            // Start in non-ALR state
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null

            // Stay in non-ALR state when usage is close to 100%.
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(1000.ms)
                .atPercentOfEstimatedBitrate(90)
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null

            // Verify that we ALR starts when bitrate drops below 20%.
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(1500.ms)
                .atPercentOfEstimatedBitrate(20)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // Verify that ALR ends when usage is above 65%.
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(4000.ms)
                .atPercentOfEstimatedBitrate(100)

            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null
        }

        "ShortSpike" {
            val clock = FakeClock().apply { setTime(Instant.ofEpochSecond(1)) }
            val alrDetector = AlrDetector(clock)
            alrDetector.setEstimatedBitrate(kEstimatedBitrate)

            // Start in non-ALR state
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null

            // Verify that we ALR starts when bitrate drops below 20%.
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(1000.ms)
                .atPercentOfEstimatedBitrate(20)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // Verify that we stay in ALR region even after a short bitrate spike.
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(100.ms)
                .atPercentOfEstimatedBitrate(150)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // ALR ends when usage is above 65%.
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(3000.ms)
                .atPercentOfEstimatedBitrate(100)
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null
        }

        "BandwidthEstimateChanges" {
            val clock = FakeClock().apply { setTime(Instant.ofEpochSecond(1)) }
            val alrDetector = AlrDetector(clock)
            alrDetector.setEstimatedBitrate(kEstimatedBitrate)

            // Start in non-ALR state
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null

            // ALR starts when bitrate drops below 20%.
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(1000.ms)
                .atPercentOfEstimatedBitrate(20)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // When bandwidth estimate drops the detector should stay in ALR mode and quit
            // it shortly afterwards as the sender continues sending the same amount of
            // traffic. This is necessary to ensure that ProbeController can still react
            // to the BWE drop by initiating a new probe.
            alrDetector.setEstimatedBitrate(kEstimatedBitrate / 5)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(1000.ms)
                .atPercentOfEstimatedBitrate(50)
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null
        }

        // Jitsi local: WebRTC has no test which tells the stop level from the start level.
        "StopLevelIsBelowStartLevel" {
            val clock = FakeClock().apply { setTime(Instant.ofEpochSecond(1)) }
            val alrDetector = AlrDetector(clock)
            alrDetector.setEstimatedBitrate(kEstimatedBitrate)

            // ALR starts when bitrate drops below 20%. The budget fills up.
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(1000.ms)
                .atPercentOfEstimatedBitrate(20)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // The budget level is now 0.25, below the start level (0.40) and above the stop level (-0.60).
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(1500.ms)
                .atPercentOfEstimatedBitrate(100)
            alrDetector.getApplicationLimitedRegionStartTime() shouldNotBe null

            // The budget level is now -0.75, below the stop level.
            SimulateOutgoingTrafficIn(alrDetector, clock)
                .forTime(2000.ms)
                .atPercentOfEstimatedBitrate(100)
            alrDetector.getApplicationLimitedRegionStartTime() shouldBe null
        }
    }
}
