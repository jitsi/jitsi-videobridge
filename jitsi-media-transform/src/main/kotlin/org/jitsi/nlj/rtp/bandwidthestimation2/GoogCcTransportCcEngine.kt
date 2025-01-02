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

import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig
import org.jitsi.nlj.rtp.bandwidthestimation.GoogleCcEstimatorConfig
import org.jitsi.nlj.util.DataSize
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*

/**
 * Transport CC engine invoking GoogCc NetworkController.
 */
class GoogCcTransportCcEngine(
    diagnosticContext: DiagnosticContext,
    parentLogger: Logger,
    val clock: Clock = Clock.systemUTC(),
) : TransportCcEngine() {
    private val logger = createChildLogger(parentLogger)

    private val feedbackAdapter = TransportFeedbackAdapter(logger)
    private val networkController = GoogCcNetworkController(
        NetworkControllerConfig(
            logger,
            diagnosticContext,
            constraints = TargetRateConstraints(
                startingRate = BandwidthEstimatorConfig.initBw,
                maxDataRate = GoogleCcEstimatorConfig.maxBw, // TODO: move these two to a generic config
                minDataRate = GoogleCcEstimatorConfig.minBw
            )
        ),
        GoogCcConfig(
            // TODO configure non-default fields here.
        )
    ).apply {
        val update = onNetworkAvailability(NetworkAvailability(atTime = clock.instant(), networkAvailable = true))
        processUpdate(update) // Does this make sense to do during init?
    }

    private val listeners = LinkedList<BandwidthListener>()

    @Synchronized
    override fun onRttUpdate(rtt: Duration) {
        val update =
            networkController.onRoundTripTimeUpdate(
                RoundTripTimeUpdate(receiveTime = clock.instant(), roundTripTime = rtt)
            )
        processUpdate(update)
    }

    @Synchronized
    override fun rtcpPacketReceived(rtcpPacket: RtcpPacket, receivedTime: Instant?) {
        if (rtcpPacket is RtcpFbTccPacket) {
            val now = clock.instant()
            val feedback = feedbackAdapter.processTransportFeedback(rtcpPacket, now)
            if (feedback != null) {
                val update = networkController.onTransportPacketsFeedback(feedback)
                processUpdate(update)
            }
        }
        // TODO: handle other RTCP packets that the network controller wants
    }

    @Synchronized
    override fun mediaPacketTagged(tccSeqNum: Int, length: DataSize) {
        val now = clock.instant()
        feedbackAdapter.addPacket(
            tccSeqNum,
            DataSize.ZERO, // TODO: network overhead
            pacingInfo = null,
            creationTime = now
        )
    }

    @Synchronized
    override fun mediaPacketSent(tccSeqNum: Int, length: DataSize) {
        val now = clock.instant()
        val sentPacketInfo = SentPacketInfo(
            packetId = tccSeqNum,
            sendTime = now,
            info = PacketInfo(
                // TODO I think these should always be true when tccSeqNum is defined?
                includedInAllocation = true,
                includedInFeedback = true,
                packetSizeBytes = length.bytes.toLong()
            )
        )
        val sentPacket = feedbackAdapter.processSentPacket(sentPacketInfo)
        if (sentPacket != null) {
            val update = networkController.onSentPacket(sentPacket)
            processUpdate(update)
        }
    }

    @Synchronized
    override fun getStatistics(): StatisticsSnapshot {
        val now = clock.instant()
        return StatisticsSnapshot(feedbackAdapter.getStatisitics(), networkController.getStatistics(now))
    }

    @Synchronized
    override fun addBandwidthListener(listener: BandwidthListener) {
        listeners.add(listener)
    }

    @Synchronized
    override fun removeBandwidthListener(listener: BandwidthListener) {
        listeners.remove(listener)
    }

    private fun processUpdate(update: NetworkControlUpdate) {
        update.targetRate?.let { targetRate ->
            logger.info("GoogleCcEstimator setting TargetRate to $targetRate")
            listeners.forEach { it.bandwidthEstimationChanged(targetRate.targetRate) }
        }
        update.congestionWindow?.let { congestionWindow ->
            logger.warn("TODO: GoogleCcEstimator wants to set CongestionWindow to $congestionWindow")
        }
        update.pacerConfig?.let { pacerConfig ->
            logger.warn("TODO: GoogleCcEstimator wants to set PacerConfig to $pacerConfig")
        }
        update.probeClusterConfigs.let { configs ->
            if (configs.isNotEmpty()) {
                logger.warn("TODO: GoogleCcEstimator wants to set ${configs.size} ProbeClusterConfigs: $configs")
            }
        }
    }

    class StatisticsSnapshot(
        val transportAdapterState: TransportFeedbackAdapter.StatisticsSnapshot,
        val networkControllerState: GoogCcNetworkController.StatisticsSnapshot
    ) : TransportCcEngine.StatisticsSnapshot() {
        override fun toJson(): Map<*, *> {
            return OrderedJsonObject().apply {
                put("name", GoogCcTransportCcEngine::class.java.simpleName)
                put("transport_adapter", transportAdapterState.toJson())
                put("network_controller", networkControllerState.toJson())
            }
        }
    }
}
