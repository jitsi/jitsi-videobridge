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
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkAvailability
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControlUpdate
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControllerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkRouteChange
import org.jitsi.nlj.rtp.bandwidthestimation2.PacerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.PacketResult
import org.jitsi.nlj.rtp.bandwidthestimation2.ProcessInterval
import org.jitsi.nlj.rtp.bandwidthestimation2.RemoteBitrateReport
import org.jitsi.nlj.rtp.bandwidthestimation2.SentPacket
import org.jitsi.nlj.rtp.bandwidthestimation2.StreamsConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.TargetRateConstraints
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.rtp.scream.test.BuiltInNetworkBehaviorConfig
import org.jitsi.nlj.rtp.scream.test.CcFeedbackGenerator
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.dataSentAt
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.scaledBy
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.time.FakeClock
import java.time.Duration
import java.time.Instant

private const val kPacingFactor = 1.1

private fun createClock() = FakeClock().apply { setTime(Instant.ofEpochSecond(1234)) }

/** Unit tests for ScreamNetworkController,
 * based on WebRTC modules/congestion_controller/scream/scream_network_controller_unittest.cc in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 *
 * Field trial settings are passed as ScreamV2Parameters.
 */
class ScreamNetworkControllerTest : FreeSpec() {
    private val logger = createLogger()
    private val diagnosticContext = DiagnosticContext()

    private fun createConfig(
        clock: FakeClock,
        constraints: TargetRateConstraints = TargetRateConstraints(),
        streamBasedConfig: StreamsConfig = StreamsConfig()
    ) = NetworkControllerConfig(
        logger,
        diagnosticContext,
        constraints = constraints,
        streamBasedConfig = streamBasedConfig,
        clock = clock
    )

    private fun testClampsRefWindowOnFirstCongestionWhenStartingRateIsHigh(sendAsEct1: Boolean) {
        val clock = createClock()
        val constraints = TargetRateConstraints(startingRate = 300.kbps, minDataRate = 20.kbps, maxDataRate = 1000.kbps)
        val config = createConfig(
            clock,
            constraints = constraints,
            streamBasedConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
        )
        val screamController = ScreamNetworkController(config)
        screamController.onNetworkAvailability(NetworkAvailability(atTime = clock.instant(), networkAvailable = true))

        // RTT is 200ms (100ms one-way delay), link capacity is 30 kbps.
        val feedbackGenerator = CcFeedbackGenerator(
            CcFeedbackGenerator.Config(
                networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 100, linkCapacity = 30.kbps),
                sendAsEct1 = sendAsEct1,
                packetSize = 150.bytes
            )
        )

