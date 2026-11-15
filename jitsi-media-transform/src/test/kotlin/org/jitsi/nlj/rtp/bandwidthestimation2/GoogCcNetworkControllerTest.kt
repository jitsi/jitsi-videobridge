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
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.times
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Instant

/** Tests of GoogCcNetworkController,
 * based on WebRTC modules/congestion_controller/goog_cc/goog_cc_network_control_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Only tests that don't require the full network simulation environment are included.
 */

private const val kInitialBitrateKbps = 60
private val kInitialBitrate = kInitialBitrateKbps.kbps
private const val kDefaultPacingRate = 2.5

// This is a float in WebRTC, so it is not exactly 1.1.
private val kDefaultPacingRateSendSideBwe = 1.1f.toDouble()

private fun createRouteChange(
    time: Instant,
    startRate: Bandwidth? = null,
    minRate: Bandwidth? = null,
    maxRate: Bandwidth? = null
): NetworkRouteChange = NetworkRouteChange(
    atTime = time,
    constraints = TargetRateConstraints(minDataRate = minRate, maxDataRate = maxRate, startingRate = startRate)
)

private fun createPacketResult(
    arrivalTime: Instant,
    sendTime: Instant,
    payloadSize: Long,
    pacingInfo: PacedPacketInfo
): PacketResult {
    val packetResult = PacketResult()
    packetResult.sentPacket = SentPacket()
    packetResult.sentPacket.sendTime = sendTime
    packetResult.sentPacket.size = payloadSize.bytes
    packetResult.sentPacket.pacingInfo = pacingInfo
    packetResult.receiveTime = arrivalTime
    return packetResult
}

class Ref<T>(
    var v: T
)

// Simulate sending packets and receiving transport feedback during
// `runtime_ms`, then return the final target birate.
fun packetTransmissionAndFeedbackBlock(
    controller: NetworkControllerInterface,
    runtimeMs: Long,
    delay: Long,
    currentTimeRef: Ref<Instant>
): Bandwidth? {
    var currentTime = currentTimeRef.v
    var update: NetworkControlUpdate
    var targetBitrate: Bandwidth? = null
    var delayBuildup = 0L
    val startTimeMs = currentTime.toEpochMilli()
    while (currentTime.toEpochMilli() - startTimeMs < runtimeMs) {
        val kPayloadSize = 1000L
        val packet = createPacketResult(currentTime + delayBuildup.ms, currentTime, kPayloadSize, PacedPacketInfo())
        delayBuildup += delay
        update = controller.onSentPacket(packet.sentPacket)
        if (update.targetRate != null) {
            targetBitrate = update.targetRate!!.targetRate
        }
        val feedback = TransportPacketsFeedback()
        feedback.feedbackTime = packet.receiveTime
        feedback.packetFeedbacks.add(packet)
        update = controller.onTransportPacketsFeedback(feedback)
        if (update.targetRate != null) {
            targetBitrate = update.targetRate!!.targetRate
        }
        currentTime += 50.ms
        update = controller.onProcessInterval(ProcessInterval(atTime = currentTime))
        if (update.targetRate != null) {
            targetBitrate = update.targetRate!!.targetRate
        }
    }
    currentTimeRef.v = currentTime
    return targetBitrate
}

// Scenarios:

class NetworkControllerTextFixture(
    googccConfig: GoogCcFactoryConfig = GoogCcFactoryConfig()
) {
    private val logger = createLogger()
    private val diagnosticContext = DiagnosticContext()

    private val factory = GoogCcNetworkControllerFactory(googccConfig)

    fun createController(): NetworkControllerInterface {
        val config = initialConfig()
        val controller = factory.create(config)
        return controller
    }

    private fun initialConfig(
        startingBandwidthKbps: Int = kInitialBitrateKbps,
        minDataRateKbps: Int = 0,
        maxDataRateKbps: Int = 5 * kInitialBitrateKbps
    ): NetworkControllerConfig {
        val config = NetworkControllerConfig(
            logger,
            diagnosticContext,
            constraints = TargetRateConstraints(
                atTime = Instant.ofEpochSecond(0),
                minDataRate = minDataRateKbps.kbps,
                maxDataRate = maxDataRateKbps.kbps,
                startingRate = startingBandwidthKbps.kbps
            )
        )
        return config
    }
}

