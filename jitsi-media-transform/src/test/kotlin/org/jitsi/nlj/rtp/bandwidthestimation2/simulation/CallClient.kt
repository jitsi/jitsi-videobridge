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
package org.jitsi.nlj.rtp.bandwidthestimation2.simulation

import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.bandwidthestimation2.MutableNetworkControlUpdate
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkAvailability
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControlUpdate
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControllerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControllerFactoryInterface
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControllerInterface
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkRouteChange
import org.jitsi.nlj.rtp.bandwidthestimation2.ProcessInterval
import org.jitsi.nlj.rtp.bandwidthestimation2.RemoteBitrateReport
import org.jitsi.nlj.rtp.bandwidthestimation2.RoundTripTimeUpdate
import org.jitsi.nlj.rtp.bandwidthestimation2.SentPacket
import org.jitsi.nlj.rtp.bandwidthestimation2.StreamsConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.TargetRateConstraints
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportLossReport
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.utils.logging2.Logger
import java.time.Duration
import java.time.Instant

/** Test scenario call controller,
 * based on WebRTC test/scenario/call_client.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Only those features used by GoogCcNetworkControllerTest are implemented.
 */

private fun createCall(
    timeController: TimeController,
    config: CallClientConfig,
    networkControllerFactory: LoggingNetworkControllerFactory,
    // audioState: AudioState
): Call {
    val callConfig = CallConfig().apply {
        bitrateConfig.maxBitrateBps = config.transport.rates.maxRate.bps.toInt()
        bitrateConfig.minBitrateBps = config.transport.rates.minRate.bps.toInt()
        bitrateConfig.startBitrateBps = config.transport.rates.startRate.bps.toInt()
        taskQueueFactory = timeController.getTaskQueueFactory()
        this.networkControllerFactory = networkControllerFactory
        // TODO audioState?
        pacerBurstInterval = config.pacerBurstInterval
    }
    val clock = timeController.getClock()
    return Call.create(
        callConfig,
        clock,
        RtpTransportControllerSendFactory().create(
            callConfig.extractTransportConfig(),
            clock
        )
    )
}

// Helper class to capture network controller state.
class NetworkControlUpdateCache(
    val controller: NetworkControllerInterface
) : NetworkControllerInterface {
    private val updateState = MutableNetworkControlUpdate()

    override fun onNetworkAvailability(msg: NetworkAvailability): NetworkControlUpdate {
        return update(controller.onNetworkAvailability(msg))
    }

    override fun onNetworkRouteChange(msg: NetworkRouteChange): NetworkControlUpdate {
        return update(controller.onNetworkRouteChange(msg))
    }

    override fun onProcessInterval(msg: ProcessInterval): NetworkControlUpdate {
        return update(controller.onProcessInterval(msg))
    }

    override fun onRemoteBitrateReport(msg: RemoteBitrateReport): NetworkControlUpdate {
        return update(controller.onRemoteBitrateReport(msg))
    }

    override fun onRoundTripTimeUpdate(msg: RoundTripTimeUpdate): NetworkControlUpdate {
        return update(controller.onRoundTripTimeUpdate(msg))
    }

    override fun onSentPacket(sentPacket: SentPacket): NetworkControlUpdate {
        return update(controller.onSentPacket(sentPacket))
    }

    override fun onStreamsConfig(msg: StreamsConfig): NetworkControlUpdate {
        return update(controller.onStreamsConfig(msg))
    }

    override fun onTargetRateConstraints(constraints: TargetRateConstraints): NetworkControlUpdate {
        return update(controller.onTargetRateConstraints(constraints))
    }

    override fun onTransportLossReport(msg: TransportLossReport): NetworkControlUpdate {
        return update(controller.onTransportLossReport(msg))
    }

    override fun onTransportPacketsFeedback(report: TransportPacketsFeedback): NetworkControlUpdate {
        return update(controller.onTransportPacketsFeedback(report))
    }

    fun updateState() = updateState

    private fun update(update: NetworkControlUpdate): NetworkControlUpdate {
        if (update.targetRate != null) {
            updateState.targetRate = update.targetRate
        }
        if (update.pacerConfig != null) {
            updateState.pacerConfig = update.pacerConfig
        }
        if (update.congestionWindow != null) {
            updateState.congestionWindow = update.congestionWindow
        }
        if (update.probeClusterConfigs.isNotEmpty()) {
            updateState.probeClusterConfigs = update.probeClusterConfigs.toMutableList()
        }
        return update
    }
}

class LoggingNetworkControllerFactory(
    parentLogger: Logger,
    config: TransportControllerConfig
) : NetworkControllerFactoryInterface {
    private var lastController: NetworkControlUpdateCache? = null

    override fun create(config: NetworkControllerConfig): NetworkControllerInterface {
        TODO("Not yet implemented")
    }

    override fun getProcessInterval(): Duration {
        TODO("Not yet implemented")
    }

    fun logCongestionControllerStats(atTime: Instant) {
    }

    fun setRemoteBitrateEstimate(msg: RemoteBitrateReport) {
    }

    fun getUpdate(): NetworkControlUpdate {
        return lastController?.updateState() ?: NetworkControlUpdate()
    }
}

// CallClient represents a participant in a call scenario. It is created by the
// Scenario class and is used as sender and receiver when setting up a media
// stream session.
class CallClient(
    val timeController: TimeController,
    val parentLogger: Logger,
    name: String,
    val config: CallClientConfig
) : EmulatedNetworkReceiverInterface {
    private val logger = parentLogger.createChildLogger(javaClass.name).apply {
        addContext("name", name)
    }
    private val clock = timeController.getClock()

    internal val networkControllerFactory = LoggingNetworkControllerFactory(logger, config.transport)

    private val call: Call = createCall(timeController, config, networkControllerFactory)
    val transport = NetworkNodeTransport(timeController.getClock())
    private val endpoints = mutableListOf<Pair<EmulatedEndpoint, Short>>()

    fun getStats(): Call.Stats {
        return call.getStats()
    }

    fun sendBandwidth(): Bandwidth = getStats().sendBandwidthBps.bps

    fun targetRate(): Bandwidth {
        return networkControllerFactory.getUpdate().targetRate!!.targetRate
    }

    fun setRemoteBitrate(bitrate: Bandwidth) {
        val msg = RemoteBitrateReport(bandwidth = bitrate, receiveTime = clock.instant())
        networkControllerFactory.setRemoteBitrateEstimate(msg)
    }

    fun setVideoReceiveRtpHeaderExtensions(extensions: List<RtpExtension>) {
        TODO()
    }

    internal fun bind(endpoint: EmulatedEndpoint): Short {
        val port = endpoint.bindReceiver(0, this)!!
        endpoints.add(Pair(endpoint, port))
        return port
    }
}

class CallClientPair(
    val first: CallClient,
    val second: CallClient
) {
    fun forward() = Pair(first, second)
    fun reverse() = Pair(second, first)
}
