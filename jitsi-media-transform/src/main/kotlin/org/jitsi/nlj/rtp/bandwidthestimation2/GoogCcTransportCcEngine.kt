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
import org.jitsi.nlj.rtp.bandwidthestimation2.PacedPacketInfo.Companion.kNotAProbe
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Transport CC engine invoking GoogCc NetworkController.
 */
class GoogCcTransportCcEngine(
    val diagnosticContext: DiagnosticContext,
    parentLogger: Logger,
    private val scheduledExecutor: ScheduledExecutorService,
    private val sendProbing: (DataSize, Any?) -> Int,
    val clock: Clock = Clock.systemUTC(),
) : TransportCcEngine() {
    private val logger = createChildLogger(parentLogger)

    private val feedbackAdapter = TransportFeedbackAdapter(logger)
    private val networkController = factory.create(
        NetworkControllerConfig(
            logger,
            diagnosticContext,
            constraints = TargetRateConstraints(
                atTime = clock.instant(),
                startingRate = BandwidthEstimatorConfig.initBw,
                maxDataRate = GoogleCcEstimatorConfig.maxBw, // TODO: move these two to a generic config
                minDataRate = GoogleCcEstimatorConfig.minBw
            )
        )
    )
    private val bitrateProber = BitrateProber(logger)
    private var probeTask: ScheduledFuture<*>? = null

    private var processTask: ScheduledFuture<*>? = null

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
    override fun mediaPacketTagged(tccSeqNum: Int, length: DataSize, probingInfo: Any?) {
        val now = clock.instant()
        val pacedPacketInfo = probingInfo as? PacedPacketInfo
        feedbackAdapter.addPacket(
            tccSeqNum,
            length, // TODO: network overhead
            pacingInfo = pacedPacketInfo,
            creationTime = now
        )
        synchronized(this@GoogCcTransportCcEngine) {
            if (pacedPacketInfo == null) {
                bitrateProber.onIncomingPacket(length)
            }
            maybeScheduleProbing(now)
        }
    }

    @Synchronized
    override fun mediaPacketSent(tccSeqNum: Int, length: DataSize) {
        val now = clock.instant()
        // We need to do sequence number unwrapping in the TransportFeedbackAdapter, so
        // truncate it here so we can unwrap it again later.
        val truncatedSeqNum = tccSeqNum and 0xFFFF
        val sentPacketInfo = SentPacketInfo(
            packetId = truncatedSeqNum,
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
        return StatisticsSnapshot(
            feedbackAdapter.getStatisitics(),
            (networkController as GoogCcNetworkController).getStatistics(now)
        )
    }

    @Synchronized
    override fun addBandwidthListener(listener: BandwidthListener) {
        listeners.add(listener)
    }

    @Synchronized
    override fun removeBandwidthListener(listener: BandwidthListener) {
        listeners.remove(listener)
    }

    override fun start() {
        val startTime = clock.instant()
        var update =
            networkController.onNetworkAvailability(NetworkAvailability(atTime = startTime, networkAvailable = true))
        processUpdate(update) // Does this make sense to do during init?

        update = networkController.onProcessInterval(ProcessInterval(atTime = startTime))
        processUpdate(update)

        val processInterval = factory.getProcessInterval().toMillis()

        processTask = scheduledExecutor.scheduleAtFixedRate({
            synchronized(this@GoogCcTransportCcEngine) {
                val now = clock.instant()
                update = networkController.onProcessInterval(ProcessInterval(atTime = now))
                processUpdate(update)
            }
        }, processInterval, processInterval, TimeUnit.MILLISECONDS)
    }

    override fun stop() {
        synchronized(this@GoogCcTransportCcEngine) {
            // Stop bitrateProber from initiating any new probes
            bitrateProber.setEnabled(false)
            probeTask?.cancel(false)
        }
        processTask?.cancel(false)
    }

    private fun processUpdate(update: NetworkControlUpdate) {
        update.targetRate?.let { targetRate ->
            logger.info("GoogleCcEstimator setting TargetRate to $targetRate")
            listeners.forEach { it.bandwidthEstimationChanged(targetRate.targetRate) }
        }
        update.congestionWindow?.let { congestionWindow ->
            /* We don't use a congestion window */
            /* TODO: does this do anything bad to the estimator? */
            logger.info("GoogleCcEstimator wants to set CongestionWindow to $congestionWindow")
        }
        update.pacerConfig?.let { pacerConfig ->
            /* We don't use a pacer */
            /* TODO: does this do anything bad to the estimator? */
            logger.info("GoogleCcEstimator wants to set PacerConfig to $pacerConfig")
        }
        update.probeClusterConfigs.let { configs ->
            if (configs.isNotEmpty()) {
                logger.info("GoogleCcEstimator creating ${configs.size} ProbeClusterConfigs: $configs")
                synchronized(this@GoogCcTransportCcEngine) {
                    configs.forEach { config ->
                        bitrateProber.createProbeCluster(config)
                    }
                    maybeScheduleProbing(clock.instant())
                }
            }
        }

        if (timeSeriesLogger.isTraceEnabled && update.isNotEmpty()) {
            val now = update.atTime ?: clock.instant()
            val stats = (networkController as GoogCcNetworkController).getStatistics(now)
            val statsPoint = diagnosticContext.makeTimeSeriesPoint("goog_cc_stats", now)
            stats.addToTimeSeriesPoint(statsPoint)
            timeSeriesLogger.trace(statsPoint)

            val updatePoint = diagnosticContext.makeTimeSeriesPoint("goog_cc_update", now)
            update.addToTimeSeriesPoint(updatePoint)
            timeSeriesLogger.trace(updatePoint)
        }
    }

    /** Schedule bitrate probing if needed and not current scheduled.
     *  Should be synchronized on this@GoogCcTransportCcEngine. */
    private fun maybeScheduleProbing(now: Instant) {
        if (bitrateProber.isProbing() && probeTask == null) {
            val nextProbeTime = bitrateProber.nextProbeTime(now)
            if (nextProbeTime == Instant.MAX) {
                return
            }
            val delay = if (nextProbeTime == Instant.MIN) {
                0
            } else {
                Duration.between(now, nextProbeTime).toMillis().coerceAtLeast(0)
            }

            scheduledExecutor.schedule({
                val cluster: PacedPacketInfo
                val probeSize: DataSize
                synchronized(this@GoogCcTransportCcEngine) {
                    probeTask = null
                    val now = clock.instant()
                    cluster = bitrateProber.currentCluster(now) ?: PacedPacketInfo()
                    if (cluster.probeClusterId == kNotAProbe) {
                        return@schedule
                    }
                    probeSize = bitrateProber.recommendedMinProbeSize()
                    val probeSent = sendProbing(probeSize, cluster)
                    bitrateProber.probeSent(now, probeSent.bytes)
                    maybeScheduleProbing(now)
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        private val factory = GoogCcNetworkControllerFactory()

        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(GoogCcTransportCcEngine::class.java)
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
