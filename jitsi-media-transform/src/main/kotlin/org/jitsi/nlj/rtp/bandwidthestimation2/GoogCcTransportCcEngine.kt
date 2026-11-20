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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.rtp.TransportCcEngine
import org.jitsi.nlj.rtp.bandwidthestimation.BandwidthEstimatorConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.PacedPacketInfo.Companion.kNotAProbe
import org.jitsi.nlj.rtp.scream.ScreamNetworkController
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpReportBlock
import org.jitsi.rtp.rtcp.RtcpRrPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.ccfb.RtcpFbCcfbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket
import org.jitsi.utils.RateLimit
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
 * Transport CC engine invoking a NetworkController: GoogCc, or Scream.  Contains some code based loosely on
 * WebRTC call/rtp_transport_controller_send.{h,cc} in
 * WebRTC tag branch-heads/8078 (Chromium 156)
 *
 * @param screamMode when Scream is used in place of GoogCc.
 */
class GoogCcTransportCcEngine(
    val diagnosticContext: DiagnosticContext,
    parentLogger: Logger,
    private val scheduledExecutor: ScheduledExecutorService,
    private val sendProbing: (DataSize, Any?) -> Int,
    val clock: Clock = Clock.systemUTC(),
    private val screamMode: ScreamMode = BandwidthEstimatorConfig.screamMode,
) : TransportCcEngine() {
    private val logger = createChildLogger(parentLogger)

    private val feedbackAdapter = TransportFeedbackAdapter(logger)
    private val ect1Policy = Ect1Policy(logger)

    /** Whether RFC 8888 feedback is used, see [setRfc8888FeedbackNegotiated]. */
    private var rfc8888FeedbackNegotiated = false

    /** Whether packets can be sent with the ECT(1) marking, see [setTransportSupportsEct1]. */
    private var transportSupportsEct1 = false

    private var started = false

    /** The last target rate a controller reported. */
    private var lastTargetRate: Bandwidth? = null

    private var networkController = createController(BandwidthEstimatorConfig.initBw)

    private fun createController(startingRate: Bandwidth): NetworkControllerInterface {
        val factory = GoogCcNetworkControllerFactory(
            GoogCcFactoryConfig(rfc8888FeedbackNegotiated = rfc8888FeedbackNegotiated, screamMode = screamMode)
        )
        val controller = factory.create(
            NetworkControllerConfig(
                logger,
                diagnosticContext,
                clock = clock,
                constraints = TargetRateConstraints(
                    atTime = clock.instant(),
                    startingRate = startingRate,
                    maxDataRate = BandwidthEstimatorConfig.maxBw,
                    minDataRate = BandwidthEstimatorConfig.minBw
                )
            )
        )
        ect1Policy.setCongestionControllerSupportsEcn(controller.supportsEcnAdaptation())
        return controller
    }

    /**
     * Sets whether RFC 8888 congestion control feedback has been negotiated with the remote endpoint, as opposed to
     * transport-cc feedback. Only RFC 8888 feedback reports the ECN marking of the packets which were received.
     *
     * In WebRTC the network controller is created once the feedback format is known. Here it exists before the
     * endpoint is signaled, so it is replaced when the format calls for the other kind of controller. The new
     * controller starts from the last target rate of the old one.
     */
    @Synchronized
    fun setRfc8888FeedbackNegotiated(negotiated: Boolean) {
        if (negotiated == rfc8888FeedbackNegotiated) {
            return
        }
        rfc8888FeedbackNegotiated = negotiated
        logger.info("Assume ${if (negotiated) "RFC 8888" else "TWCC"} feedback.")
        ect1Policy.setFeedbackSupportsEcn(negotiated)

        if (screamMode.usesScream(negotiated) != (networkController is ScreamNetworkController)) {
            networkController = createController(
                (lastTargetRate ?: BandwidthEstimatorConfig.initBw)
                    .coerceIn(BandwidthEstimatorConfig.minBw, BandwidthEstimatorConfig.maxBw)
            )
            logger.info("Now using ${networkController.javaClass.simpleName}")
            if (started) {
                startController()
            }
        }
    }

    /**
     * Sets whether the transport can send packets with the ECT(1) marking. When it can, and the feedback and the
     * network controller support it, packets are flagged to be sent with it, see [Ect1Policy].
     */
    @Synchronized
    fun setTransportSupportsEct1(supported: Boolean) {
        transportSupportsEct1 = supported
    }

    /**
     * Tells the engine that packets now take a different network path, for example after the remote endpoint
     * changed address.
     */
    @Synchronized
    fun onNetworkRouteChanged() {
        // WebRTC tells the controller of every route change if the controller adapts to ECN, so that it knows that
        // the round trip time may have changed. It tells other controllers only of a change which is significant
        // enough to restart the estimate, which cannot be judged here.
        if (rfc8888FeedbackNegotiated && networkController.supportsEcnAdaptation()) {
            val now = clock.instant()
            val update = networkController.onNetworkRouteChange(
                NetworkRouteChange(
                    atTime = now,
                    constraints = TargetRateConstraints(
                        atTime = now,
                        maxDataRate = BandwidthEstimatorConfig.maxBw,
                        minDataRate = BandwidthEstimatorConfig.minBw
                    ),
                    restartBwe = false
                )
            )
            processUpdate(update)
        }

        // The new path may preserve the ECT(1) marking even if the old one did not.
        ect1Policy.onNetworkRouteChanged()
    }

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
        when (rtcpPacket) {
            is RtcpFbTccPacket -> {
                val time = receivedTime ?: clock.instant()
                onTransportPacketsFeedback(feedbackAdapter.processTransportFeedback(rtcpPacket, time))
            }

            is RtcpFbCcfbPacket -> {
                val time = receivedTime ?: clock.instant()
                onTransportPacketsFeedback(feedbackAdapter.processCongestionControlFeedback(rtcpPacket, time))
            }

            is RtcpFbRembPacket -> {
                /* Ignore REMB packets - if we're supposed to be receiving them they'll be handled by [RembHandler],
                 * and if we're not we're getting mysterious spurious REMB messages which we want to ignore.
                 */

                /*
                val time = receivedTime ?: clock.instant()
                val msg = RemoteBitrateReport(receiveTime = time, bandwidth = rtcpPacket.bitrate.bps)
                val update = networkController.onRemoteBitrateReport(msg)
                processUpdate(update)
                 */
            }

            is RtcpSrPacket -> {
                val time = receivedTime ?: clock.instant()
                onReport(time, rtcpPacket.reportBlocks)
            }

            is RtcpRrPacket -> {
                val time = receivedTime ?: clock.instant()
                onReport(time, rtcpPacket.reportBlocks)
            }
        }
    }

    private class LossReport {
        var extendedHighestSequenceNumber: Long = 0
        var cumulativeLost = 0
    }

    private val lastReportBlocks = HashMap<Long, LossReport>()
    private var lastReportBlockTime = clock.instant()

    private fun onReport(receiveTime: Instant, reportBlocks: List<RtcpReportBlock>) {
        if (reportBlocks.isEmpty()) {
            return
        }

        var totalPacketsLostDelta = 0L
        var totalPacketsDelta = 0L

        reportBlocks.forEach { reportBlock ->
            val newLossReport = LossReport()
            val lastLossReport = lastReportBlocks.putIfAbsent(reportBlock.ssrc, newLossReport)
            if (lastLossReport != null) {
                totalPacketsDelta += reportBlock.extendedHighestSeqNum - lastLossReport.extendedHighestSequenceNumber
                totalPacketsLostDelta += reportBlock.cumulativePacketsLost - lastLossReport.cumulativeLost
            }
            val lossReport = lastLossReport ?: newLossReport
            lossReport.extendedHighestSequenceNumber = reportBlock.extendedHighestSeqNum
            lossReport.cumulativeLost = reportBlock.cumulativePacketsLost
        }
        // Can only compute delta if there has been previous blocks to compare to. If
        // not, total_packets_delta will be unchanged and there's nothing more to do.
        if (totalPacketsDelta == 0L) {
            return
        }
        val packetsReceivedDelta = totalPacketsDelta - totalPacketsLostDelta
        // To detect lost packets, at least one packet has to be received.
        if (packetsReceivedDelta < 1) {
            return
        }
        val msg = TransportLossReport(
            packetsLostDelta = totalPacketsLostDelta,
            packetsReceivedDelta = packetsReceivedDelta,
            receiveTime = receiveTime,
            startTime = lastReportBlockTime,
            endTime = receiveTime
        )
        val update = networkController.onTransportLossReport(msg)
        processUpdate(update)
        lastReportBlockTime = receiveTime
    }

    /** Handles feedback about sent packets, whichever RTCP feedback format it arrived in. */
    private fun onTransportPacketsFeedback(feedback: TransportPacketsFeedback?) {
        if (feedback == null) {
            return
        }
        val update = networkController.onTransportPacketsFeedback(feedback)
        processUpdate(update)
        ect1Policy.onPacketsFeedback(feedback.hasPacketWithBleachedEct1(), feedback.hasPacketWithEcn())

        feedback.packetFeedbacks.forEach { fb ->
            lossListeners.forEach { l ->
                if (fb.isReceived()) {
                    l.packetReceived(fb.reportedRecoveredForTheFirstTime)
                } else if (fb.reportedLostForTheFirstTime) {
                    l.packetLost(1)
                }
            }
        }
    }

    @Synchronized
    override fun mediaPacketTagged(packetInfo: org.jitsi.nlj.PacketInfo, tccSeqNum: Long) {
        val now = clock.instant()
        val length = packetInfo.packet.length.bytes
        val pacedPacketInfo = packetInfo.probingInfo as? PacedPacketInfo
        packetInfo.sendAsEct1 = transportSupportsEct1 && ect1Policy.shouldSendEct1()
        feedbackAdapter.addPacket(
            packetInfo,
            tccSeqNum,
            DataSize.ZERO, // TODO: network overhead
            creationTime = now
        )
        ect1Policy.onPacketSent(now, packetInfo.sendAsEct1)
        if (pacedPacketInfo == null) {
            bitrateProber.onIncomingPacket(length)
        }
        maybeScheduleProbing(now)
    }

    @Synchronized
    override fun mediaPacketSent(packetInfo: org.jitsi.nlj.PacketInfo, tccSeqNum: Long) {
        val now = clock.instant()
        val length = packetInfo.packet.length.toLong()
        val sentPacketInfo = SentPacketInfo(
            packetId = tccSeqNum,
            sendTime = now,
            info = PacketInfo(
                // TODO I think these should always be true when tccSeqNum is defined?
                includedInAllocation = true,
                includedInFeedback = true,
                packetSizeBytes = length
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
            networkController.getStatistics(now),
            rfc8888FeedbackNegotiated,
            transportSupportsEct1 && ect1Policy.shouldSendEct1()
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

    /** Tells the current network controller that the network is available. */
    private fun startController() {
        val startTime = clock.instant()
        // processUpdate throws if a listener throws. Apply both updates before that exception is rethrown.
        var failure: Throwable? = null
        listOf(
            networkController.onNetworkAvailability(NetworkAvailability(atTime = startTime, networkAvailable = true)),
            networkController.onProcessInterval(ProcessInterval(atTime = startTime))
        ).forEach { update ->
            try {
                processUpdate(update) // Does this make sense to do during init?
            } catch (t: Throwable) {
                val first = failure
                if (first == null) {
                    failure = t
                } else {
                    first.addSuppressed(t)
                }
            }
        }
        failure?.let { throw it }
    }

    @Synchronized
    override fun start() {
        started = true
        startController()

        // The periodic task is scheduled last, so that it does not run for an engine that failed to start.
        val processInterval = GoogCcNetworkControllerFactory().getProcessInterval().toMillis()
        processTask = scheduledExecutor.scheduleAtFixedRate({
            processExceptionLog.loggingExceptions {
                synchronized(this@GoogCcTransportCcEngine) {
                    val now = clock.instant()
                    processUpdate(networkController.onProcessInterval(ProcessInterval(atTime = now)))
                }
            }
        }, processInterval, processInterval, TimeUnit.MILLISECONDS)
    }

    /**
     * Logs the exceptions of one of the tasks given to [scheduledExecutor]. The executor stores an exception from a
     * task in the task's Future, and nothing reads that Future, so the exception would never be logged. The executor
     * also stops running a periodic task after the task throws. The log messages are rate limited, because a task
     * can fail every time it runs. Each task has its own limit and count, so that a task that fails often does not
     * hide the failures of another.
     */
    private inner class ExceptionLog(private val what: String) {
        private val rateLimit = RateLimit()

        /** The number of exceptions that were not logged since the last log message. */
        private var numNotLogged = 0

        /** Runs [block] and logs any exception it throws. */
        inline fun loggingExceptions(block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                log(t)
            }
        }

        fun log(t: Throwable) {
            val notLogged = synchronized(this) {
                if (rateLimit.accept()) {
                    numNotLogged.also { numNotLogged = 0 }
                } else {
                    numNotLogged++
                    null
                }
            }
            if (notLogged != null) {
                logger.error(
                    "Exception while $what" + if (notLogged > 0) " ($notLogged more exceptions not logged)" else "",
                    t
                )
            }
        }
    }

    private val processExceptionLog = ExceptionLog("processing an interval")
    private val probeExceptionLog = ExceptionLog("sending a probe")

    @Synchronized
    override fun stop() {
        // Stop bitrateProber from initiating any new probes
        bitrateProber.setEnabled(false)
        probeTask?.cancel(false)
        processTask?.cancel(false)
    }

    private fun processUpdate(update: NetworkControlUpdate) {
        // The first exception thrown by a listener. It is rethrown after the whole update has been applied. The
        // network controller has already changed state, so the update must not be left half applied.
        var listenerFailure: Throwable? = null
        update.targetRate?.let { targetRate ->
            logger.debug { "GoogleCcEstimator setting TargetRate to $targetRate" }
            lastTargetRate = targetRate.targetRate
            listeners.forEach { listener ->
                try {
                    listener.bandwidthEstimationChanged(targetRate.targetRate)
                } catch (t: Throwable) {
                    val first = listenerFailure
                    if (first == null) {
                        listenerFailure = t
                    } else {
                        first.addSuppressed(t)
                    }
                }
            }
        }
        update.congestionWindow?.let { congestionWindow ->
            /* We don't use a congestion window */
            /* TODO: does this do anything bad to the estimator? */
            logger.trace { "GoogleCcEstimator wants to set CongestionWindow to $congestionWindow" }
        }
        update.pacerConfig?.let { pacerConfig ->
            /* We don't use a pacer */
            /* TODO: does this do anything bad to the estimator? */
            logger.trace { "GoogleCcEstimator wants to set PacerConfig to $pacerConfig" }
        }
        update.probeClusterConfigs.let { configs ->
            if (configs.isNotEmpty()) {
                logger.debug { "GoogleCcEstimator creating ${configs.size} ProbeClusterConfigs: $configs" }
                configs.forEach { config ->
                    bitrateProber.createProbeCluster(config)
                }
                maybeScheduleProbing(clock.instant())
            }
        }

        if (timeSeriesLogger.isTraceEnabled && update.isNotEmpty()) {
            val now = update.atTime ?: clock.instant()
            val stats = networkController.getStatistics(now)
            val statsPoint = diagnosticContext.makeTimeSeriesPoint(
                if (stats is GoogCcNetworkController.StatisticsSnapshot) "goog_cc_stats" else "scream_stats",
                now
            )
            stats.addToTimeSeriesPoint(statsPoint)
            timeSeriesLogger.trace(statsPoint)

            val updatePoint = diagnosticContext.makeTimeSeriesPoint("goog_cc_update", now)
            update.addToTimeSeriesPoint(updatePoint)
            timeSeriesLogger.trace(updatePoint)
        }

        listenerFailure?.let { throw it }
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

            probeTask = scheduledExecutor.schedule({
                probeExceptionLog.loggingExceptions { sendProbe() }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** Sends a probe for the current cluster and schedules the next probe. */
    @Synchronized
    private fun sendProbe() {
        probeTask = null
        val now = clock.instant()
        val cluster = bitrateProber.currentCluster(now) ?: PacedPacketInfo()
        if (cluster.probeClusterId == kNotAProbe) {
            return
        }
        val probeSize = bitrateProber.recommendedMinProbeSize()
        val probeSent = sendProbing(probeSize, cluster)
        if (probeSent <= 0) {
            // BitrateProber.probeSent requires a nonzero size. WebRTC's pacer does not call it when nothing was
            // sent. The next call to mediaPacketTagged schedules the next probe.
            return
        }
        bitrateProber.probeSent(now, probeSent.bytes)
        maybeScheduleProbing(now)
    }

    companion object {
        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(GoogCcTransportCcEngine::class.java)

        /* Default config settings to use when this version of the Google transport CC estimator engine is used. */
        val defaultRateTrackerWindowSize: Duration by config {
            "jmt.bwe.estimator.GoogleCc2.default-window-size".from(JitsiConfig.newConfig)
        }
        val defaultRateTrackerBucketSize: Duration by config {
            "jmt.bwe.estimator.GoogleCc2.default-bucket-size".from(JitsiConfig.newConfig)
        }
        val defaultInitialIgnoreBwePeriod: Duration by config {
            "jmt.bwe.estimator.GoogleCc2.default-initial-ignore-bwe-period".from(JitsiConfig.newConfig)
        }
    }

    class StatisticsSnapshot(
        val transportAdapterState: TransportFeedbackAdapter.StatisticsSnapshot,
        val networkControllerState: NetworkControllerStatistics,
        val rfc8888FeedbackNegotiated: Boolean,
        val sendEct1: Boolean
    ) : TransportCcEngine.StatisticsSnapshot() {
        override val unmatchedFeedback: Long get() = transportAdapterState.totalUnmatchedReports

        override fun toJson(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
            put("name", GoogCcTransportCcEngine::class.java.simpleName)
            put("rfc_8888_feedback_negotiated", rfc8888FeedbackNegotiated)
            put("send_ect1", sendEct1)
            set<ObjectNode>("transport_adapter", transportAdapterState.toJson())
            set<ObjectNode>("network_controller", networkControllerState.toJson())
        }
    }
}
