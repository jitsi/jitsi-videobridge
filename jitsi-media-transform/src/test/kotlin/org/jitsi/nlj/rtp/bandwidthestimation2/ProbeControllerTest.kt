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
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.times
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import java.time.Instant

val kMinBitrate = 100.bps
val kStartBitrate = 300.bps
val kMaxBitrate = 10000.bps
val kMbpsMultiplier = 1000.kbps

val kExponentialProbingTimeout = 5.secs

val kAlrProbeInterval = 5.secs
private val kAlrEndedTimeout = 3.secs
private val kBitrateDropTimeout = 5.secs

/** Unit tests for ProbeController,
 * based on WebRTC modules/congestion_controller/goog_cc/probe_controller_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings.
 */
class ProbeControllerTest : FreeSpec() {
    class ProbeControllerFixture(
        val config: ProbeControllerConfig = ProbeControllerConfig()
    ) {
        private val clock = FakeClock().apply { setTime(Instant.ofEpochMilli(100000L)) }

        fun createController(): ProbeController = ProbeController(logger, diagnosticContext, config)

        fun currentTime() = clock.instant()
        fun advanceTime(delta: Duration) = clock.elapse(delta)
    }

    init {
        "InitiatesProbingAfterSetBitrates" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true

            val probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThanOrEqual 2
        }

