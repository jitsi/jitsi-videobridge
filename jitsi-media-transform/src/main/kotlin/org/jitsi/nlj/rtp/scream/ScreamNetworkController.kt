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
package org.jitsi.nlj.rtp.scream

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.nlj.rtp.bandwidthestimation2.MutableNetworkControlUpdate
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkAvailability
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControlUpdate
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControllerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControllerInterface
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkControllerStatistics
import org.jitsi.nlj.rtp.bandwidthestimation2.NetworkRouteChange
import org.jitsi.nlj.rtp.bandwidthestimation2.PacerConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.ProbeClusterConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.ProcessInterval
import org.jitsi.nlj.rtp.bandwidthestimation2.RemoteBitrateReport
import org.jitsi.nlj.rtp.bandwidthestimation2.RoundTripTimeUpdate
import org.jitsi.nlj.rtp.bandwidthestimation2.SentPacket
import org.jitsi.nlj.rtp.bandwidthestimation2.StreamsConfig
import org.jitsi.nlj.rtp.bandwidthestimation2.TargetRateConstraints
import org.jitsi.nlj.rtp.bandwidthestimation2.TargetTransferRate
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportLossReport
import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.min
import org.jitsi.utils.NEVER
import org.jitsi.utils.isFinite
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.ms
import org.jitsi.utils.toDouble
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** Scream network controller,
 * based on WebRTC modules/congestion_controller/scream/scream_network_controller.{h,cc} in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * @param params In WebRTC these come from the field trials in the environment of the configuration.
 */