        val rtt = 200.ms
        val startTime = clock.instant()
        var targetRate = constraints.startingRate!!
        while (targetRate > 60.kbps && Duration.between(startTime, clock.instant()) < rtt.multipliedBy(10)) {
            val feedback = feedbackGenerator.processUntilNextFeedback(
                targetRate,
                clock,
                { packet -> screamController.onSentPacket(packet) }
            )
            val update = screamController.onTransportPacketsFeedback(feedback)
            update.targetRate?.let { targetRate = it.targetRate }
        }
        targetRate shouldBeLessThanOrEqualTo 60.kbps
        targetRate shouldBeGreaterThanOrEqualTo 20.kbps
        Duration.between(startTime, clock.instant()) shouldBeLessThanOrEqualTo rtt.multipliedBy(2)
    }

    private fun testRouteChangeWithoutBweRestart(initialQueueDelay: Duration, queueDelayAfterRouteChange: Duration) {
        val clock = createClock()
        val constraints = TargetRateConstraints(startingRate = 100.kbps, maxDataRate = 3000.kbps)
        val config = createConfig(clock, constraints = constraints)
        val screamController = ScreamNetworkController(config)
        screamController.onNetworkAvailability(NetworkAvailability(atTime = clock.instant(), networkAvailable = true))

        val initialConfig = CcFeedbackGenerator.Config(
            networkConfig = BuiltInNetworkBehaviorConfig(
                queueDelayMs = initialQueueDelay.toMillis().toInt(),
                linkCapacity = 2000.kbps
            )
        )
        val feedbackGenerator = CcFeedbackGenerator(initialConfig)

        var targetRate = 100.kbps
        for (i in 0 until 100) {
            val feedback = feedbackGenerator.processUntilNextFeedback(
                targetRate,
                clock,
                { packet -> screamController.onSentPacket(packet) }
            )
            val update = screamController.onTransportPacketsFeedback(feedback)
            update.targetRate?.let { targetRate = it.targetRate }
        }
        targetRate shouldBeGreaterThan 1500.kbps

        val routeChange = NetworkRouteChange(atTime = clock.instant(), constraints = constraints, restartBwe = false)
        screamController.onNetworkRouteChange(routeChange)

        val newConfig = CcFeedbackGenerator.Config(
            networkConfig = BuiltInNetworkBehaviorConfig(
                queueDelayMs = queueDelayAfterRouteChange.toMillis().toInt(),
                linkCapacity = 2000.kbps
            )
        )
        val newGenerator = CcFeedbackGenerator(newConfig)

        var targetRateUpdateCounter = 0
        for (i in 0 until 20) {
            val feedbackNewRtt = newGenerator.processUntilNextFeedback(
                targetRate,
                clock,
                { packet -> screamController.onSentPacket(packet) }
            )
            val updateNewRtt = screamController.onTransportPacketsFeedback(feedbackNewRtt)
            updateNewRtt.targetRate?.let {
                ++targetRateUpdateCounter
                it.targetRate shouldBeGreaterThan 500.kbps
            }
        }
        targetRateUpdateCounter shouldBeGreaterThanOrEqual 5
    }

    private class PaddingTestResult(
        val targetRate: Bandwidth,
        val paddingStart: Instant,
        val paddingStop: Instant
    )

    private fun processUntilPaddingStartAndStop(
        clock: FakeClock,
        screamController: ScreamNetworkController,
        feedbackGenerator: CcFeedbackGenerator,
        increaseSendRate: Boolean = true
    ): PaddingTestResult {
        var targetRate = Bandwidth.ZERO
        var paddingStart = Instant.EPOCH
        var paddingStop = Instant.EPOCH
        val startTime = clock.instant()
        var sendRate = 50.kbps
        while (clock.instant() < startTime + 10.secs) {
            val feedback = feedbackGenerator.processUntilNextFeedback(
                sendRate,
                clock,
                { packet -> screamController.onSentPacket(packet) }
            )
            val update = screamController.onTransportPacketsFeedback(feedback)
            update.pacerConfig?.let { pacerConfig ->
                if (pacerConfig.padRate() != Bandwidth.ZERO) {
                    if (paddingStart == Instant.EPOCH) {
                        paddingStart = clock.instant()
                    }
                    if (increaseSendRate) {
                        // Set the send rate equal to the padding rate.
                        sendRate = pacerConfig.padRate()
                    }
                } else if (paddingStart != Instant.EPOCH && paddingStop == Instant.EPOCH) {
                    paddingStop = clock.instant()
                }
            }
            update.targetRate?.let { targetRate = it.targetRate }
            if (paddingStop != Instant.EPOCH) {
                break
            }
        }
        (paddingStart == Instant.EPOCH) shouldBe false
        (paddingStop == Instant.EPOCH) shouldBe false
        return PaddingTestResult(targetRate, paddingStart, paddingStop)
    }

    init {
        "CanConstruct" {
            val clock = createClock()
            val config = createConfig(clock)
            ScreamNetworkController(config)
        }

        "OnNetworkAvailabilityUpdatesTargetRateAndPacerRate" {
            val clock = createClock()
            val config = createConfig(
                clock,
                constraints = TargetRateConstraints(startingRate = 123.kbps),
                streamBasedConfig = StreamsConfig(maxTotalAllocatedBitrate = 456.kbps)
            )
            val screamController = ScreamNetworkController(config)

            val update = screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )
            update.isNotEmpty() shouldBe true
            update.targetRate shouldNotBe null
            update.targetRate!!.targetRate shouldBe config.constraints.startingRate
            update.pacerConfig shouldNotBe null
            update.pacerConfig!!.dataWindow shouldBe
                dataSentAt(config.constraints.startingRate!!.scaledBy(kPacingFactor), PacerConfig.kDefaultTimeInterval)
        }

        "OnTransportPacketsFeedbackUpdatesTargetRateAndPacerRate" {
            val clock = createClock()
            val config = createConfig(clock)
            val screamController = ScreamNetworkController(config)

            // Simulation with infinite capacity.
            val feedbackGenerator = CcFeedbackGenerator(CcFeedbackGenerator.Config())

            val feedback = feedbackGenerator.processUntilNextFeedback(100.kbps, clock)
            val update = screamController.onTransportPacketsFeedback(feedback)
            update.isNotEmpty() shouldBe true
            update.targetRate shouldNotBe null
            update.targetRate!!.targetRate shouldBeGreaterThan 100.kbps
            update.pacerConfig shouldNotBe null
            update.pacerConfig!!.dataWindow shouldBe
                dataSentAt(update.targetRate!!.targetRate.scaledBy(kPacingFactor), PacerConfig.kDefaultTimeInterval)
        }

        "OnNetworkRouteChangeResetsScreamAndUpdatesTargetRate" {
            val clock = createClock()
            val config = createConfig(
                clock,
                constraints = TargetRateConstraints(startingRate = 50.kbps),
                streamBasedConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
            )
            val screamController = ScreamNetworkController(config)
            screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )

            val feedbackGenerator = CcFeedbackGenerator(CcFeedbackGenerator.Config())
            var sendRate = 100.kbps
            for (i in 0 until 10) {
                val feedback = feedbackGenerator.processUntilNextFeedback(sendRate, clock)
                val update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let { sendRate = it.targetRate }
            }
            sendRate shouldBeGreaterThan 50.kbps

            val routeChange = NetworkRouteChange(
                atTime = clock.instant(),
                constraints = TargetRateConstraints(startingRate = 123.kbps)
            )
            val update = screamController.onNetworkRouteChange(routeChange)
            update.isNotEmpty() shouldBe true
            update.targetRate shouldNotBe null
            update.targetRate!!.targetRate shouldBe routeChange.constraints.startingRate
            update.pacerConfig shouldNotBe null
            update.pacerConfig!!.dataWindow shouldBe
                dataSentAt(
                    routeChange.constraints.startingRate!!.scaledBy(kPacingFactor),
                    PacerConfig.kDefaultTimeInterval
                )
        }

        "OnNetworkRouteChangeWithRestartBweOnDegradedLink" - {
            listOf(false, true).forEach { safeReset ->
                "safe reset $safeReset" {
                    val clock = createClock()
                    val config = createConfig(
                        clock,
                        constraints = TargetRateConstraints(startingRate = 300.kbps),
                        streamBasedConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
                    )
                    val screamController = ScreamNetworkController(
                        config,
                        if (safeReset) ScreamV2Parameters() else ScreamV2Parameters(safeResetOnRouteChange = false)
                    )
                    screamController.onNetworkAvailability(
                        NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
                    )

                    // Low capacity link to cause target rate to decrease below starting rate.
                    val feedbackGenerator = CcFeedbackGenerator(
                        CcFeedbackGenerator.Config(
                            networkConfig = BuiltInNetworkBehaviorConfig(linkCapacity = 100.kbps)
                        )
                    )

                    var sendRate = 300.kbps
                    for (i in 0 until 20) {
                        val feedback = feedbackGenerator.processUntilNextFeedback(sendRate, clock)
                        val update = screamController.onTransportPacketsFeedback(feedback)
                        update.targetRate?.let { sendRate = it.targetRate }
                    }
                    sendRate shouldBeLessThan 300.kbps

                    // Route change with restart_bwe = true and default starting rate of 300 kbps.
                    val routeChange = NetworkRouteChange(
                        atTime = clock.instant(),
                        constraints = TargetRateConstraints(startingRate = 300.kbps),
                        restartBwe = true
                    )

                    val update = screamController.onNetworkRouteChange(routeChange)
                    update.isNotEmpty() shouldBe true
                    update.targetRate shouldNotBe null
                    if (safeReset) {
                        // Target rate must not jump back up to 300 kbps on route change.
                        update.targetRate!!.targetRate shouldBeLessThanOrEqualTo sendRate
                    } else {
                        // When safe reset is disabled, target rate resets back up to 300 kbps.
                        update.targetRate!!.targetRate shouldBe 300.kbps
                    }
                }
            }
        }

        "TargetRateRampsUptoTargetConstraints" {
            val clock = createClock()
            val config = createConfig(clock, constraints = TargetRateConstraints(maxDataRate = 300.kbps))
            val screamController = ScreamNetworkController(config)

            // Simulation with infinite capacity.
            val feedbackGenerator = CcFeedbackGenerator(CcFeedbackGenerator.Config())

            var targetRate = 100.kbps
            for (i in 0 until 10) {
                val feedback = feedbackGenerator.processUntilNextFeedback(targetRate, clock)
                val update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let { targetRate = it.targetRate }
            }
            targetRate shouldBe 300.kbps

            // Reduce the constraints and expect the next target rate is bound by it.
            val constraints = TargetRateConstraints(maxDataRate = 200.kbps)
            screamController.onTargetRateConstraints(constraints)
            val feedback = feedbackGenerator.processUntilNextFeedback(targetRate, clock)
            val update = screamController.onTransportPacketsFeedback(feedback)
            update.targetRate shouldNotBe null
            update.targetRate!!.targetRate shouldBe 200.kbps
        }

        "RouteChangeWithoutBweRestartHigherRttDoesNotResetStartingRate" {
            testRouteChangeWithoutBweRestart(initialQueueDelay = 10.ms, queueDelayAfterRouteChange = 100.ms)
        }

        "RouteChangeWithoutBweRestartLowerRttDoesNotResetStartingRate" {
            testRouteChangeWithoutBweRestart(initialQueueDelay = 50.ms, queueDelayAfterRouteChange = 10.ms)
        }

        "TargetRateLimitedByRemoteBitrateReport" {
            val clock = createClock()
            val config = createConfig(clock, constraints = TargetRateConstraints(maxDataRate = 1000.kbps))
            val screamController = ScreamNetworkController(config)

            // Simulation with infinite capacity.
            val feedbackGenerator = CcFeedbackGenerator(CcFeedbackGenerator.Config())
            var targetRate = 100.kbps
            for (i in 0 until 10) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    targetRate,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                val update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let { targetRate = it.targetRate }
            }
            targetRate shouldBe 1000.kbps

            val msg = RemoteBitrateReport(receiveTime = clock.instant(), bandwidth = 500.kbps)
            var update: NetworkControlUpdate = screamController.onRemoteBitrateReport(msg)

            update.targetRate shouldNotBe null
            update.targetRate!!.targetRate shouldBe 500.kbps

            for (i in 0 until 2) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    targetRate,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let { it.targetRate shouldBe 500.kbps }
            }
        }

        "PacingWindowReducedIfCeCongestedStreamsConfigured" {
            val clock = createClock()
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(linkCapacity = 900.kbps),
                    sendAsEct1 = true
                )
            )
            val config = createConfig(clock)
            val screamController = ScreamNetworkController(config)

            val streamsConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
            screamController.onStreamsConfig(streamsConfig)

            var update = NetworkControlUpdate()
            var sendRate = 500.kbps
            for (i in 0 until 20) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let { sendRate = it.targetRate }
            }
            update.pacerConfig shouldNotBe null
            update.pacerConfig!!.timeWindow shouldBe 2.ms
        }

        "PacingWindowReducedIfDelayCongestedStreamsConfigured" {
            val clock = createClock()
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(linkCapacity = 900.kbps),
                    // Scream will react to delay increase, not CE.
                    sendAsEct1 = false
                )
            )
            val config = createConfig(clock)
            val screamController = ScreamNetworkController(config)

            val streamsConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
            screamController.onStreamsConfig(streamsConfig)

            var update = NetworkControlUpdate()
            var sendRate = 500.kbps
            for (i in 0 until 30) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let { sendRate = it.targetRate }
            }
            update.pacerConfig shouldNotBe null
            update.pacerConfig!!.timeWindow shouldBe 2.ms
        }

        "PacingWindowNotReducedIfNotCongested" {
            val clock = createClock()
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(linkCapacity = 9000.kbps),
                    // Scream will react to delay increase, not CE.
                    sendAsEct1 = false
                )
            )
            val config = createConfig(clock)
            val screamController = ScreamNetworkController(config)

            val streamsConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
            screamController.onStreamsConfig(streamsConfig)

            var update = NetworkControlUpdate()
            var sendRate = 500.kbps
            for (i in 0 until 30) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let { sendRate = it.targetRate }
            }
            update.pacerConfig shouldNotBe null
            update.pacerConfig!!.timeWindow shouldBe PacerConfig.kDefaultTimeInterval
        }

        "InitiallyPaddingIsAllowedToReachNeededRate" {
            val clock = createClock()
            val config = createConfig(clock)
            val screamController = ScreamNetworkController(config)
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 10, linkCapacity = 5000.kbps),
                    sendAsEct1 = true
                )
            )

            val streamsConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
            screamController.onStreamsConfig(streamsConfig)

            var update = screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )

            var sendRate = 50.kbps
            var targetRate = Bandwidth.ZERO
            var paddingSet = false
            var paddingStop = Instant.EPOCH
            val startTime = clock.instant()
            while (clock.instant() < startTime + 1.secs) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    sendRate,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                update.pacerConfig?.let { pacerConfig ->
                    if (pacerConfig.padRate() != Bandwidth.ZERO) {
                        paddingSet = true
                        // Set the send rate equal to the padding rate.
                        sendRate = pacerConfig.padRate()
                        // Pacing rate is rounded.
                        pacerConfig.padRate() shouldBeGreaterThan update.targetRate!!.targetRate - 1.kbps
                        pacerConfig.padRate() shouldBeLessThan update.targetRate!!.targetRate + 1.kbps
                    } else if (paddingSet && paddingStop == Instant.EPOCH) {
                        paddingStop = clock.instant()
                    }
                }
                update.targetRate?.let { targetRate = it.targetRate }
            }
            paddingSet shouldBe true
            // Target rate should reach max needed rate.
            targetRate shouldBeGreaterThanOrEqualTo streamsConfig.maxTotalAllocatedBitrate!!
            // But not much more, since seen data in flight should limit the target rate
            // increase.
            targetRate shouldBeLessThanOrEqualTo streamsConfig.maxTotalAllocatedBitrate!!.scaledBy(1.5)
            // Padding should stop when target is reached.
            Duration.between(startTime, paddingStop) shouldBeLessThan 1.secs
        }

        "InitialProbingWithoutMaxTotalAllocatedBitrate" {
            val clock = createClock()
            // Do not set config.stream_based_config.max_total_allocated_bitrate
            val config = createConfig(
                clock,
                constraints = TargetRateConstraints(startingRate = 100.kbps, maxDataRate = 1000.kbps),
                streamBasedConfig = StreamsConfig(enableRepeatedInitialProbing = true)
            )
            val screamController = ScreamNetworkController(config)

            var update = screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )
            update.pacerConfig shouldNotBe null
            // Padding is allowed during the first 6 seconds even if
            // max_total_allocated_bitrate is zero.
            update.pacerConfig!!.padRate() shouldBe config.constraints.startingRate

            // Advance clock past the 6s initial BWE probe window.
            clock.elapse(7.secs)
            update = screamController.onProcessInterval(ProcessInterval(atTime = clock.instant()))

            // Since max_total_allocated_bitrate is not set, padding should stop after the
            // initial probe window.
            update.pacerConfig?.let { it.padRate() shouldBe Bandwidth.ZERO }
        }

        "PaddingStopIfNetworkCongested" {
            val clock = createClock()
            val config = createConfig(clock)
            val screamController = ScreamNetworkController(config)
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 10, linkCapacity = 500.kbps),
                    sendAsEct1 = true
                )
            )

            val streamsConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
            screamController.onStreamsConfig(streamsConfig)

            val result = processUntilPaddingStartAndStop(clock, screamController, feedbackGenerator)

            result.targetRate shouldBeLessThan 750.kbps
            // Padding should stop when congestion is detected.
            Duration.between(result.paddingStart, result.paddingStop) shouldBeLessThan 1.secs
        }

        "PeriodicallyAllowPadding" {
            val clock = createClock()
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 10, linkCapacity = 15000.kbps),
                    sendAsEct1 = true
                )
            )
            val config = createConfig(clock)
            val screamController = ScreamNetworkController(config)

            val streamsConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
            screamController.onStreamsConfig(streamsConfig)

            val result1 = processUntilPaddingStartAndStop(
                clock,
                screamController,
                feedbackGenerator,
                increaseSendRate = false
            )
            val result2 = processUntilPaddingStartAndStop(clock, screamController, feedbackGenerator)

            val paddingDuration = Duration.between(result1.paddingStart, result1.paddingStop)
            val timeBetweenPadding = Duration.between(result1.paddingStop, result2.paddingStart)
            paddingDuration shouldBeGreaterThan 2500.ms
            paddingDuration shouldBeLessThan 3300.ms
            timeBetweenPadding shouldBeGreaterThan 2500.ms
            timeBetweenPadding shouldBeLessThan 3300.ms
        }

        "DelayPaddingAfterCongestion" {
            val clock = createClock()
            val config = createConfig(clock)
            val screamController = ScreamNetworkController(config)
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 10, linkCapacity = 500.kbps),
                    sendAsEct1 = true
                )
            )

            val streamsConfig = StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps)
            screamController.onStreamsConfig(streamsConfig)

            // Padding should start, but then stop since pushing to max allocated causes
            // congestion.
            val result1 = processUntilPaddingStartAndStop(
                clock,
                screamController,
                feedbackGenerator,
                increaseSendRate = true
            )

            // Ensure it was stopped quickly when congestion kicked in.
            Duration.between(result1.paddingStart, result1.paddingStop) shouldBeLessThan 1.secs

            // Network recovers because increase_send_rate=false keeps sending rate at
            // 50kbps. Wait until next padding starts.
            val result2 = processUntilPaddingStartAndStop(
                clock,
                screamController,
                feedbackGenerator,
                increaseSendRate = false
            )

            val timeUntilPadding = Duration.between(result1.paddingStop, result2.paddingStart)
            timeUntilPadding shouldBeGreaterThan 2500.ms
            timeUntilPadding shouldBeLessThan 3500.ms
        }

        "PadsToMinOf2xCurrentMaxAndEverSeenMax" {
            val clock = createClock()
            val config = createConfig(clock)
            val screamController = ScreamNetworkController(config)
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 50, linkCapacity = 5000.kbps),
                    sendAsEct1 = true
                )
            )

            screamController.onStreamsConfig(StreamsConfig(maxTotalAllocatedBitrate = 1000.kbps))

            // Even if max_total_allocated_bitrate is lowered, padding is still allowed up
            // to 2x the new max and previous max.
            screamController.onStreamsConfig(StreamsConfig(maxTotalAllocatedBitrate = 300.kbps))

            val result1 = processUntilPaddingStartAndStop(clock, screamController, feedbackGenerator)
            result1.targetRate shouldBeLessThan 700.kbps

            screamController.onStreamsConfig(StreamsConfig(maxTotalAllocatedBitrate = 800.kbps))
            val result2 = processUntilPaddingStartAndStop(clock, screamController, feedbackGenerator)
            result2.targetRate shouldBeLessThan 1100.kbps
        }

        "CanSetStartBitrate" {
            val clock = createClock()
            val config = createConfig(clock, constraints = TargetRateConstraints(startingRate = 3000.kbps))
            val screamController = ScreamNetworkController(config)
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 50, linkCapacity = 5000.kbps)
                )
            )

            val feedback = feedbackGenerator.processUntilNextFeedback(
                sendRate = 100.kbps,
                clock,
                { packet -> screamController.onSentPacket(packet) }
            )
            val update = screamController.onTransportPacketsFeedback(feedback)

            update.targetRate!!.targetRate shouldBeGreaterThanOrEqualTo 2980.kbps
            update.targetRate!!.targetRate shouldBeLessThan 3300.kbps
        }

        "IgnoreFeedbackWithoutReceivedPackets" {
            val clock = createClock()
            val config = createConfig(clock, constraints = TargetRateConstraints(startingRate = 300.kbps))
            val screamController = ScreamNetworkController(config)

            val msg = TransportPacketsFeedback()
            msg.feedbackTime = clock.instant()
            val result = PacketResult()
            result.sentPacket.sendTime = clock.instant()
            result.sentPacket.sequenceNumber = 1
            result.sentPacket.size = 1000.bytes
            result.isReceived() shouldBe false
            msg.packetFeedbacks.add(result)

            val update = screamController.onTransportPacketsFeedback(msg)
            // Scream should not change the target rate if there are no received packets.
            // Since this is the first feedback, the target rate should be the starting
            // rate.
            update.targetRate!!.targetRate shouldBe 300.kbps
        }

        "ReportsIsBandwidthLimited" {
            val clock = createClock()
            val config = createConfig(
                clock,
                constraints = TargetRateConstraints(startingRate = 100.kbps, maxDataRate = 5000.kbps)
            )
            val screamController = ScreamNetworkController(config)

            // Initially, before any packets are sent, is_bandwidth_limited is false
            // because the sender is application-limited (in ALR).
            var update: NetworkControlUpdate = screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )
            update.targetRate shouldNotBe null
            update.targetRate!!.isBandwidthLimited shouldBe false

            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 10, linkCapacity = 2000.kbps)
                )
            )

            var targetRate = 100.kbps
            // Send at full BWE rate so we exit ALR and become bandwidth-limited.
            var exitedAlr = false
            for (i in 0 until 20) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    targetRate,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let {
                    targetRate = it.targetRate
                    if (it.isBandwidthLimited) {
                        exitedAlr = true
                    }
                }
            }
            exitedAlr shouldBe true

            // Now send at a very low rate (e.g. 50kbps) while BWE remains high.
            // This should trigger application limited state.
            var alrDetected = false
            for (i in 0 until 50) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    50.kbps,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                if (update.targetRate != null && !update.targetRate!!.isBandwidthLimited) {
                    alrDetected = true
                    break
                }
            }
            alrDetected shouldBe true
        }

        "ReportsIsBandwidthLimitedEvenIfTargetRateClamped" {
            val clock = createClock()
            val config = createConfig(
                clock,
                constraints = TargetRateConstraints(
                    startingRate = 500.kbps,
                    minDataRate = 500.kbps,
                    maxDataRate = 500.kbps
                )
            )
            val screamController = ScreamNetworkController(config)

            var update: NetworkControlUpdate = screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )
            update.targetRate shouldNotBe null
            update.targetRate!!.isBandwidthLimited shouldBe false
            update.targetRate!!.targetRate shouldBe 500.kbps

            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 150, linkCapacity = 2000.kbps)
                )
            )

            // Warm up to build up data in flight so that we are not application limited.
            for (i in 0 until 5) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    500.kbps,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                screamController.onTransportPacketsFeedback(feedback)
            }

            // Now we should be actively sending near capacity (is_bandwidth_limited =
            // true).
            for (i in 0 until 15) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    500.kbps,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let {
                    it.isBandwidthLimited shouldBe true
                    it.targetRate shouldBe 500.kbps
                }
            }

            // Now send at a very low rate (50kbps).
            // The target rate is clamped at 500kbps so it cannot change.
            // We expect an update.target_rate to be generated anyway because
            // is_bandwidth_limited changes to false.
            var alrDetected = false
            for (i in 0 until 50) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    50.kbps,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                val target = update.targetRate
                if (target != null) {
                    target.targetRate shouldBe 500.kbps
                    if (!target.isBandwidthLimited) {
                        alrDetected = true
                        break
                    }
                }
            }
            alrDetected shouldBe true
        }

        "AlrRecoversDuringPeriodicPadding" {
            val clock = FakeClock().apply { setTime(Instant.EPOCH) }
            // Set periodic padding and padding duration to 1s so it aligns exactly with
            // warmup and activates repeatedly in the final loop.
            val feedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 50, linkCapacity = 2000.kbps)
                )
            )
            val controllerConfig = createConfig(clock)
            val screamController = ScreamNetworkController(controllerConfig)

            val streamsConfig = StreamsConfig(maxTotalAllocatedBitrate = 10_000.kbps)
            screamController.onStreamsConfig(streamsConfig)

            val rateConstraints = TargetRateConstraints(maxDataRate = 10_000.kbps, startingRate = 300.kbps)
            screamController.onTargetRateConstraints(rateConstraints)

            var update: NetworkControlUpdate = screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )

            var targetRate = 300.kbps

            // Warmup loop: allow BWE to adapt up to the 2 Mbps link capacity.
            val warmupStart = clock.instant()
            while (Duration.between(warmupStart, clock.instant()) < 10.secs) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    targetRate,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let { targetRate = it.targetRate }
            }
            targetRate shouldBeGreaterThanOrEqualTo 1800.kbps
            targetRate shouldBeLessThanOrEqualTo 2100.kbps

            // ALR entry: ensure we enter ALR by sending below capacity.
            var alrDetected = false
            val alrStart = clock.instant()
            while (Duration.between(alrStart, clock.instant()) < 1.secs) {
                val feedback = feedbackGenerator.processUntilNextFeedback(
                    100.kbps,
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                val target = update.targetRate
                if (target != null) {
                    targetRate = target.targetRate
                    if (!target.isBandwidthLimited) {
                        alrDetected = true
                        break
                    }
                }
            }
            alrDetected shouldBe true

            // Recovery loop: create a new channel with higher capacity (10 Mbps) and
            // lower delay (5ms). Even though the application only sends at `target_rate`
            // (which was frozen by ALR), active periodic padding automatically unblocks
            // ALR, allowing BWE to adapt upwards close to the new 10 Mbps link capacity.
            val highCapFeedbackGenerator = CcFeedbackGenerator(
                CcFeedbackGenerator.Config(
                    networkConfig = BuiltInNetworkBehaviorConfig(queueDelayMs = 5, linkCapacity = 10_000.kbps)
                )
            )

            var alrRecovered = false
            val recoveryStart = clock.instant()
            var paddingRate = Bandwidth.ZERO
            while (Duration.between(recoveryStart, clock.instant()) < 5.secs) {
                val feedback = highCapFeedbackGenerator.processUntilNextFeedback(
                    max(paddingRate, 100.kbps),
                    clock,
                    { packet -> screamController.onSentPacket(packet) }
                )
                update = screamController.onTransportPacketsFeedback(feedback)
                update.targetRate?.let {
                    targetRate = it.targetRate
                    if (it.isBandwidthLimited) {
                        alrRecovered = true
                    }
                }
                update.pacerConfig?.let { paddingRate = it.padRate() }
            }
            alrRecovered shouldBe true
            targetRate shouldBeGreaterThanOrEqualTo 9000.kbps
        }

        "CwndReduceRatioSetWhenPacerQueueGrowsAndShrinks" {
            val clock = createClock()
            val config = createConfig(clock, constraints = TargetRateConstraints(startingRate = 1000.kbps))
            val screamController = ScreamNetworkController(config)
            screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )

            // Starting rate is 1000 kbps, so pacing rate = 1.1 * 1000 kbps = 1100 kbps
            // (137,500 bytes/sec). Pacer queue of 41250 bytes gives (41250/137500) =
            // 300ms pacing delay. Expected ratio: (300ms - 100ms) / (500ms - 100ms) = 200
            // / 400 = 0.5.
            val msg1 = ProcessInterval(atTime = clock.instant(), pacerQueue = 41250.bytes)
            val update1 = screamController.onProcessInterval(msg1)
            update1.targetRate shouldNotBe null
            update1.targetRate!!.cwndReduceRatio shouldBe (0.5 plusOrMinus 0.05)

            // Send ProcessInterval with empty pacer queue -> cwnd_reduce_ratio drops back
            // to 0.0.
            clock.elapse(100.ms)
            val msg2 = ProcessInterval(atTime = clock.instant(), pacerQueue = DataSize.ZERO)
            val update2 = screamController.onProcessInterval(msg2)
            update2.targetRate shouldNotBe null
            update2.targetRate!!.cwndReduceRatio shouldBe 0.0
        }

        "TargetRateSetToZeroWhenCongestedAndQueueExceedsMaxPacingDelay" {
            val clock = createClock()
            val config = createConfig(clock, constraints = TargetRateConstraints(startingRate = 1000.kbps))
            val screamController = ScreamNetworkController(config)
            screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )

            // Congestion: data_in_flight (12000 bytes) exceeds max_data_in_flight (~10000
            // bytes).
            val sentPacket = SentPacket(sendTime = clock.instant(), dataInFlight = 12000.bytes)
            screamController.onSentPacket(sentPacket)

            // Starting rate is 1000 kbps, so pacing rate = 1.1 * 1000 kbps = 1100 kbps
            // (137,500 bytes/sec). Max pacing delay is 500 ms (68,750 bytes). 600 ms
            // pacing delay = 0.6 * 137,500 = 82,500 bytes.
            val msg = ProcessInterval(atTime = clock.instant(), pacerQueue = 82500.bytes)
            val update = screamController.onProcessInterval(msg)
            update.targetRate shouldNotBe null
            update.targetRate!!.targetRate shouldBe Bandwidth.ZERO

            // Congestion clears (data_in_flight drops to 1000 bytes), but pacer queue is
            // still at 300 ms (41,250 bytes > min_pacing_delay of 100 ms).
            // Due to hysteresis, target rate remains zero.
            val clearCongestion = SentPacket(sendTime = clock.instant(), dataInFlight = 1000.bytes)
            screamController.onSentPacket(clearCongestion)

            val midQueueMsg = ProcessInterval(atTime = clock.instant(), pacerQueue = 41250.bytes)
            val updateMid = screamController.onProcessInterval(midQueueMsg)
            updateMid.targetRate?.let { it.targetRate shouldBe Bandwidth.ZERO }

            // Once queue drains below min pacing delay (< 100 ms / 13,750 bytes),
            // target rate is restored.
            clock.elapse(100.ms)
            val drainMsg = ProcessInterval(atTime = clock.instant(), pacerQueue = 5000.bytes)
            val update2 = screamController.onProcessInterval(drainMsg)
            update2.targetRate shouldNotBe null
            update2.targetRate!!.targetRate shouldBeGreaterThan Bandwidth.ZERO
        }

        "TargetRateNotZeroWhenQueueExceedsMaxPacingDelayButNotCongested" {
            val clock = createClock()
            val config = createConfig(clock, constraints = TargetRateConstraints(startingRate = 1000.kbps))
            val screamController = ScreamNetworkController(config)
            screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )

            // Not congested: data_in_flight (1000 bytes) < max_data_in_flight.
            val sentPacket = SentPacket(sendTime = clock.instant(), dataInFlight = 1000.bytes)
            screamController.onSentPacket(sentPacket)

            // 600 ms pacing delay = 82,500 bytes.
            val msg = ProcessInterval(atTime = clock.instant(), pacerQueue = 82500.bytes)
            val update = screamController.onProcessInterval(msg)
            update.targetRate shouldNotBe null
            update.targetRate!!.targetRate shouldBeGreaterThan Bandwidth.ZERO
            update.targetRate!!.cwndReduceRatio shouldBe 1.0
        }

        "TargetRateNotZeroWhenCongestedButQueueBelowMaxPacingDelay" {
            val clock = createClock()
            val config = createConfig(clock, constraints = TargetRateConstraints(startingRate = 1000.kbps))
            val screamController = ScreamNetworkController(config)
            screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )

            // Congested: data_in_flight exceeds max_data_in_flight.
            val sentPacket = SentPacket(sendTime = clock.instant(), dataInFlight = 12000.bytes)
            screamController.onSentPacket(sentPacket)

            // 50 ms pacing delay = 0.05 * 137,500 = 6,875 bytes (< 500 ms).
            val msg = ProcessInterval(atTime = clock.instant(), pacerQueue = 6875.bytes)
            val update = screamController.onProcessInterval(msg)
            // Target rate is still > 0 (only cwnd_reduce_ratio is set).
            update.targetRate?.let { it.targetRate shouldBeGreaterThan Bandwidth.ZERO }
        }

        "PacingRateClampedToMinTotalAllocatedBitrate" {
            val clock = createClock()
            val config = createConfig(
                clock,
                constraints = TargetRateConstraints(startingRate = 100.kbps),
                streamBasedConfig = StreamsConfig(
                    minTotalAllocatedBitrate = 200.kbps,
                    maxTotalAllocatedBitrate = 500.kbps
                )
            )
            val screamController = ScreamNetworkController(config)

            val update = screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )
            update.pacerConfig shouldNotBe null

            // Pacing rate should be at least min_total_allocated_bitrate * kPacingFactor
            // = 200 * 1.1 = 220 kbps.
            update.pacerConfig!!.dataWindow shouldBe
                dataSentAt(200.kbps.scaledBy(kPacingFactor), PacerConfig.kDefaultTimeInterval)
        }

        "PacingRateUpdatesOnStreamsConfigChange" {
            val clock = createClock()
            val config = createConfig(
                clock,
                constraints = TargetRateConstraints(startingRate = 100.kbps),
                streamBasedConfig = StreamsConfig(maxTotalAllocatedBitrate = 500.kbps)
            )
            val screamController = ScreamNetworkController(config)

            val update = screamController.onNetworkAvailability(
                NetworkAvailability(atTime = clock.instant(), networkAvailable = true)
            )
            update.pacerConfig shouldNotBe null
            update.pacerConfig!!.dataWindow shouldBe
                dataSentAt(100.kbps.scaledBy(kPacingFactor), PacerConfig.kDefaultTimeInterval)

            val streamsConfig = StreamsConfig(
                atTime = clock.instant(),
                minTotalAllocatedBitrate = 300.kbps,
                maxTotalAllocatedBitrate = 500.kbps
            )
            val streamUpdate = screamController.onStreamsConfig(streamsConfig)
            streamUpdate.pacerConfig shouldNotBe null
            streamUpdate.pacerConfig!!.dataWindow shouldBe
                dataSentAt(300.kbps.scaledBy(kPacingFactor), PacerConfig.kDefaultTimeInterval)
        }

        "ClampsRefWindowOnFirstDelayCongestionWhenStartingRateIsHigh" {
            testClampsRefWindowOnFirstCongestionWhenStartingRateIsHigh(sendAsEct1 = false)
        }

        "ClampsRefWindowOnFirstCeCongestionWhenStartingRateIsHigh" {
            testClampsRefWindowOnFirstCongestionWhenStartingRateIsHigh(sendAsEct1 = true)
        }
    }
}
