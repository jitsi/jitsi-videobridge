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
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.nlj.rtp.bandwidthestimation2.simulation.CallClient
import org.jitsi.nlj.rtp.bandwidthestimation2.simulation.CallClientConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.simulation.CallConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.simulation.EmulatedNetworkNode
import org.jitsi.nlj.rtp.bandwidthestimation2.simulation.NetworkSimulationConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.simulation.Scenario
import org.jitsi.nlj.rtp.bandwidthestimation2.simulation.VideoStreamConfig
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.maxDuration
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant
import java.util.*

/** Tests of GoogCcNetworkController,
 * based on WebRTC modules/congestion_controller/goog_cc/goog_cc_network_control_unittest.cc in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 */

// Count dips from a constant high bandwidth level within a short window.
private fun countBandwidthDips(bandwidthHistory: Queue<Bandwidth>, threshold: Bandwidth): Int {
    if (bandwidthHistory.isEmpty()) {
        return 0
    }
    val first = bandwidthHistory.poll()

    var dips = 0
    var stateHigh = true
    while (!bandwidthHistory.isEmpty()) {
        if (bandwidthHistory.first() + threshold < first && stateHigh) {
            ++dips
            stateHigh = false
        } else if (bandwidthHistory.first() == first) {
            stateHigh = true
        } else if (bandwidthHistory.first() > first) {
            // If this is toggling we will catch it later when front becomes first.
            stateHigh = false
        }
        bandwidthHistory.remove()
    }
    return dips
}

private fun createFeedbackOnlyFactory(): GoogCcNetworkControllerFactory {
    return GoogCcNetworkControllerFactory(GoogCcFactoryConfig(feedbackOnly = true))
}

private const val kInitialBitrateKbps = 60
private val kInitialBitrate = kInitialBitrateKbps.kbps
private const val kDefaultPacingRate = 2.5

private fun createVideoSendingClient(
    s: Scenario,
    config: CallClientConfig,
    sendLink: List<EmulatedNetworkNode>,
    returnLink: List<EmulatedNetworkNode>
): CallClient {
    val client = s.createClient("send", config)
    val route = s.createRoutes(
        client,
        sendLink,
        s.createClient("return", config),
        returnLink
    )
    s.createVideoStream(route.forward(), VideoStreamConfig())
    return client
}

private fun createRouteChange(
    time: Instant,
    startRate: Bandwidth? = null,
    minRate: Bandwidth? = null,
    maxRate: Bandwidth? = null
): NetworkRouteChange {
    return NetworkRouteChange(
        atTime = time,
        constraints = TargetRateConstraints(minDataRate = minRate, maxDataRate = maxRate, startingRate = startRate)
    )
}

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

// Create transport packets feedback with a built-up delay.
fun createTransportPacketsFeedback(
    perPacketNetworkDelay: Duration,
    oneWayDelay: Duration,
    sendTime: Instant): TransportPacketsFeedback {
    var delayBuildup = oneWayDelay
    val kFeedbackSize = 3
    val kPayloadSize = 1000L
    val feedback = TransportPacketsFeedback()
    for (i in 0 until kFeedbackSize) {
        val packet = createPacketResult(
            /*arrival_time=*/sendTime + delayBuildup, sendTime, kPayloadSize,
        PacedPacketInfo()
        )
        delayBuildup += perPacketNetworkDelay
        feedback.feedbackTime = packet.receiveTime + oneWayDelay
        feedback.packetFeedbacks.add(packet)
    }
    return feedback
}

// Scenarios:

fun updatesTargetRateBasedOnLinkCapacity(testName: String = "") {
    val factory = createFeedbackOnlyFactory()
    val s = Scenario("googcc_unit/target_capacity" + testName, false)
    val config = CallClientConfig()
    config.transport.ccFactory = factory
    config.transport.rates.minRate = 10.kbps
    config.transport.rates.maxRate = 1500.kbps
    config.transport.rates.startRate = 300.kbps
    val sendNet = s.createMutableSimulationNode { c: NetworkSimulationConfig ->
        c.bandwidth = 500.kbps
        c.delay = 100.ms
        c.lossRate = 0.0
    }
    val retNet = s.createMutableSimulationNode { c: NetworkSimulationConfig ->
        c.delay = 100.ms
    }
    val truth = s.createPrinter(
        "send.truth.txt", maxDuration, listOf(sendNet.configPrinter()))

    val client = createVideoSendingClient(s, config, listOf(sendNet.node()),
        listOf(retNet.node()))

    truth.printRow()
    s.runFor(25.secs)
    truth.printRow()
    client.targetRate().kbps shouldBe 450.0 plusOrMinus 100.0

    sendNet.updateConfig { c: NetworkSimulationConfig ->
        c.bandwidth = 800.kbps
        c.delay = 100.ms
    }

    truth.printRow()
    s.runFor(20.secs)
    truth.printRow()
    client.targetRate().kbps shouldBe 750.0 plusOrMinus 150.0

    sendNet.updateConfig {  c: NetworkSimulationConfig ->
        c.bandwidth = 100.kbps
        c.delay = 200.ms
    }
    retNet.updateConfig {  c: NetworkSimulationConfig -> c.delay = 200.ms }

    truth.printRow()
    s.runFor(50.secs)
    truth.printRow()
    client.targetRate().kbps shouldBe 90.0 plusOrMinus 25.0
}