class ScreamNetworkController(
    config: NetworkControllerConfig,
    private val params: ScreamV2Parameters = ScreamV2Parameters()
) : NetworkControllerInterface {
    private val logger = createChildLogger(config.parentLogger)
    private val clock = config.clock

    private val defaultPacingWindow: Duration = config.defaultPacingTimeWindow
    private val allowInitialBweBeforeMedia: Boolean = config.streamBasedConfig.enableRepeatedInitialProbing
    private var firstUpdateCreated = false
    private var networkAvailable = false
    private var currentPacingWindow: Duration = config.defaultPacingTimeWindow

    private var scream = ScreamV2(logger, params)

    private var minTargetRate: Bandwidth = config.constraints.minDataRate ?: Bandwidth.ZERO
    private var maxTargetRate: Bandwidth = config.constraints.maxDataRate ?: Bandwidth.INFINITY
    private var startingRate: Bandwidth = config.constraints.startingRate ?: kDefaultStartRate
    private var remoteBitrateReport: Bandwidth? = null

    private var streamsConfig: StreamsConfig = config.streamBasedConfig
    private var maxSeenTotalAllocatedBitrate: Bandwidth =
        config.streamBasedConfig.maxTotalAllocatedBitrate ?: Bandwidth.ZERO

    private var initialBweProbeEndTime: Instant = NEVER
    private var paddingIntervalEndTime: Instant = NEVER

    private var pacerQueueSize = DataSize.ZERO
    private var dataInFlight = DataSize.ZERO

    // Values last reported in a NetworkControlUpdate. Used for finding out if an
    // update needs to be reported.
    private var reportedTargetRate = Bandwidth.ZERO
    private var reportedPaddingRate = Bandwidth.ZERO
    private var reportedPacingRate = Bandwidth.ZERO
    private var reportedIsBandwidthLimited = true
    private var reportedCwndReduceRatio = 0.0
    private var encoderPausedDueToCongestion = false

    init {
        updateScreamTargetBitrateConstraints()
    }

    private fun updateScreamTargetBitrateConstraints() {
        // TODO: bugs.webrtc.org/447037083 - We should also consider remote network
        // state estimates.
        scream.setTargetBitrateConstraints(
            minTargetRate,
            min(maxTargetRate, remoteBitrateReport ?: Bandwidth.INFINITY),
            startingRate
        )
    }

    private fun createFirstUpdate(now: Instant): NetworkControlUpdate {
        check(networkAvailable)
        check(!firstUpdateCreated)
        firstUpdateCreated = true
        paddingIntervalEndTime = NEVER
        if (allowInitialBweBeforeMedia) {
            initialBweProbeEndTime = now.plusFinite(params.initialProbingDuration)
        }
        val update = createUpdate(now)

        if (allowInitialBweBeforeMedia) {
            // Creating a probe packet allows padding packets to be sent. So this is
            // only used for triggering padding.
            update.probeClusterConfigs.add(
                ProbeClusterConfig(
                    atTime = now,
                    targetDataRate = 50.kbps,
                    targetDuration = 1.ms,
                    minProbeDelta = 10.ms,
                    // Use two probe packets even though one should be enough. This is a
                    // workaround needed because the pacer will not generate or send padding
                    // packets until after two probing packets.
                    targetProbeCount = 2
                )
            )
        }
        return update
    }

    override fun onNetworkAvailability(msg: NetworkAvailability): NetworkControlUpdate {
        networkAvailable = msg.networkAvailable
        if (!firstUpdateCreated && networkAvailable) {
            return createFirstUpdate(msg.atTime)
        }
        return NetworkControlUpdate()
    }

    override fun onNetworkRouteChange(msg: NetworkRouteChange): NetworkControlUpdate {
        logger.info(" OnNetworkRouteChange, resetting ScreamV2.")
        minTargetRate = msg.constraints.minDataRate ?: minTargetRate
        maxTargetRate = msg.constraints.maxDataRate ?: maxTargetRate
        startingRate = msg.constraints.startingRate ?: startingRate
        if (!msg.restartBwe) {
            startingRate = scream.targetRate()
        } else if (params.safeResetOnRouteChange) {
            startingRate = min(scream.targetRate(), startingRate)
        }
        startingRate = clamp(startingRate, minTargetRate, maxTargetRate)

        scream = ScreamV2(logger, params)
        firstUpdateCreated = false
        encoderPausedDueToCongestion = false
        reportedTargetRate = Bandwidth.ZERO
        updateScreamTargetBitrateConstraints()
        if (networkAvailable && (streamsConfig.maxTotalAllocatedBitrate ?: Bandwidth.ZERO) > Bandwidth.ZERO) {
            return createFirstUpdate(msg.atTime)
        }
        return NetworkControlUpdate()
    }

    override fun onProcessInterval(msg: ProcessInterval): NetworkControlUpdate {
        if (msg.pacerQueue != null) {
            pacerQueueSize = msg.pacerQueue
        }
        return createUpdate(msg.atTime)
    }

    override fun onRemoteBitrateReport(msg: RemoteBitrateReport): NetworkControlUpdate {
        remoteBitrateReport = msg.bandwidth
        updateScreamTargetBitrateConstraints()
        return createUpdate(msg.receiveTime)
    }

    override fun onRoundTripTimeUpdate(msg: RoundTripTimeUpdate): NetworkControlUpdate {
        // Scream uses Smoothed RTT from TransportFeedback.
        return NetworkControlUpdate()
    }

    override fun onSentPacket(sentPacket: SentPacket): NetworkControlUpdate {
        scream.onPacketSent(sentPacket.dataInFlight)
        dataInFlight = sentPacket.dataInFlight
        if (sentPacket.dataInFlight > scream.maxDataInFlight() ||
            scream.delayBasedCongestionControl().isQueueDelayDetected()
        ) {
            return createUpdate(sentPacket.sendTime)
        }
        return NetworkControlUpdate()
    }

    // Omitted: OnReceivedPacket: Scream does not have to know about received packets.

    override fun onStreamsConfig(msg: StreamsConfig): NetworkControlUpdate {
        if (msg.maxTotalAllocatedBitrate != null) {
            logger.trace { "OnStreamsConfig: max_total_allocated_bitrate=${msg.maxTotalAllocatedBitrate}" }
        }
        streamsConfig = msg
        maxSeenTotalAllocatedBitrate = max(
            maxSeenTotalAllocatedBitrate,
            streamsConfig.maxTotalAllocatedBitrate ?: Bandwidth.ZERO
        )
        if (!firstUpdateCreated && networkAvailable &&
            (streamsConfig.maxTotalAllocatedBitrate ?: Bandwidth.ZERO) > Bandwidth.ZERO
        ) {
            return createFirstUpdate(msg.atTime)
        }
        if (firstUpdateCreated) {
            return createUpdate(msg.atTime)
        }
        return NetworkControlUpdate()
    }

    override fun onTargetRateConstraints(constraints: TargetRateConstraints): NetworkControlUpdate {
        minTargetRate = constraints.minDataRate ?: minTargetRate
        maxTargetRate = constraints.maxDataRate ?: maxTargetRate
        startingRate = constraints.startingRate ?: startingRate
        updateScreamTargetBitrateConstraints()
        // No need to change target rate immediately. Wait until next feedback.
        return NetworkControlUpdate()
    }

    override fun onTransportLossReport(msg: TransportLossReport): NetworkControlUpdate = NetworkControlUpdate()

    // Omitted: OnNetworkStateEstimate: Not used

    override fun onTransportPacketsFeedback(report: TransportPacketsFeedback): NetworkControlUpdate {
        scream.onTransportPacketsFeedback(report)
        dataInFlight = report.dataInFlight
        return createUpdate(report.feedbackTime)
    }

    override fun supportsEcnAdaptation(): Boolean = true

    private fun getPacingRate(): Bandwidth {
        val minPacingRate = (streamsConfig.minTotalAllocatedBitrate ?: Bandwidth.ZERO).scaledBy(params.pacingFactor)
        return max(scream.pacingRate(), minPacingRate)
    }

    // Calculates a ratio in [0.0, 1.0] indicating how much the video encoder
    // should reduce its target bitrate (pushback) due to network or pacer queue
    // build-up. Returns 1.0 if data in flight exceeds max_data_in_flight.
    // Otherwise, if pacer queue delay exceeds min_pacing_delay_for_pushback,
    // the ratio scales linearly up to 1.0 at max_pacing_delay_for_pushback.
    private fun calculateCwndReduceRatio(): Double {
        var cwndReduceRatio = 0.0
        val pacingRate = getPacingRate()
        if (pacingRate > Bandwidth.ZERO && pacerQueueSize != DataSize.ZERO) {
            val pacingDelay = timeToSend(pacerQueueSize, pacingRate)
            val minDelay = params.minPacingDelayForPushback
            val maxDelay = params.maxPacingDelayForPushback
            if (maxDelay > minDelay) {
                val ratio = (pacingDelay - minDelay).ratioTo(maxDelay - minDelay)
                cwndReduceRatio += ratio.coerceIn(0.0, 1.0)
            }
        }
        return cwndReduceRatio.coerceIn(0.0, 1.0)
    }

    private fun createUpdate(now: Instant): MutableNetworkControlUpdate {
        val update = MutableNetworkControlUpdate()
        val isBandwidthLimited = !scream.isApplicationLimited()
        val cwndReduceRatio = calculateCwndReduceRatio()

        val pacingRate = getPacingRate()
        val pacingDelay = if (pacingRate > Bandwidth.ZERO) timeToSend(pacerQueueSize, pacingRate) else Duration.ZERO
        val isCongested = dataInFlight > scream.maxDataInFlight()

        if (isCongested && pacingDelay > params.maxPacingDelayForPushback) {
            encoderPausedDueToCongestion = true
        } else if (!isCongested && pacingDelay < params.minPacingDelayForPushback) {
            encoderPausedDueToCongestion = false
        }

        val targetRate = if (encoderPausedDueToCongestion) Bandwidth.ZERO else scream.targetRate()

        if (targetRate != reportedTargetRate ||
            isBandwidthLimited != reportedIsBandwidthLimited ||
            abs(cwndReduceRatio - reportedCwndReduceRatio) > 0.1
        ) {
            reportedTargetRate = targetRate
            reportedIsBandwidthLimited = isBandwidthLimited
            reportedCwndReduceRatio = cwndReduceRatio
            val targetRateMsg = TargetTransferRate()
            targetRateMsg.atTime = now
            targetRateMsg.targetRate = targetRate
            targetRateMsg.cwndReduceRatio = cwndReduceRatio
            targetRateMsg.networkEstimate.atTime = now
            targetRateMsg.networkEstimate.roundTripTime = scream.rtt()
            targetRateMsg.isBandwidthLimited = isBandwidthLimited
            update.targetRate = targetRateMsg
        }
        update.pacerConfig = maybeCreatePacerConfig(now)
        update.congestionWindow = scream.maxDataInFlight()
        return update
    }

    private fun maybeCreatePacerConfig(now: Instant): PacerConfig? {
        // Allow sending packets in larger bursts if some time has passed since last
        // congestion event.
        val pacingWindow =
            if (timeBetween(scream.lastReactionToCongestionTime(), clock.instant()) >
                params.allowLargePacingBurstsAfterCongestionTime
            ) {
                defaultPacingWindow
            } else {
                10.ms
            }

        var allowPadding = false
        if (params.timeBetweenPeriodicPadding.isFinite() &&
            timeBetween(scream.lastReferenceWindowDecreaseTime(), now) > params.allowPaddingAfterLastCongestionTime
        ) {
            if (now < paddingIntervalEndTime) {
                // We are currently inside an active padding duration.
                allowPadding = true
            } else if (timeBetween(paddingIntervalEndTime, now) >= params.timeBetweenPeriodicPadding) {
                // Enough time has passed since the end of the last padding interval;
                // start a new one.
                paddingIntervalEndTime = now.plusFinite(params.periodicPaddingDuration)
                allowPadding = true
            }
        } else if (now < paddingIntervalEndTime) {
            // Stop padding immediately if a congestion event occurred recently.
            paddingIntervalEndTime = now
        }

        var paddingRate = Bandwidth.ZERO
        if (allowPadding) {
            // Padding is allowed.
            var maxPaddingRate = minOf(
                maxTargetRate,
                maxSeenTotalAllocatedBitrate,
                (streamsConfig.maxTotalAllocatedBitrate ?: Bandwidth.ZERO) * 2,
                remoteBitrateReport ?: Bandwidth.INFINITY
            )
            if (maxPaddingRate == Bandwidth.ZERO && now < initialBweProbeEndTime) {
                // If initial BWE probing is allowed, probe up to the max target rate.
                maxPaddingRate = maxTargetRate
            }
            val targetRate = scream.targetRate()
            paddingRate = if (targetRate < maxPaddingRate) targetRate else Bandwidth.ZERO
        }

        val pacingRate = getPacingRate()
        if (paddingRate != reportedPaddingRate ||
            pacingRate != reportedPacingRate ||
            currentPacingWindow != pacingWindow
        ) {
            if (currentPacingWindow != pacingWindow) {
                logger.trace { "Pacing window changed: $pacingWindow" }
            }
            reportedPaddingRate = paddingRate
            reportedPacingRate = pacingRate
            currentPacingWindow = pacingWindow

            return PacerConfig.create(now, pacingRate, paddingRate, currentPacingWindow)
        }
        return null
    }

    /** Jitsi local addition. */
    class StatisticsSnapshot(
        val time: Instant,
        val target: Bandwidth,
        val pacing: Bandwidth,
        val padding: Bandwidth,
        val rtt: Duration,
        val refWindow: DataSize,
        val refWindowI: DataSize,
        val maxDataInFlight: DataSize,
        val dataInFlight: DataSize,
        val l4sAlpha: Double,
        val lossCongestionLevel: Double,
        val queueDelay: Duration,
        val queueDelayMinAvg: Duration,
        val latencyDifferenceAvg: Duration,
        val feedbackHoldTime: Duration,
        val receivedRate: Bandwidth,
        val isApplicationLimited: Boolean
    ) : NetworkControllerStatistics {
        override fun toJson(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
            put("name", ScreamNetworkController::class.java.simpleName)
            put("time", time.toEpochMilli())
            put("target", target.bps)
            put("pacing", pacing.bps)
            put("padding", padding.bps)
            put("rtt", rtt.toDouble())
            put("ref_window", refWindow.bytes)
            put("ref_window_i", refWindowI.bytes)
            put("max_data_in_flight", maxDataInFlight.bytes)
            put("data_in_flight", dataInFlight.bytes)
            put("l4s_alpha", l4sAlpha)
            put("loss_congestion_level", lossCongestionLevel)
            put("queue_delay", if (queueDelay.isFinite()) queueDelay.toDouble() else Double.NaN)
            put("queue_delay_min_avg", queueDelayMinAvg.toDouble())
            put("latency_difference_avg", latencyDifferenceAvg.toDouble())
            put("feedback_hold_time", feedbackHoldTime.toDouble())
            put("received_rate", if (receivedRate.isFinite()) receivedRate.bps.toDouble() else Double.NaN)
            put("is_application_limited", isApplicationLimited)
        }

        override fun addToTimeSeriesPoint(point: DiagnosticContext.TimeSeriesPoint) {
            point.apply {
                addField("target", target.bps)
                addField("pacing", pacing.bps)
                addField("padding", padding.bps)
                addField("rtt", rtt.toDouble())
                addField("ref_window", refWindow.bytes)
                addField("ref_window_i", refWindowI.bytes)
                addField("max_data_in_flight", maxDataInFlight.bytes)
                addField("data_in_flight", dataInFlight.bytes)
                addField("l4s_alpha", l4sAlpha)
                addField("loss_congestion_level", lossCongestionLevel)
                addField("queue_delay", if (queueDelay.isFinite()) queueDelay.toDouble() else Double.NaN)
                addField("queue_delay_min_avg", queueDelayMinAvg.toDouble())
                addField("latency_difference_avg", latencyDifferenceAvg.toDouble())
                addField("feedback_hold_time", feedbackHoldTime.toDouble())
                addField("received_rate", if (receivedRate.isFinite()) receivedRate.bps.toDouble() else Double.NaN)
                addField("is_application_limited", isApplicationLimited)
            }
        }
    }

    override fun getStatistics(now: Instant): StatisticsSnapshot = StatisticsSnapshot(
        time = now,
        target = scream.targetRate(),
        pacing = getPacingRate(),
        padding = reportedPaddingRate,
        rtt = scream.rtt(),
        refWindow = scream.refWindow(),
        refWindowI = scream.refWindowI() ?: DataSize.ZERO,
        maxDataInFlight = scream.maxDataInFlight(),
        dataInFlight = dataInFlight,
        l4sAlpha = scream.l4sAlpha(),
        lossCongestionLevel = scream.lossCongestionLevel(),
        queueDelay = scream.delayBasedCongestionControl().queueDelay(),
        queueDelayMinAvg = scream.delayBasedCongestionControl().queueDelayMinAvg(),
        latencyDifferenceAvg = scream.delayBasedCongestionControl().latencyDifferenceAvg(),
        feedbackHoldTime = scream.feedbackHoldTime(),
        receivedRate = scream.receivedRate(),
        isApplicationLimited = scream.isApplicationLimited()
    )

    companion object {
        private val kDefaultStartRate = 300.kbps
    }
}