class GoogCcNetworkControllerTest : FreeSpec() {
    init {
        "InitializeTargetRateOnFirstProcessInterval" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()

            var update =
                controller.onNetworkAvailability(
                    NetworkAvailability(
                        atTime = Instant.ofEpochMilli(123456),
                        networkAvailable = true
                    )
                )
            update = controller.onProcessInterval(ProcessInterval(atTime = Instant.ofEpochMilli(123456)))

            update.targetRate!!.targetRate shouldBe kInitialBitrate
            update.pacerConfig!!.dataRate() shouldBe kInitialBitrate * kDefaultPacingRate
            update.probeClusterConfigs[0].targetDataRate shouldBe kInitialBitrate * 3
            update.probeClusterConfigs[1].targetDataRate shouldBe kInitialBitrate * 5
        }

        "ChangeDefaultPacingFactorAfterFirstFeedback" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()

            var update =
                controller.onNetworkAvailability(
                    NetworkAvailability(
                        atTime = Instant.ofEpochMilli(123456),
                        networkAvailable = true
                    )
                )
            update = controller.onProcessInterval(ProcessInterval(atTime = Instant.ofEpochMilli(123456)))
            update.pacerConfig!!.dataRate() shouldBe kInitialBitrate * kDefaultPacingRate

            val feedback = TransportPacketsFeedback()
            feedback.feedbackTime = Instant.ofEpochMilli(123457)
            feedback.packetFeedbacks.add(
                createPacketResult(feedback.feedbackTime, feedback.feedbackTime, 100, PacedPacketInfo())
            )
            update = controller.onTransportPacketsFeedback(feedback)
            update.pacerConfig shouldNotBe null
            update.pacerConfig!!.dataRate() shouldBeGreaterThanOrEqualTo
                kInitialBitrate * kDefaultPacingRateSendSideBwe
            update.pacerConfig!!.dataRate() shouldBeLessThan
                (kInitialBitrate + 5.kbps) * kDefaultPacingRateSendSideBwe
        }

        "RespectsConfiguredPacingFactor" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()

            var update =
                controller.onNetworkAvailability(
                    NetworkAvailability(
                        atTime = Instant.ofEpochMilli(123456),
                        networkAvailable = true
                    )
                )
            update = controller.onProcessInterval(ProcessInterval(atTime = Instant.ofEpochMilli(123456)))
            update.pacerConfig!!.dataRate() shouldBe kInitialBitrate * kDefaultPacingRate

            var lastPacerConfig: PacerConfig? = null
            val streamsConfig = StreamsConfig(pacingFactor = 10.0)

            update = controller.onStreamsConfig(streamsConfig)
            if (update.pacerConfig != null) {
                lastPacerConfig = update.pacerConfig
            }

            val feedback = TransportPacketsFeedback()
            feedback.feedbackTime = Instant.ofEpochMilli(123457)
            feedback.packetFeedbacks.add(
                createPacketResult(feedback.feedbackTime, feedback.feedbackTime, 100, PacedPacketInfo())
            )
            update = controller.onTransportPacketsFeedback(feedback)
            if (update.pacerConfig != null) {
                lastPacerConfig = update.pacerConfig
            }

            lastPacerConfig shouldNotBe null
            lastPacerConfig!!.dataRate() shouldBeGreaterThanOrEqualTo kInitialBitrate * 10
            lastPacerConfig.dataRate() shouldBeLessThan (kInitialBitrate + 5.kbps) * 10
        }

        "ReactsToChangedNetworkConditionsFromRemote" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()
            var currentTime = Instant.ofEpochMilli(123)
            var update =
                controller.onNetworkAvailability(NetworkAvailability(atTime = currentTime, networkAvailable = true))
            update = controller.onProcessInterval(ProcessInterval(atTime = currentTime))
            update = controller.onRemoteBitrateReport(
                RemoteBitrateReport(receiveTime = currentTime, bandwidth = kInitialBitrate * 2)
            )

            currentTime += 25.ms
            update = controller.onProcessInterval(ProcessInterval(atTime = currentTime))
            update.targetRate!!.targetRate shouldBe kInitialBitrate * 2
            update.pacerConfig!!.dataRate() shouldBe kInitialBitrate * 2 * kDefaultPacingRate

            update = controller.onRemoteBitrateReport(
                RemoteBitrateReport(receiveTime = currentTime, bandwidth = kInitialBitrate)
            )
            currentTime += 25.ms
            update = controller.onProcessInterval(ProcessInterval(atTime = currentTime))
            update.targetRate!!.targetRate shouldBe kInitialBitrate
            update.pacerConfig!!.dataRate() shouldBe kInitialBitrate * kDefaultPacingRate
        }

        "OnNetworkRouteChanged" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()
            val currentTime = Instant.ofEpochMilli(123)
            var update =
                controller.onNetworkAvailability(NetworkAvailability(atTime = currentTime, networkAvailable = true))
            val newBitrate = 200000.bps

            update = controller.onNetworkRouteChange(createRouteChange(currentTime, newBitrate))
            update.targetRate!!.targetRate shouldBe newBitrate
            update.pacerConfig!!.dataRate() shouldBe newBitrate * kDefaultPacingRate
            update.probeClusterConfigs.size shouldBe 2

            // If the bitrate is reset to -1, the new starting bitrate will be
            // the minimum default bitrate.
            val kDefaultMinBitrate = 5.kbps
            update = controller.onNetworkRouteChange(createRouteChange(currentTime))
            update.targetRate!!.targetRate shouldBe kDefaultMinBitrate
            // WebRTC's DataSize is a whole number of bytes: 5 kbps * 2.5 * 40 ms is 62.5 bytes, which rounds to 63.
            update.pacerConfig!!.dataWindow shouldBe 63.bytes
            update.probeClusterConfigs.size shouldBe 2
        }

        "ProbeOnRouteChange" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()
            var currentTime = Instant.ofEpochMilli(123)
            var update =
                controller.onNetworkAvailability(NetworkAvailability(atTime = currentTime, networkAvailable = true))
            currentTime += 1.secs

            update = controller.onNetworkRouteChange(
                createRouteChange(currentTime, 2 * kInitialBitrate, Bandwidth.ZERO, 20 * kInitialBitrate)
            )

            update.pacerConfig shouldNotBe null
            update.targetRate!!.targetRate shouldBe kInitialBitrate * 2
            update.probeClusterConfigs.size shouldBe 2
            update.probeClusterConfigs[0].targetDataRate shouldBe kInitialBitrate * 6
            update.probeClusterConfigs[1].targetDataRate shouldBe kInitialBitrate * 12

            update = controller.onProcessInterval(ProcessInterval(atTime = currentTime))
        }

        "ProbeAfterRouteChangeWhenTransportWritable" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()
            val currentTime = Instant.ofEpochMilli(123)

            var update = controller.onNetworkAvailability(
                NetworkAvailability(atTime = currentTime, networkAvailable = false)
            )
            update.probeClusterConfigs.isEmpty() shouldBe true

            update = controller.onNetworkRouteChange(
                createRouteChange(
                    currentTime,
                    2 * kInitialBitrate,
                    Bandwidth.ZERO,
                    20 * kInitialBitrate
                )
            )
            // Transport is not writable. So not point in sending a probe.
            update.probeClusterConfigs.isEmpty() shouldBe true

            // Probe is sent when transport becomes writable.
            update = controller.onNetworkAvailability(
                NetworkAvailability(atTime = currentTime, networkAvailable = true)
            )
            update.probeClusterConfigs.isNotEmpty() shouldBe true
        }

        // Bandwidth estimation is updated when feedbacks are received.
        // Feedbacks which show an increasing delay cause the estimation to be reduced.
        "UpdatesDelayBasedEstimate" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()
            val kRunTimeMs = 6000L
            val currentTime = Ref(Instant.ofEpochMilli(123))
            var update =
                controller.onNetworkAvailability(NetworkAvailability(atTime = currentTime.v, networkAvailable = true))

            // The test must run and insert packets/feedback long enough that the
            // BWE computes a valid estimate. This is first done in an environment which
            // simulates no bandwidth limitation, and therefore not built-up delay.
            val targetBitrateBeforeDelay =
                packetTransmissionAndFeedbackBlock(controller, kRunTimeMs, 0, currentTime)
            targetBitrateBeforeDelay shouldNotBe null

            // Repeat, but this time with a building delay, and make sure that the
            // estimation is adjusted downwards.
            val targetBitrateAfterDelay =
                packetTransmissionAndFeedbackBlock(controller, kRunTimeMs, 50, currentTime)
            targetBitrateAfterDelay!! shouldBeLessThan targetBitrateBeforeDelay!!
        }

        "TriggersTargetRateUpdateWhenBandwidthLimitedChanges" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()
            var currentTime = Instant.ofEpochMilli(123456)

            var update =
                controller.onNetworkAvailability(NetworkAvailability(atTime = currentTime, networkAvailable = true))
            update = controller.onProcessInterval(ProcessInterval(atTime = currentTime))
            update.targetRate shouldNotBe null
            update.targetRate!!.isBandwidthLimited shouldBe true

            // Send packet to initialize ALR detector
            val sentPacket = SentPacket()
            sentPacket.sendTime = currentTime
            sentPacket.size = 1000.bytes
            update = controller.onSentPacket(sentPacket)

            // Wait 10 seconds without sending any packets (this will make budget ratio
            // accumulate and enter ALR)
            currentTime += 10.secs
            // Send a tiny packet to trigger AlrDetector state change evaluation
            sentPacket.sendTime = currentTime
            sentPacket.size = 1.bytes
            update = controller.onSentPacket(sentPacket)

            var alrDetected = false
            if (update.targetRate != null && !update.targetRate!!.isBandwidthLimited) {
                alrDetected = true
            } else {
                // Process interval should trigger target rate update if not already
                // triggered by OnSentPacket
                update = controller.onProcessInterval(ProcessInterval(atTime = currentTime))
                if (update.targetRate != null && !update.targetRate!!.isBandwidthLimited) {
                    alrDetected = true
                }
            }
            alrDetected shouldBe true

            // Now send a large burst of packets to leave ALR.
            // Target rate is kInitialBitrate (60kbps). Budget ratio needs to drop below
            // 0.5. We send 50 packets of 1000 bytes.
            var bandwidthLimitedDetected = false
            for (i in 0 until 50) {
                currentTime += 1.ms
                sentPacket.sendTime = currentTime
                sentPacket.size = 1000.bytes
                update = controller.onSentPacket(sentPacket)
                if (update.targetRate != null && update.targetRate!!.isBandwidthLimited) {
                    bandwidthLimitedDetected = true
                }
            }

            if (!bandwidthLimitedDetected) {
                // Process interval should trigger target rate update if not already
                // triggered by OnSentPacket
                update = controller.onProcessInterval(ProcessInterval(atTime = currentTime))
                if (update.targetRate != null && update.targetRate!!.isBandwidthLimited) {
                    bandwidthLimitedDetected = true
                }
            }
            bandwidthLimitedDetected shouldBe true
        }
    }
}
