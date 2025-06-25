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

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.min
import org.jitsi.nlj.util.times
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.MIN_DURATION
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.isFinite
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.max
import org.jitsi.utils.min
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.toDouble
import org.jitsi.utils.toRoundedMillis
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong

/** Top-level Google CC Network Controller,
 * based on WebRTC modules/congestion_controller/goog_cc/goog_cc_network_control.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings,
 * except where needed by unit tests.
 */
class GoogCcConfig(
    val feedbackOnly: Boolean = false,
    /* Not in this object in WebRTC: This is a field trial parameter there */
    val rateControlSettings: CongestionWindowConfig = CongestionWindowConfig()
)

class GoogCcNetworkController(
    config: NetworkControllerConfig,
    googCcConfig: GoogCcConfig
) : NetworkControllerInterface {
    private val logger = config.parentLogger.createChildLogger(javaClass.name)
    private val diagnosticContext = config.diagnosticContext

    override fun onNetworkAvailability(msg: NetworkAvailability): NetworkControlUpdate {
        return NetworkControlUpdate(
            probeClusterConfigs = probeController.onNetworkAvailability(msg)
        )
    }

    override fun onNetworkRouteChange(msg: NetworkRouteChange): NetworkControlUpdate {
        if (safeResetOnRouteChange) {
            var estimatedBitrate: Bandwidth?
            if (safeResetAcknowledgedRate) {
                estimatedBitrate = acknowledgedBitrateEstimator.bitrate()
                if (estimatedBitrate == null) {
                    estimatedBitrate = acknowledgedBitrateEstimator.peekRate()
                }
            } else {
                estimatedBitrate = bandwidthEstimation.targetRate()
            }
            if (estimatedBitrate != null) {
                if (msg.constraints.startingRate != null) {
                    msg.constraints.startingRate = min(msg.constraints.startingRate!!, estimatedBitrate)
                } else {
                    msg.constraints.startingRate = estimatedBitrate
                }
            }
        }

        acknowledgedBitrateEstimator = AcknowledgedBitrateEstimatorInterface.create()
        probeBitrateEstimator = ProbeBitrateEstimator(logger, diagnosticContext)
        delayBasedBwe = DelayBasedBwe(logger, diagnosticContext)

        bandwidthEstimation.onRouteChange()
        probeController.reset(msg.atTime)
        val update = MutableNetworkControlUpdate(
            probeClusterConfigs = resetConstraints(msg.constraints)
        )
        maybeTriggerOnNetworkChanged(update, msg.atTime)

        return update
    }

    override fun onProcessInterval(msg: ProcessInterval): NetworkControlUpdate {
        val update = MutableNetworkControlUpdate()
        if (initialConfig != null) {
            update.probeClusterConfigs = resetConstraints(initialConfig!!.constraints)
            update.pacerConfig = getPacingRates(msg.atTime)

            if (initialConfig!!.streamBasedConfig.requestsAlrProbing != null) {
                probeController.enablePeriodicAlrProbing(initialConfig!!.streamBasedConfig.requestsAlrProbing!!)
            }
            if (initialConfig!!.streamBasedConfig.enableRepeatedInitialProbing != null) {
                probeController.enableRepeatedInitialProbing(
                    initialConfig!!.streamBasedConfig.enableRepeatedInitialProbing!!
                )
            }
            val totalBitrate = initialConfig!!.streamBasedConfig.maxTotalAllocatedBitrate
            if (totalBitrate != null) {
                val probes = probeController.onMaxTotalAllocatedBitrate(totalBitrate, msg.atTime)
                update.probeClusterConfigs.addAll(probes)
            }
            initialConfig = null
        }
        if (congestionWindowPushbackController != null && msg.pacerQueue != null) {
            congestionWindowPushbackController.updatePacingQueue(msg.pacerQueue.bytes.roundToLong())
        }
        bandwidthEstimation.updateEstimate(msg.atTime)
        val startTimeMs = alrDetector.getApplicationLimitedRegionStartTime()
        probeController.setAlrStartTimeMs(startTimeMs)

        val probes = probeController.process(msg.atTime)
        update.probeClusterConfigs.addAll(probes)

        if (rateControlSettings.useCongestionWindow() &&
            !feedbackMaxRtts.isEmpty()
        ) {
            updateCongestionWindowSize()
        }
        if (congestionWindowPushbackController != null && currentDataWindow != null) {
            congestionWindowPushbackController.setDataWindow(currentDataWindow!!)
        } else {
            update.congestionWindow = currentDataWindow
        }
        maybeTriggerOnNetworkChanged(update, msg.atTime)
        return update
    }

    override fun onRemoteBitrateReport(msg: RemoteBitrateReport): NetworkControlUpdate {
        if (packetFeedbackOnly) {
            logger.error("Received REMB for packet feedback only GoogCC")
            return NetworkControlUpdate()
        }
        bandwidthEstimation.updateReceiverEstimate(msg.receiveTime, msg.bandwidth)

        timeSeriesLogger.trace {
            diagnosticContext.makeTimeSeriesPoint("REMB_BW", msg.receiveTime)
                .addField("REMB_kbps", msg.bandwidth.kbps)
        }
        return NetworkControlUpdate()
    }

    override fun onRoundTripTimeUpdate(msg: RoundTripTimeUpdate): NetworkControlUpdate {
        if (packetFeedbackOnly || msg.smoothed) {
            return NetworkControlUpdate()
        }
        assert(msg.roundTripTime != Duration.ZERO)
        delayBasedBwe.onRttUpdate(msg.roundTripTime)
        bandwidthEstimation.updateRtt(msg.roundTripTime, msg.receiveTime)
        return NetworkControlUpdate()
    }

    override fun onSentPacket(sentPacket: SentPacket): NetworkControlUpdate {
        alrDetector.onBytesSent(sentPacket.size.bytes.roundToLong(), sentPacket.sendTime.toEpochMilli())
        acknowledgedBitrateEstimator.setAlr(alrDetector.getApplicationLimitedRegionStartTime() != null)

        if (!firstPacketSent) {
            firstPacketSent = true
            // Initialize feedback time to send time to allow estimation of RTT until
            // first feedback is received.
            bandwidthEstimation.updatePropagationRtt(sentPacket.sendTime, Duration.ZERO)
        }
        bandwidthEstimation.onSentPacket(sentPacket)

        if (congestionWindowPushbackController != null) {
            congestionWindowPushbackController.updateOutstandingData(sentPacket.dataInFlight.bytes.roundToLong())
            val update = MutableNetworkControlUpdate()
            maybeTriggerOnNetworkChanged(update, sentPacket.sendTime)
            return update
        } else {
            return NetworkControlUpdate()
        }
    }

    override fun onStreamsConfig(msg: StreamsConfig): NetworkControlUpdate {
        val update = MutableNetworkControlUpdate()
        if (msg.requestsAlrProbing != null) {
            probeController.enablePeriodicAlrProbing(msg.requestsAlrProbing)
        }
        if (msg.maxTotalAllocatedBitrate != null) {
            update.probeClusterConfigs =
                probeController.onMaxTotalAllocatedBitrate(msg.maxTotalAllocatedBitrate, msg.atTime)
        }

        var pacingChanged = false
        if (msg.pacingFactor != null && msg.pacingFactor != pacingFactor) {
            pacingFactor = msg.pacingFactor
            pacingChanged = true
        }
        if (msg.minTotalAllocatedBitrate != null &&
            minTotalAllocatedBitrate != msg.minTotalAllocatedBitrate
        ) {
            minTotalAllocatedBitrate = msg.minTotalAllocatedBitrate
            pacingChanged = true

            if (useMinAllocatableAsLowerBound) {
                clampConstraints()
                delayBasedBwe.setMinBitrate(minDataRate)
                bandwidthEstimation.setMinMaxBitrate(minDataRate, maxDataRate)
            }
        }
        if (msg.maxPaddingRate != null && msg.maxPaddingRate != maxPaddingRate) {
            maxPaddingRate = msg.maxPaddingRate
            pacingChanged = true
        }

        if (pacingChanged) {
            update.pacerConfig = getPacingRates(msg.atTime)
        }
        return update
    }

    override fun onTargetRateConstraints(constraints: TargetRateConstraints): NetworkControlUpdate {
        val update = MutableNetworkControlUpdate()
        update.probeClusterConfigs = resetConstraints(constraints)
        maybeTriggerOnNetworkChanged(update, constraints.atTime)
        return update
    }

    private fun clampConstraints() {
        // TODO(holmer): We should make sure the default bitrates are set to 10 kbps,
        // and that we don't try to set the min bitrate to 0 from any applications.
        // The congestion controller should allow a min bitrate of 0.
        minDataRate = max(minTargetRate, kCongestionControllerMinBitrate)
        if (useMinAllocatableAsLowerBound) {
            minDataRate = max(minDataRate, minTotalAllocatedBitrate)
        }
        if (maxDataRate < minDataRate) {
            logger.warn("max bitrate $maxDataRate smaller than min bitrate $minDataRate")
            maxDataRate = minDataRate
        }
        if (startingRate != null && startingRate!! < minDataRate) {
            logger.warn("start bitrate ${startingRate!!} smaller than min bitrate $minDataRate")
            startingRate = minDataRate
        }
    }

    private fun resetConstraints(newConstraints: TargetRateConstraints): MutableList<ProbeClusterConfig> {
        minTargetRate = newConstraints.minDataRate ?: Bandwidth.ZERO
        maxDataRate = newConstraints.maxDataRate ?: Bandwidth.INFINITY
        startingRate = newConstraints.startingRate
        clampConstraints()

        bandwidthEstimation.setBitrates(startingRate, minDataRate, maxDataRate, newConstraints.atTime)
        if (startingRate != null) {
            delayBasedBwe.setStartBitrate(startingRate!!)
        }
        delayBasedBwe.setMinBitrate(minDataRate)

        return probeController.setBitrates(
            minDataRate,
            startingRate ?: Bandwidth.ZERO,
            maxDataRate,
            newConstraints.atTime
        )
    }

    override fun onTransportLossReport(msg: TransportLossReport): NetworkControlUpdate {
        if (packetFeedbackOnly) {
            return NetworkControlUpdate()
        }
        val totalPacketsDelta = msg.packetsReceivedDelta + msg.packetsLostDelta
        bandwidthEstimation.updatePacketsLost(msg.packetsLostDelta, totalPacketsDelta, msg.receiveTime)
        return NetworkControlUpdate()
    }

    private fun updateCongestionWindowSize() {
        val minFeedbackMaxRtt = feedbackMaxRtts.min().ms

        val kMinCwnd = (2 * 1500).bytes
        val timeWindow = minFeedbackMaxRtt + rateControlSettings.getCongestionWindowAdditionalTimeMs().ms

        var dataWindow = lastLossBasedTargetRate * timeWindow
        if (currentDataWindow != null) {
            dataWindow = max(kMinCwnd, (dataWindow + currentDataWindow!!) / 2.0)
        } else {
            dataWindow = max(kMinCwnd, dataWindow)
        }
        currentDataWindow = dataWindow
    }

    override fun onTransportPacketsFeedback(report: TransportPacketsFeedback): NetworkControlUpdate {
        if (report.packetFeedbacks.isEmpty()) {
            // TODO(bugs.webrtc.org/10125): Design a better mechanism to safe-guard
            // against building very large network queues.
            return NetworkControlUpdate()
        }
        if (congestionWindowPushbackController != null) {
            congestionWindowPushbackController.updateOutstandingData(report.dataInFlight.bytes.roundToLong())
        }
        var maxFeedbackRtt = MIN_DURATION
        var minPropagationRtt = MAX_DURATION
        var maxRecvTime = Instant.MIN

        val feedbacks = report.receivedWithSendInfo()
        for (feedback in feedbacks) {
            maxRecvTime = max(maxRecvTime, feedback.receiveTime)
        }
        for (feedback in feedbacks) {
            val feedbackRtt = Duration.between(feedback.sentPacket.sendTime, report.feedbackTime)
            val minPendingTime = Duration.between(feedback.receiveTime, maxRecvTime)
            val propagationRtt = feedbackRtt - minPendingTime
            maxFeedbackRtt = max(maxFeedbackRtt, feedbackRtt)
            minPropagationRtt = min(minPropagationRtt, propagationRtt)
        }

        if (maxFeedbackRtt.isFinite()) {
            feedbackMaxRtts.add(maxFeedbackRtt.toRoundedMillis())
            val kMaxFeedbackRttWindow = 32
            if (feedbackMaxRtts.size > kMaxFeedbackRttWindow) {
                feedbackMaxRtts.removeFirst()
            }
            // TODO(srte): Use time since last unacknowledged packet.
            bandwidthEstimation.updatePropagationRtt(report.feedbackTime, minPropagationRtt)
        }
        if (packetFeedbackOnly) {
            if (!feedbackMaxRtts.isEmpty()) {
                val sumRttMs = feedbackMaxRtts.sum()
                val meanRttMs = sumRttMs / feedbackMaxRtts.size
                if (delayBasedBwe != null) {
                    delayBasedBwe.onRttUpdate(meanRttMs.ms)
                }
            }

            var feedbackMinRtt = MAX_DURATION
            for (packetFeedback in feedbacks) {
                val pendingTime = Duration.between(packetFeedback.receiveTime, maxRecvTime)
                val rtt = Duration.between(packetFeedback.sentPacket.sendTime, report.feedbackTime) - pendingTime
                // Value used for predicting NACK round trip time in FEC controller.
                feedbackMinRtt = min(rtt, feedbackMinRtt)
            }
            if (feedbackMinRtt.isFinite()) {
                bandwidthEstimation.updateRtt(feedbackMinRtt, report.feedbackTime)
            }

            expectedPacketsSinceLastLossUpdate += report.packetsWithFeedback().size
            for (packetFeedback in report.packetsWithFeedback()) {
                if (!packetFeedback.isReceived()) {
                    lostPacketsSinceLastLossUpdate += 1
                }
            }
            if (report.feedbackTime > nextLossUpdate) {
                nextLossUpdate = report.feedbackTime + kLossUpdateInterval
                bandwidthEstimation.updatePacketsLost(
                    lostPacketsSinceLastLossUpdate.toLong(),
                    expectedPacketsSinceLastLossUpdate.toLong(),
                    report.feedbackTime
                )
                expectedPacketsSinceLastLossUpdate = 0
                lostPacketsSinceLastLossUpdate = 0
            }
        }
        val alrStartTime = alrDetector.getApplicationLimitedRegionStartTime()

        if (previouslyInAlr && alrStartTime == null) {
            val nowMs = report.feedbackTime.toEpochMilli()
            acknowledgedBitrateEstimator.setAlrEndedTime(report.feedbackTime)
            probeController.setAlrEndedTimeMs(nowMs)
        }
        previouslyInAlr = alrStartTime != null
        acknowledgedBitrateEstimator.incomingPacketFeedbackVector(report.sortedByReceiveTime())
        val acknowledgedBitrate = acknowledgedBitrateEstimator.bitrate()
        bandwidthEstimation.setAcknowledgedRate(acknowledgedBitrate, report.feedbackTime)
        for (feedback in report.sortedByReceiveTime()) {
            if (feedback.sentPacket.pacingInfo.probeClusterId != PacedPacketInfo.kNotAProbe) {
                probeBitrateEstimator.handleProbeAndEstimateBitrate(feedback)
            }
        }

        /* Skipped network_estimator code */

        var probeBitrate = probeBitrateEstimator.fetchAndResetLastEstimatedBitrate()

        /* Skipped network_estimator code */

        if (limitProbesLowerThanThroughputEstimate && probeBitrate != null && acknowledgedBitrate != null) {
            // Limit the backoff to something slightly below the acknowledged
            // bitrate. ("Slightly below" because we want to drain the queues
            // if we are actually overusing.)
            // The acknowledged bitrate shouldn't normally be higher than the delay
            // based estimate, but it could happen e.g. due to packet bursts or
            // encoder overshoot. We use std::min to ensure that a probe result
            // below the current BWE never causes an increase.
            val limit = min(delayBasedBwe.lastEstimate(), acknowledgedBitrate * kProbeDropThroughputFraction)
            probeBitrate = max(probeBitrate, limit)
        }

        val update = MutableNetworkControlUpdate()
        var recoveredFromOveruse = false

        val result = delayBasedBwe.incomingPacketFeedbackVector(
            report,
            acknowledgedBitrate,
            probeBitrate,
            alrStartTime != null
        )

        if (result.updated) {
            if (result.probe) {
                bandwidthEstimation.setSendBitrate(result.targetBitrate, report.feedbackTime)
            }
            // Since SetSendBitrate now resets the delay-based estimate, we have to
            // call UpdateDelayBasedEstimate after SetSendBitrate.
            bandwidthEstimation.updateDelayBasedEstimate(report.feedbackTime, result.targetBitrate)
        }
        bandwidthEstimation.updateLossBasedEstimator(
            report,
            result.delayDetectorState,
            probeBitrate,
            alrStartTime != null
        )
        if (result.updated) {
            // Update the estimate in the ProbeController, in case we want to probe.
            maybeTriggerOnNetworkChanged(update, report.feedbackTime)
        }

        recoveredFromOveruse = result.recoveredFromOveruse

        if (recoveredFromOveruse) {
            probeController.setAlrStartTimeMs(alrStartTime)
            val probes = probeController.requestProbe(report.feedbackTime)
            update.probeClusterConfigs.addAll(probes)
        }

        // No valid RTT could be because send-side BWE isn't used, in which case
        // we don't try to limit the outstanding packets.
        if (rateControlSettings.useCongestionWindow() && maxFeedbackRtt.isFinite()) {
            updateCongestionWindowSize()
        }
        if (congestionWindowPushbackController != null && currentDataWindow != null) {
            congestionWindowPushbackController.setDataWindow(currentDataWindow!!)
        } else {
            update.congestionWindow = currentDataWindow
        }

        return update
    }

    fun getNetworkState(atTime: Instant): NetworkControlUpdate {
        val update = MutableNetworkControlUpdate()
        update.targetRate = TargetTransferRate().apply {
            networkEstimate.atTime = atTime
            networkEstimate.lossRateRatio = (lastEstimatedFractionLoss ?: 0u).toFloat() / 255.0f
            networkEstimate.roundTripTime = lastEstimatedRoundTripTime
            networkEstimate.bwePeriod = delayBasedBwe.getExpectedBwePeriod()

            this.atTime = atTime
            if (rateControlSettings.useCongestionWindowDropFrameOnly()) {
                targetRate = lastLossBasedTargetRate
            } else {
                targetRate = lastPushbackTargetRate
            }

            targetRate = lastPushbackTargetRate
            stableTargetRate = bandwidthEstimation.getEstimatedLinkCapacity()
        }
        update.pacerConfig = getPacingRates(atTime)
        update.congestionWindow = currentDataWindow
        return update
    }

    private fun maybeTriggerOnNetworkChanged(update: MutableNetworkControlUpdate, atTime: Instant) {
        val fractionLoss = bandwidthEstimation.fractionLoss()
        val roundTripTime = bandwidthEstimation.roundTripTime()
        val lossBasedTargetRate = bandwidthEstimation.targetRate()
        val lossBasedState = bandwidthEstimation.lossBasedState()
        var pushbackTargetRate = lossBasedTargetRate

        /* TODO: plotting */

        var cwndReduceRatio = 0.0
        if (congestionWindowPushbackController != null) {
            var pushbackRate = congestionWindowPushbackController.updateTargetBitrate(lossBasedTargetRate.bps.toInt())
            pushbackRate = Math.max(bandwidthEstimation.getMinBitrate(), pushbackRate)
            pushbackTargetRate = pushbackRate.bps
            if (rateControlSettings.useCongestionWindowDropFrameOnly()) {
                cwndReduceRatio = (lossBasedTargetRate.bps - pushbackTargetRate.bps).toDouble() /
                    lossBasedTargetRate.bps
            }
        }
        var stableTargetRate = bandwidthEstimation.getEstimatedLinkCapacity()
        stableTargetRate = min(stableTargetRate, pushbackTargetRate)

        if (lossBasedTargetRate != lastLossBasedTargetRate ||
            lossBasedState != lastLossBasedState ||
            fractionLoss != lastEstimatedFractionLoss ||
            roundTripTime != lastEstimatedRoundTripTime ||
            pushbackTargetRate != lastPushbackTargetRate ||
            stableTargetRate != lastStableTargetRate
        ) {
            lastLossBasedTargetRate = lossBasedTargetRate
            lastPushbackTargetRate = pushbackTargetRate
            lastEstimatedFractionLoss = fractionLoss
            lastEstimatedRoundTripTime = roundTripTime
            lastStableTargetRate = stableTargetRate
            lastLossBasedState = lossBasedState

            alrDetector.setEstimatedBitrate(lossBasedTargetRate.bps.toInt())

            val bwePeriod = delayBasedBwe.getExpectedBwePeriod()

            val targetRateMsg = TargetTransferRate()
            targetRateMsg.atTime = atTime
            if (rateControlSettings.useCongestionWindowDropFrameOnly()) {
                targetRateMsg.targetRate = lossBasedTargetRate
                targetRateMsg.cwndReduceRatio = cwndReduceRatio
            } else {
                targetRateMsg.targetRate = pushbackTargetRate
            }
            targetRateMsg.stableTargetRate = stableTargetRate
            targetRateMsg.networkEstimate.atTime = atTime
            targetRateMsg.networkEstimate.roundTripTime = roundTripTime
            targetRateMsg.networkEstimate.lossRateRatio = fractionLoss.toFloat() / 255.0f
            targetRateMsg.networkEstimate.bwePeriod = bwePeriod

            update.targetRate = targetRateMsg

            val probes = probeController.setEstimatedBitrate(
                lossBasedTargetRate,
                getBandwidthLimitedCause(
                    bandwidthEstimation.lossBasedState(),
                    bandwidthEstimation.isRttAboveLimit(),
                    delayBasedBwe.lastState()
                ),
                atTime
            )
            update.probeClusterConfigs.addAll(probes)
            update.pacerConfig = getPacingRates(atTime)
            logger.debug(
                "bwe $atTime: pushback_target_bps=${lastPushbackTargetRate.bps} " +
                    "estimate_bps=${lossBasedTargetRate.bps}"
            )
        }
    }

    private fun getPacingRates(atTime: Instant): PacerConfig {
        // Pacing rate is based on target rate before congestion window pushback,
        // because we don't want to build queues in the pacer when pushback occurs.
        val pacingRate = max(minTotalAllocatedBitrate, lastLossBasedTargetRate) * pacingFactor
        var paddingRate = if (lastLossBasedState == LossBasedState.kIncreaseUsingPadding) {
            max(maxPaddingRate, lastLossBasedTargetRate)
        } else {
            maxPaddingRate
        }
        paddingRate = min(paddingRate, lastPushbackTargetRate)
        val msg = PacerConfig()
        msg.atTime = atTime
        msg.timeWindow = 1.secs
        msg.dataWindow = pacingRate * msg.timeWindow
        msg.padWindow = paddingRate * msg.timeWindow
        return msg
    }

    /** Jitsi local addition.
     * Fields based on WebRTC modules/congestion_controller/goog_cc/test/goog_cc_printer.{cc,h}
     */
    class StatisticsSnapshot(
        val time: Instant,
        val rtt: Duration,
        val target: Bandwidth,
        val stableTarget: Bandwidth,
        val pacing: Bandwidth?,
        val padding: Bandwidth?,
        val window: DataSize,
        val rateControlState: AimdRateControl.RateControlState,
        val stableEstimate: Bandwidth?,
        val trendline: Double,
        val trendlineModifiedOffset: Double,
        val trendlineOffsetThreshold: Double,
        val acknowledgedRate: Bandwidth?,
        /* Skipped, based on NetworkStateEstimate
         * estCapacity
         * estCapacityDev
         * estCapacityMin
         * estCrossDelay
         * estSpikeDelay
         * estPreBuffer
         * estPostBuffer
         * estPropagation
         */

        val lossRatio: Float,

        /* Fields where data from LossBasedBweV1 are printed, even though LossBasedBweV2 is the default.
         TODO: print state out of LossBasedBweV2.
        val lossAverage: Double,
        val lossAverageMax: Double,
        val lossThresInc: Double,
        val lossThresDec: Double,
        val lossBasedRate: Bandwidth,
        val lossAckRate: Bandwidth,
         */
        /* SendSideBandwidthEstimator populates itself from LossBasedBwe's estimate. */
        val sendSideTarget: Bandwidth,
        val lossBasedState: LossBasedState,
        val dataWindow: DataSize?,
        val pushbackTarget: Bandwidth,
        /* Additions to the fields from goog_cc_printer */
        val inAlr: Boolean
    ) {
        fun toJson(): OrderedJsonObject {
            return OrderedJsonObject().apply {
                put("time", time.toEpochMilli())
                put("rtt", rtt.toDouble())
                put("target", target.bps)
                put("stable_target", stableTarget.bps)
                put("pacing", pacing?.bps ?: Double.NaN)
                put("padding", padding?.bps ?: Double.NaN)
                put("window", window.bytes)
                put("rate_control_state", rateControlState.name)
                put("stable_estimate", stableEstimate?.bps ?: Double.NaN)
                put("trendline", trendline)
                put("trendline_modified_offset", trendlineModifiedOffset)
                put("trendline_modified_threshold", trendlineOffsetThreshold)
                put("acknowledged_rate", acknowledgedRate?.bps ?: Double.NaN)
                put("loss_ratio", lossRatio)
                put("send_side_target", sendSideTarget.bps)
                put("last_loss_based_state", lossBasedState.name)
                put("data_window", dataWindow?.bytes ?: Double.NaN)
                put("pushback_target", pushbackTarget.bps)
                put("in_alr", inAlr)
            }
        }

        fun addToTimeSeriesPoint(point: DiagnosticContext.TimeSeriesPoint) {
            point.apply {
                addField("rtt", rtt.toDouble())
                addField("target", target.bps)
                addField("stable_target", stableTarget.bps)
                addField("pacing", pacing?.bps ?: Double.NaN)
                addField("padding", padding?.bps ?: Double.NaN)
                addField("window", window.bytes)
                addField("rate_control_state", rateControlState.name)
                addField("stable_estimate", stableEstimate?.bps ?: Double.NaN)
                addField("trendline", trendline)
                addField("trendline_modified_offset", trendlineModifiedOffset)
                addField("trendline_modified_threshold", trendlineOffsetThreshold)
                addField("acknowledged_rate", acknowledgedRate?.bps ?: Double.NaN)
                addField("loss_ratio", lossRatio)
                addField("send_side_target", sendSideTarget.bps)
                addField("last_loss_based_state", lossBasedState.name)
                addField("data_window", dataWindow?.bytes ?: Double.NaN)
                addField("pushback_target", pushbackTarget.bps)
                addField("in_alr", inAlr)
            }
        }
    }

    private fun trend() = delayBasedBwe.delayDetector as TrendlineEstimator

    fun getStatistics(now: Instant): StatisticsSnapshot {
        val stateUpdate = getNetworkState(now)
        val target = stateUpdate.targetRate!!
        val pacing = stateUpdate.pacerConfig
        val congestionWindow = stateUpdate.congestionWindow ?: DataSize.INFINITY
        return StatisticsSnapshot(
            time = target.atTime,
            rtt = target.networkEstimate.roundTripTime,
            target = target.targetRate,
            stableTarget = target.stableTargetRate,
            pacing = pacing?.dataRate(),
            padding = pacing?.padRate(),
            window = congestionWindow,
            rateControlState = delayBasedBwe.rateControl.rateControlState,
            stableEstimate = delayBasedBwe.rateControl.linkCapacity.estimate,
            trendline = trend().prevTrend,
            trendlineModifiedOffset = trend().prevModifiedTrend,
            trendlineOffsetThreshold = trend().threshold,
            acknowledgedRate = acknowledgedBitrateEstimator.bitrate(),
            lossRatio = target.networkEstimate.lossRateRatio,
            sendSideTarget = bandwidthEstimation.targetRate(),
            lossBasedState = lastLossBasedState,
            dataWindow = currentDataWindow,
            pushbackTarget = lastPushbackTargetRate,
            inAlr = previouslyInAlr
        )
    }

    private val packetFeedbackOnly = googCcConfig.feedbackOnly
    private val safeResetOnRouteChange = false
    private val safeResetAcknowledgedRate = false
    private val useMinAllocatableAsLowerBound = true
    private val limitProbesLowerThanThroughputEstimate = true
    private val rateControlSettings = googCcConfig.rateControlSettings

    private val probeController = ProbeController(logger, diagnosticContext)
    private val congestionWindowPushbackController = if (rateControlSettings.useCongestionWindowPushback()) {
        CongestionWindowPushbackController()
    } else {
        null
    }

    private val bandwidthEstimation = SendSideBandwidthEstimation(logger, diagnosticContext)
    private val alrDetector = AlrDetector()
    private var probeBitrateEstimator = ProbeBitrateEstimator(logger, diagnosticContext)
    private var delayBasedBwe = DelayBasedBwe(logger, diagnosticContext).also {
        it.setMinBitrate(kCongestionControllerMinBitrate)
    }
    private var acknowledgedBitrateEstimator = AcknowledgedBitrateEstimatorInterface.create()

    private var initialConfig: NetworkControllerConfig? = config

    private var minTargetRate = Bandwidth.ZERO
    private var minDataRate = Bandwidth.ZERO
    private var maxDataRate = Bandwidth.ZERO
    private var startingRate: Bandwidth? = null

    private var firstPacketSent = false

    /* Skipping NetworkStateEstimate */

    private var nextLossUpdate = Instant.MIN
    private var lostPacketsSinceLastLossUpdate = 0
    private var expectedPacketsSinceLastLossUpdate = 0

    private val feedbackMaxRtts = ArrayDeque<Long>()

    private var lastLossBasedTargetRate = config.constraints.startingRate!!
    private var lastPushbackTargetRate = lastLossBasedTargetRate
    private var lastStableTargetRate = lastLossBasedTargetRate
    private var lastLossBasedState: LossBasedState = LossBasedState.kDelayBasedEstimate

    private var lastEstimatedFractionLoss: UByte? = 0u
    private var lastEstimatedRoundTripTime = MAX_DURATION

    private var pacingFactor = config.streamBasedConfig.pacingFactor ?: kDefaultPaceMultiplier
    private var minTotalAllocatedBitrate = config.streamBasedConfig.minTotalAllocatedBitrate ?: Bandwidth.ZERO
    private var maxPaddingRate = config.streamBasedConfig.maxPaddingRate ?: Bandwidth.ZERO

    private var previouslyInAlr = false
    private var currentDataWindow: DataSize? = null

    companion object {
        // From RTCPSender video report interval.
        private val kLossUpdateInterval = 1000.ms

        // Pacing-rate relative to our target send rate.
        // Multiplicative factor that is applied to the target bitrate to calculate
        // the number of bytes that can be transmitted per interval.
        // Increasing this factor will result in lower delays in cases of bitrate
        // overshoots from the encoder.
        private const val kDefaultPaceMultiplier = 2.5

        // If the probe result is far below the current throughput estimate
        // it's unlikely that the probe is accurate, so we don't want to drop too far.
        // However, if we actually are overusing, we want to drop to something slightly
        // below the current throughput estimate to drain the network queues.
        private const val kProbeDropThroughputFraction = 0.85

        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(GoogCcNetworkController::class.java)

        private fun getBandwidthLimitedCause(
            lossBasedState: LossBasedState,
            isRttAboveLimit: Boolean,
            bandwidthUsage: BandwidthUsage
        ): BandwidthLimitedCause {
            if (bandwidthUsage == BandwidthUsage.kBwOverusing ||
                bandwidthUsage == BandwidthUsage.kBwUnderusing
            ) {
                return BandwidthLimitedCause.kDelayBasedLimitedDelayIncreased
            } else if (isRttAboveLimit) {
                return BandwidthLimitedCause.kRttBasedBackOffHighRtt
            }

            return when (lossBasedState) {
                LossBasedState.kDecreasing ->
                    // Probes may not be sent in this state.
                    BandwidthLimitedCause.kLossLimitedBwe
                LossBasedState.kIncreaseUsingPadding ->
                    // Probes may not be sent in this state.
                    BandwidthLimitedCause.kLossLimitedBwe
                LossBasedState.kIncreasing ->
                    // Probes may be sent in this state.
                    BandwidthLimitedCause.kLossLimitedBweIncreasing
                LossBasedState.kDelayBasedEstimate ->
                    BandwidthLimitedCause.kDelayBasedLimited
            }
        }
    }
}

/* Congestion window config, from WebRTC rtc_base/experiments/rate_control_settings.{h,cc}
 * stripped down to used fields only. */
class CongestionWindowConfig(
    private val queueSizeMs: Int? = 350,
    private val minBitrateBps: Int? = 30000,
    private val dropFrameOnly: Boolean = true
) {
    fun useCongestionWindow() = queueSizeMs != null

    fun getCongestionWindowAdditionalTimeMs() = queueSizeMs ?: kDefaultAcceptedQueueMs

    fun useCongestionWindowPushback() = queueSizeMs != null && minBitrateBps != null

    fun useCongestionWindowDropFrameOnly() = dropFrameOnly

    companion object {
        private const val kDefaultAcceptedQueueMs = 350
    }
}