fun runRembDipScenario(testName: String): Bandwidth {
    val s = Scenario(testName)
    val netConf = NetworkSimulationConfig()
    netConf.bandwidth = 2000.kbps
    netConf.delay = 50.ms
    val client = s.createClient("send") { c ->
        c.transport.rates.startRate = 1000.kbps
    }
    val sendNet = listOf(s.createSimulationNode(netConf))
    val retNet = listOf(s.createSimulationNode(netConf))
    val route = s.createRoutes(client, sendNet, s.createClient("return", CallClientConfig()), retNet)
    s.createVideoStream(route.forward(), VideoStreamConfig())

    s.runFor(10.secs)
    client.sendBandwidth().kbps shouldBeGreaterThan 1500.0

    val rembLimit = 250.kbps
    client.setRemoteBitrate(rembLimit)
    s.runFor(1.secs)
    client.sendBandwidth() shouldBe rembLimit

    val rembLimitLifted = 10000.kbps
    client.setRemoteBitrate(rembLimitLifted)
    s.runFor(10.secs)

    return client.sendBandwidth()
}

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

            val update = controller.onProcessInterval(ProcessInterval(atTime = Instant.ofEpochMilli(123456)))

            update.targetRate!!.targetRate shouldBe kInitialBitrate
            update.pacerConfig!!.dataRate() shouldBe kInitialBitrate * kDefaultPacingRate
            update.probeClusterConfigs[0].targetDataRate shouldBe kInitialBitrate * 3
            update.probeClusterConfigs[1].targetDataRate shouldBe kInitialBitrate * 5
        }

        "ReactsToChangedNetworkConditions" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()
            var currentTime = Instant.ofEpochMilli(123)
            var update = controller.onProcessInterval(ProcessInterval(atTime = currentTime))
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

        "ProbeOnRouteChange" {
            val fixture = NetworkControllerTextFixture()
            val controller = fixture.createController()
            val currentTime = Instant.ofEpochMilli(123)
            val newBitrate = 200000.bps
            var update = controller.onNetworkRouteChange(createRouteChange(currentTime, newBitrate))
            update.targetRate!!.targetRate shouldBe newBitrate
            update.pacerConfig!!.dataRate() shouldBe newBitrate * kDefaultPacingRate
            update.probeClusterConfigs.size shouldBe 2

            // If the bitrate is reset to -1, the new starting bitrate will be
            // the minimum default bitrate.
            val kDefaultMinBitrate = 5.kbps
            update = controller.onNetworkRouteChange(createRouteChange(currentTime))
            update.targetRate!!.targetRate shouldBe kDefaultMinBitrate
            update.pacerConfig!!.dataRate().bps.toDouble() shouldBe
                kDefaultMinBitrate.bps * kDefaultPacingRate plusOrMinus 10.0
            update.probeClusterConfigs.size shouldBe 2
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
                createRouteChange(currentTime,
                    kInitialBitrate * 2,
                    Bandwidth.ZERO,
                    kInitialBitrate * 20
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

        /* Skipping PaceAtMaxOfLowerLinkCapacityAndBwe - depends on field trial */

        "CongestionWindowPushbackOnNetworkDelay" {
            val factory = GoogCcNetworkControllerFactory(
                GoogCcFactoryConfig(
                    feedbackOnly = true,
                    rateControlSettings = CongestionWindowConfig(
                        queueSizeMs = 800,
                        minBitrateBps = 30000
                    )
                )
            )
            val s = Scenario("googcc_unit/cwnd_on_delay", false)
            val sendNet =
                s.createMutableSimulationNode { c->
                    c.bandwidth = 1000.kbps
                    c.delay = 100.ms
                }
            val retNet = s.createSimulationNode { c-> c.delay = 100.ms }
            val config = CallClientConfig()
            config.transport.ccFactory = factory
            // Start high so bandwidth drop has max effect.
            config.transport.rates.startRate = 300.kbps
            config.transport.rates.maxRate = 2000.kbps
            config.transport.rates.minRate = 10.kbps

            val client = createVideoSendingClient(s, config, listOf(sendNet.node()), listOf(retNet))
            s.runFor(10.secs)
            sendNet.pauseTransmissionUntil(s.now() + 10.secs)
            s.runFor(3.secs)

            // After 3 seconds without feedback from any sent packets, we expect that the
            // target rate is reduced to the minimum pushback threshold
            // kDefaultMinPushbackTargetBitrateBps, which is defined as 30 kbps in
            // congestion_window_pushback_controller.
            client.targetRate().kbps shouldBeLessThan 40.0
        }
    }
}