        "InitiatesProbingWhenNetworkAvailable" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()

            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.isEmpty() shouldBe true
            probes = probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true))
            probes.size shouldBeGreaterThanOrEqual 2
        }

        "SetsDefaultTargetDurationAndTargetProbeCount" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            val probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThanOrEqual 2

            probes[0].targetDuration shouldBe 15.ms
            probes[0].targetProbeCount shouldBe 5
        }

        "FieldTrialsOverrideDefaultTargetDurationAndTargetProbeCount" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig(
                    minProbePacketsSent = 2,
                    minProbeDuration = 123.ms
                )
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            val probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThanOrEqual 2

            probes[0].targetDuration shouldBe 123.ms
            probes[0].targetProbeCount shouldBe 2
        }

        "ProbeOnlyWhenNetworkIsUp" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            var probes = probeController.onNetworkAvailability(
                NetworkAvailability(atTime = fixture.currentTime(), networkAvailable = false)
            )
            probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.isEmpty() shouldBe true
            probes = probeController.onNetworkAvailability(
                NetworkAvailability(atTime = fixture.currentTime(), networkAvailable = true)
            )
            probes.size shouldBeGreaterThanOrEqual 2
        }

        "CanConfigureInitialProbeRateFactor" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig(
                    firstExponentialProbeScale = 2.0,
                    secondExponentialProbeScale = 3.0
                )
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            val probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThanOrEqual 2
            probes[0].targetDataRate shouldBe kStartBitrate * 2
            probes[1].targetDataRate shouldBe kStartBitrate * 3
        }

        "DisableSecondInitialProbeIfRateFactorZero" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig(
                    firstExponentialProbeScale = 2.0,
                    secondExponentialProbeScale = 0.0
                )
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            val probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBe kStartBitrate * 2
        }

        "InitiatesProbingOnMaxBitrateIncrease" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            // Long enough to time out exponential probing.
            fixture.advanceTime(kExponentialProbingTimeout)
            probes = probeController.setEstimatedBitrate(
                kStartBitrate,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes = probeController.process(fixture.currentTime())
            probes = probeController.setBitrates(
                kMinBitrate,
                kStartBitrate,
                kMaxBitrate + 100.bps,
                fixture.currentTime()
            )
            probes.size shouldBe 1
            probes[0].targetDataRate.bps shouldBe kMaxBitrate.bps + 100
        }

        "ProbesOnMaxAllocatedBitrateIncreaseOnlyWhenInAlr" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                kMaxBitrate - 1.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )

            // Wait long enough to time out exponential probing.
            fixture.advanceTime(kExponentialProbingTimeout)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true

            // Probe when in alr.
            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            probes = probeController.onMaxTotalAllocatedBitrate(
                kMaxBitrate + 1.bps,
                fixture.currentTime()
            )
            probes.size shouldBe 2
            probes[0].targetDataRate shouldBe kMaxBitrate

            // Do not probe when not in alr.
            probeController.setAlrStartTimeMs(null)
            probes = probeController.onMaxTotalAllocatedBitrate(
                kMaxBitrate + 2.bps,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true
        }

        "ProbesOnMaxAllocatedBitrateLimitedByCurrentBwe" {
            val fixture = ProbeControllerFixture()
            kMaxBitrate shouldBeGreaterThan 1.5 * kStartBitrate
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                kStartBitrate,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )

            // Wait long enough to time out exponential probing.
            fixture.advanceTime(kExponentialProbingTimeout)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true

            // Probe when in alr.
            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            probes = probeController.onMaxTotalAllocatedBitrate(kMaxBitrate, fixture.currentTime())
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBe 2.0 * kStartBitrate

            // Continue probing if probe succeeds.
            probes = probeController.setEstimatedBitrate(
                1.5 * kStartBitrate,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBeGreaterThan 2.0 * kStartBitrate
        }

        "CanDisableProbingOnMaxTotalAllocatedBitrateIncrease" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig(
                    probeOnMaxAllocatedBitrateChange = false
                )
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                kMaxBitrate - 1.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            fixture.advanceTime(kExponentialProbingTimeout)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true
            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())

            // Do no probe, since probe_max_allocation:false.
            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            probes = probeController.onMaxTotalAllocatedBitrate(kMaxBitrate + 1.bps, fixture.currentTime())
            probes.isEmpty() shouldBe true
        }

        "InitiatesProbingOnMaxBitrateIncreaseAtMaxBitrate" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            // Long enough to time out exponential probing.
            fixture.advanceTime(kExponentialProbingTimeout)
            probes = probeController.setEstimatedBitrate(
                kStartBitrate,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes = probeController.process(fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                kMaxBitrate,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes = probeController.setBitrates(
                kMinBitrate,
                kStartBitrate,
                kMaxBitrate + 100.bps,
                fixture.currentTime()
            )
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBe kMaxBitrate + 100.bps
        }

        "TestExponentialProbing" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())

            // Repeated probe should only be sent when estimated bitrate climbs above
            // 0.7 * 6 * kStartBitrate = 1260.
            probes = probeController.setEstimatedBitrate(
                1000.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true

            probes = probeController.setEstimatedBitrate(
                1800.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.size shouldBe 1
            probes[0].targetDataRate.bps shouldBe 2 * 1800
        }

        "ExponentialProbingStopIfMaxBitrateLow" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig(
                    abortFurtherProbeIfMaxLowerThanCurrent = true
                )
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThan 0

            // Repeated probe normally is sent when estimated bitrate climbs above
            // 0.7 * 6 * kStartBitrate = 1260. But since max bitrate is low, expect
            // exponential probing to stop.
            probes = probeController.setBitrates(
                kMinBitrate,
                kStartBitrate,
                maxBitrate = kStartBitrate,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true

            probes = probeController.setEstimatedBitrate(
                1800.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true
        }

        "ExponentialProbingStopIfMaxAllocatedBitrateLow" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig(
                    abortFurtherProbeIfMaxLowerThanCurrent = true
                )
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThan 0

            // Repeated probe normally is sent when estimated bitrate climbs above
            // 0.7 * 6 * kStartBitrate = 1260. But since allocated bitrate i slow, expect
            // exponential probing to stop.
            probes = probeController.onMaxTotalAllocatedBitrate(kStartBitrate, fixture.currentTime())
            probes.isEmpty() shouldBe true

            probes = probeController.setEstimatedBitrate(
                1800.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true
        }

        "InitialProbingToLowMaxAllocatedbitrate" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThan 0

            // Repeated probe is sent when estimated bitrate climbs above
            // 0.7 * 6 * kStartBitrate = 1260.
            probes = probeController.onMaxTotalAllocatedBitrate(kStartBitrate, fixture.currentTime())
            probes.isEmpty() shouldBe true

            // If the inital probe result is received, a new probe is sent at 2x the
            // needed max bitrate.
            probes = probeController.setEstimatedBitrate(
                1800.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.size shouldBe 1
            probes[0].targetDataRate.bps shouldBe 2 * kStartBitrate.bps
        }

        "InitialProbingTimeout" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThan 0
            // Advance far enough to cause a time out in waiting for probing result.
            fixture.advanceTime(kExponentialProbingTimeout)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true
            probes = probeController.setEstimatedBitrate(
                1800.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true
        }

        "RepeatedInitialProbingSendsNewProbeAfterTimeout" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.enableRepeatedInitialProbing(true)
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThan 0
            val startTime = fixture.currentTime()
            var lastProbeTime = fixture.currentTime()
            while (fixture.currentTime() < startTime + 5.secs) {
                fixture.advanceTime(100.ms)
                probes = probeController.process(fixture.currentTime())
                if (probes.isNotEmpty()) {
                    // Expect a probe every second.
                    Duration.between(lastProbeTime, fixture.currentTime()) shouldBe 1100.ms
                    probes[0].minProbeDelta shouldBe 20.ms
                    probes[0].targetDuration shouldBe 100.ms
                    lastProbeTime = fixture.currentTime()
                } else {
                    Duration.between(lastProbeTime, fixture.currentTime()) shouldBeLessThan 1100.ms
                }
            }
            fixture.advanceTime(
                1.secs
            )
            // After 5s, repeated initial probing stops.
            probeController.process(fixture.currentTime()).isEmpty() shouldBe true
        }

        "RepeatedInitialProbingStopIfMaxAllocatedBitrateSet" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.enableRepeatedInitialProbing(true)
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThan 0

            fixture.advanceTime(1100.ms)
            probes = probeController.process(fixture.currentTime())
            probes.size shouldBe 1
            probes = probeController.onMaxTotalAllocatedBitrate(kMinBitrate, fixture.currentTime())
            fixture.advanceTime(1100.ms)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true
        }

        "RequestProbeInAlr" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBeGreaterThanOrEqual 2
            probes = probeController.setEstimatedBitrate(
                500.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )

            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            fixture.advanceTime(kAlrEndedTimeout + 1.ms)
            probes = probeController.process(fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                250.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes = probeController.requestProbe(fixture.currentTime())

            probes.size shouldBe 1
            probes[0].targetDataRate.bps shouldBe (0.85 * 500).toLong()
        }

        "RequestProbeWhenAlrEndedRecently" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBe 2
            probes = probeController.setEstimatedBitrate(
                500.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )

            probeController.setAlrStartTimeMs(null)
            fixture.advanceTime(kAlrProbeInterval + 1.ms)
            probes = probeController.process(fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                250.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probeController.setAlrEndedTimeMs(fixture.currentTime().toEpochMilli())
            fixture.advanceTime(kAlrEndedTimeout - 1.ms)
            probes = probeController.requestProbe(fixture.currentTime())

            probes.size shouldBe 1
            probes[0].targetDataRate.bps shouldBe (0.85 * 500).toLong()
        }

        "RequestProbeWhenAlrNotEndedRecently" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBe 2
            probes = probeController.setEstimatedBitrate(
                500.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )

            probeController.setAlrStartTimeMs(null)
            fixture.advanceTime(kAlrProbeInterval + 1.ms)
            probes = probeController.process(fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                250.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probeController.setAlrEndedTimeMs(fixture.currentTime().toEpochMilli())
            fixture.advanceTime(kAlrEndedTimeout + 1.ms)
            probes = probeController.requestProbe(fixture.currentTime())

            probes.isEmpty() shouldBe true
        }

        "RequestProbeWhenBweDropNotRecent" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBe 2
            probes = probeController.setEstimatedBitrate(
                500.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )

            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            fixture.advanceTime(kAlrProbeInterval + 1.ms)
            probes = probeController.process(fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                250.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            fixture.advanceTime(kBitrateDropTimeout + 1.ms)
            probes = probeController.requestProbe(fixture.currentTime())
            probes.isEmpty() shouldBe true
        }

        "PeriodicProbing" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            probeController.enablePeriodicAlrProbing(true)
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBe 2
            probes = probeController.setEstimatedBitrate(
                500.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )

            val startTime = fixture.currentTime()

            // Expect the controller to send a new probe after 5s has passed.
            probeController.setAlrStartTimeMs(startTime.toEpochMilli())
            fixture.advanceTime(5.secs)
            probes = probeController.process(fixture.currentTime())
            probes.size shouldBe 1
            probes[0].targetDataRate.bps shouldBe 1000

            // The following probe should be sent at 10s into ALR.
            probeController.setAlrStartTimeMs(startTime.toEpochMilli())
            fixture.advanceTime(4.secs)
            probes = probeController.process(fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                500.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true

            probeController.setAlrStartTimeMs(startTime.toEpochMilli())
            fixture.advanceTime(1.secs)
            probes = probeController.process(fixture.currentTime())
            probes.size shouldBe 1
            probes = probeController.setEstimatedBitrate(
                500.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true
        }

        "PeriodicProbingAfterReset" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            val alrStartTime = fixture.currentTime()

            probeController.setAlrStartTimeMs(alrStartTime.toEpochMilli())
            probeController.enablePeriodicAlrProbing(true)
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probeController.reset(fixture.currentTime())

            fixture.advanceTime(10.secs)
            probes = probeController.process(fixture.currentTime())
            // Since bitrates are not yet set, no probe is sent event though we are in ALR
            // mode.
            probes.isEmpty() shouldBe true

            probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.size shouldBe 2

            // Make sure we use `kStartBitrateBps` as the estimated bitrate
            // until SetEstimatedBitrate is called with an updated estimate.
            fixture.advanceTime(10.secs)
            probes = probeController.process(fixture.currentTime())
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBe kStartBitrate * 2
        }

        "NoProbesWhenTransportIsNotWritable" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.enablePeriodicAlrProbing(true)

            val probes = probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = false))
            probes.isEmpty() shouldBe true
            probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
                .isEmpty() shouldBe true
            fixture.advanceTime(10.secs)
            probeController.process(fixture.currentTime()).isEmpty() shouldBe true

            // Controller is reset after a network route change.
            // But, a probe should not be sent since the transport is not writable.
            // Transport is not writable until after DTLS negotiation completes.
            // However, the bitrate constraints may change.
            probeController.reset(fixture.currentTime())
            probeController.setBitrates(
                2 * kMinBitrate,
                2 * kStartBitrate,
                2 * kMaxBitrate,
                fixture.currentTime()
            ).isEmpty() shouldBe true
            fixture.advanceTime(10.secs)
            probeController.process(fixture.currentTime()).isEmpty() shouldBe true
        }

        "TestExponentialProbingOverflow" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(
                kMinBitrate,
                10 * kMbpsMultiplier,
                100 * kMbpsMultiplier,
                fixture.currentTime()
            )
            // Verify that probe bitrate is capped at the specified max bitrate.
            probes = probeController.setEstimatedBitrate(
                60 * kMbpsMultiplier,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBe 100 * kMbpsMultiplier
            // Verify that repeated probes aren't sent.
            probes = probeController.setEstimatedBitrate(
                100 * kMbpsMultiplier,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true
        }

        "TestAllocatedBitrateCap" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true

            var probes = probeController.setBitrates(
                kMinBitrate,
                10 * kMbpsMultiplier,
                100 * kMbpsMultiplier,
                fixture.currentTime()
            )

            // Configure ALR for periodic probing.
            probeController.enablePeriodicAlrProbing(true)
            val alrStartTime = fixture.currentTime()
            probeController.setAlrStartTimeMs(alrStartTime.toEpochMilli())

            val estimatedBitrate = 10 * kMbpsMultiplier
            probes = probeController.setEstimatedBitrate(
                estimatedBitrate,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )

            // Set a max allocated bitrate below the current estimate.
            val maxAllocated = estimatedBitrate - 1 * kMbpsMultiplier
            probes = probeController.onMaxTotalAllocatedBitrate(maxAllocated, fixture.currentTime())
            probes.isEmpty() shouldBe true

            // Probes such as ALR capped at 2x the max allocation limit.
            fixture.advanceTime(5.secs)
            probes = probeController.process(fixture.currentTime())
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBe 2 * maxAllocated

            // Remove allocation limit.
            probeController.onMaxTotalAllocatedBitrate(Bandwidth.ZERO, fixture.currentTime())
                .isEmpty() shouldBe true
            fixture.advanceTime(5.secs)
            probes = probeController.process(fixture.currentTime())
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBe estimatedBitrate * 2
        }

        "ConfigurableProbingFieldTrial" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig(
                    firstExponentialProbeScale = 2.0,
                    secondExponentialProbeScale = 5.0,
                    furtherExponentialProbeScale = 3.0,
                    furtherProbeThreshold = 0.8,
                    firstAllocationProbeScale = 2.0,
                    allocationProbeLimitByCurrentScale = 1000.0,
                    secondAllocationProbeScale = null,
                    minProbePacketsSent = 2
                )
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, 5000.kbps, fixture.currentTime())
            probes.size shouldBe 2
            probes[0].targetDataRate.bps shouldBe 600
            probes[0].targetProbeCount shouldBe 2
            probes[1].targetDataRate.bps shouldBe 1500
            probes[1].targetProbeCount shouldBe 2

            // Repeated probe should only be sent when estimated bitrate climbs above
            // 0.8 * 5 * kStartBitrateBps = 1200.
            probes = probeController.setEstimatedBitrate(
                1100.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.size shouldBe 0

            probes = probeController.setEstimatedBitrate(
                1250.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.size shouldBe 1
            probes[0].targetDataRate.bps shouldBe 3 * 1250

            fixture.advanceTime(5.secs)
            probes = probeController.process(fixture.currentTime())

            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            probes = probeController.onMaxTotalAllocatedBitrate(200.kbps, fixture.currentTime())
            probes.size shouldBe 1
            probes[0].targetDataRate.bps shouldBe 400_000
        }

        "LimitAlrProbeWhenLossBasedBweLimited" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig()
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            probeController.enablePeriodicAlrProbing(true)
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes = probeController.setEstimatedBitrate(
                500.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            // Expect the controller to send a new probe after 5s has passed.
            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            fixture.advanceTime(5.secs)
            probes = probeController.process(fixture.currentTime())
            probes.size shouldBe 1

            probeController.setEstimatedBitrate(
                500.bps,
                BandwidthLimitedCause.kLossLimitedBweIncreasing,
                fixture.currentTime()
            )
            fixture.advanceTime(6.secs)
            probes = probeController.process(fixture.currentTime())
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBe 1.5 * 500.bps

            probes = probeController.setEstimatedBitrate(
                1.5 * 500.bps,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            fixture.advanceTime(6.secs)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe false
            probes[0].targetDataRate shouldBeGreaterThan 1.5 * 1.5 * 500.bps
        }

        /* Skipping tests involving network state:
         * "PeriodicProbeAtUpperNetworkStateEstimate",
         * "LimitProbeAtUpperNetworkStateEstimateIfLossBasedLimited",
         * "AlrProbesLimitedByNetworkStateEstimate",
         * "CanSetLongerProbeDurationAfterNetworkStateEstimate"
         */

        "ProbeInAlrIfLossBasedIncreasing" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig()
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probeController.enablePeriodicAlrProbing(true)
            probes = probeController.setEstimatedBitrate(
                kStartBitrate,
                BandwidthLimitedCause.kLossLimitedBweIncreasing,
                fixture.currentTime()
            )

            // Wait long enough to time out exponential probing.
            fixture.advanceTime(kExponentialProbingTimeout)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true

            // Probe when in alr.
            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            fixture.advanceTime(kAlrProbeInterval + 1.ms)
            probes = probeController.process(fixture.currentTime())
            probes.size shouldBe 1
            probes[0].targetDataRate shouldBe 1.5 * kStartBitrate
        }

        "NotProbeWhenInAlrIfLossBasedDecreases" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig()
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probeController.enablePeriodicAlrProbing(true)
            probes = probeController.setEstimatedBitrate(
                kStartBitrate,
                BandwidthLimitedCause.kLossLimitedBwe,
                fixture.currentTime()
            )

            // Wait long enough to time out exponential probing.
            fixture.advanceTime(kExponentialProbingTimeout)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true

            // Not probe in alr when loss based estimate decreases.
            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            fixture.advanceTime(kAlrProbeInterval + 1.ms)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true
        }

        "NotProbeIfLossBasedIncreasingOutsideAlr" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig()
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probeController.enablePeriodicAlrProbing(true)
            probes = probeController.setEstimatedBitrate(
                kStartBitrate,
                BandwidthLimitedCause.kLossLimitedBwe,
                fixture.currentTime()
            )

            // Wait long enough to time out exponential probing.
            fixture.advanceTime(kExponentialProbingTimeout)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true

            probeController.setAlrStartTimeMs(null)
            fixture.advanceTime(kAlrProbeInterval + 1.ms)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true
        }

        /* Skipping tests with network_state:
         * "ProbeFurtherWhenLossBasedIsSameAsDelayBasedEstimate",
         * "ProbeIfEstimateLowerThanNetworkStateEstimate",
         * "DontProbeFurtherWhenLossLimited",
         * "ProbeFurtherWhenDelayBasedLimited",
         * "ProbeAfterTimeoutIfNetworkStateEstimateIncreaseAfterProbeSent",
         * "SkipProbeFurtherIfAlreadyProbedToMaxRate"
         */

        "MaxAllocatedBitrateNotReset" {
            val fixture = ProbeControllerFixture()
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.isEmpty() shouldBe false

            probes = probeController.onMaxTotalAllocatedBitrate(kStartBitrate / 4, fixture.currentTime())
            probeController.reset(fixture.currentTime())

            probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.isEmpty() shouldBe false
            probes[0].targetDataRate shouldBe kStartBitrate / 4 * 2
        }

        "SkipAlrProbeIfEstimateLargerThanMaxProbe" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig(
                    skipIfEstimateLargerThanFractionOfMax = 0.9
                )
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            probeController.enablePeriodicAlrProbing(true)
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.isEmpty() shouldBe false

            probes = probeController.setEstimatedBitrate(
                kMaxBitrate,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe true

            // But if the max rate increase, A new probe is sent.
            probes = probeController.setBitrates(kMinBitrate, kStartBitrate, 2 * kMaxBitrate, fixture.currentTime())
            probes.isEmpty() shouldBe false
        }

        "SkipAlrProbeIfEstimateLargerThanFractionOfMaxAllocated" {
            val fixture = ProbeControllerFixture(
                config = ProbeControllerConfig(
                    skipIfEstimateLargerThanFractionOfMax = 1.0
                )
            )
            val probeController = fixture.createController()
            probeController.onNetworkAvailability(NetworkAvailability(networkAvailable = true)).isEmpty() shouldBe true
            probeController.enablePeriodicAlrProbing(true)
            var probes = probeController.setBitrates(kMinBitrate, kStartBitrate, kMaxBitrate, fixture.currentTime())
            probes.isEmpty() shouldBe false
            probes = probeController.setEstimatedBitrate(
                kMaxBitrate / 2,
                BandwidthLimitedCause.kDelayBasedLimited,
                fixture.currentTime()
            )

            fixture.advanceTime(10.secs)
            probeController.setAlrStartTimeMs(fixture.currentTime().toEpochMilli())
            probes = probeController.onMaxTotalAllocatedBitrate(kMaxBitrate / 2, fixture.currentTime())
            // No probes since total allocated is not higher than the current estimate.
            probes.isEmpty() shouldBe true
            fixture.advanceTime(2.secs)
            probes = probeController.process(fixture.currentTime())
            probes.isEmpty() shouldBe true

            // But if the max allocated increase, A new probe is sent.
            probes = probeController.onMaxTotalAllocatedBitrate(
                kMaxBitrate / 2 + 1.bps,
                fixture.currentTime()
            )
            probes.isEmpty() shouldBe false
        }

        /* Skipping tests with network_state:
         * "SkipNetworkStateProbeIfEstimateLargerThanMaxProbe",
         * "SendsProbeIfNetworkStateEstimateLowerThanMaxProbe",
         * "ProbeNotLimitedByNetworkStateEsimateIfLowerThantCurrent",
         * "DontProbeIfDelayIncreased",
         * "DontProbeIfHighRtt" */
    }

    companion object {
        val logger = createLogger()
        val diagnosticContext = DiagnosticContext()
    }
}
