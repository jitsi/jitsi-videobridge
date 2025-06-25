/*
 * Copyright @ 2019-present 8x8, Inc
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
// This file uses WebRTC's naming style for enums and constants
@file:Suppress("ktlint:standard:enum-entry-name-case", "ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.min
import org.jitsi.utils.div
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.max
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.times
import java.time.Duration
import java.time.Instant
import java.util.*
import kotlin.math.exp
import kotlin.math.min

/** Send-side bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/send_side_bandwidth_estimation.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings.
 */
class LinkCapacityTracker {
    private var capacityEstimateBps = 0.0
    private var lastLinkCapcityUpdate = Instant.MIN
    private var lastDelayBasedEstimate = Bandwidth.INFINITY

    fun updateDelayBasedEstimate(atTime: Instant, delayBasedEstimate: Bandwidth) {
        if (delayBasedEstimate < lastDelayBasedEstimate) {
            capacityEstimateBps = min(capacityEstimateBps, delayBasedEstimate.bps.toDouble())
            lastLinkCapcityUpdate = atTime
        }
        lastDelayBasedEstimate = delayBasedEstimate
    }

    fun onStartingRate(startRate: Bandwidth) {
        if (lastLinkCapcityUpdate.isInfinite()) {
            capacityEstimateBps = startRate.bps.toDouble()
        }
    }

    fun onRateUpdate(acknowledged: Bandwidth?, target: Bandwidth, atTime: Instant) {
        if (acknowledged == null) {
            return
        }
        val acknowledgedTarget = min(acknowledged, target)
        if (acknowledgedTarget.bps > capacityEstimateBps) {
            val alpha = if (lastLinkCapcityUpdate.isFinite() && atTime.isFinite()) {
                val delta = Duration.between(lastLinkCapcityUpdate, atTime)
                exp(-(delta / 10.secs))
            } else {
                0.0
            }
            capacityEstimateBps = alpha * capacityEstimateBps * alpha + (1 - alpha) * acknowledgedTarget.bps
        }
        lastLinkCapcityUpdate = atTime
    }

    fun onRttBackoff(backoffRate: Bandwidth, atTime: Instant) {
        capacityEstimateBps = min(capacityEstimateBps, backoffRate.bps.toDouble())
        lastLinkCapcityUpdate = atTime
    }

    fun estimate(): Bandwidth {
        return capacityEstimateBps.bps
    }
}

class RttBasedBackoff {
    val disabled = false
    val configuredLimit = 3.secs
    val dropFraction = 0.8
    val dropInterval = 1.secs
    val bandwidthFloor = 5.kbps

    var rttLimit = configuredLimit
    var lastPropagationRttUpdate = Instant.MAX
    var lastPropagationRtt = Duration.ZERO
    var lastPacketSent = Instant.MIN

    fun updatePropagationRtt(atTime: Instant, propagationRtt: Duration) {
        lastPropagationRttUpdate = atTime
        lastPropagationRtt = propagationRtt
    }

    fun isRttAboveLimit(): Boolean {
        return correctedRtt() > rttLimit
    }

    private fun correctedRtt(): Duration {
        // Avoid timeout when no packets are being sent.
        val timeoutCorrection = max(Duration.between(lastPropagationRttUpdate, lastPacketSent), Duration.ZERO)
        return timeoutCorrection + lastPropagationRtt
    }
}

private val kBweIncreaseInterval = 1000.ms
private val kBweDecreaseInterval = 300.ms
private val kStartPhase = 2000.ms
private val kBweConverganceTime = 20000.ms
private const val kLimitNumPackets = 20
private val kDefaultMaxBitrate = 1000000000.bps
private val kLowBitrateLogPeriod = 10000.ms
private val kRtcEventLogPeriod = 5000.ms

// Expecting that RTCP feedback is sent uniformly within [0.5, 1.5]s intervals.
private val kMaxRtcpFeedbackInterval = 5000.ms

private const val kDefaultLowLossThreshold = 0.02f
private const val kDefaultHighLossThreshold = 0.1f
private val kDefaultBitrateThreshold = Bandwidth.ZERO

class SendSideBandwidthEstimation(
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext
) {
    private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(this.javaClass)
    private val logger = parentLogger.createChildLogger(javaClass.name)

    private val rttBackoff = RttBasedBackoff()
    private val linkCapacity = LinkCapacityTracker()

    private val minBitrateHistory = ArrayDeque<Pair<Instant, Bandwidth>>()

    // incoming filters
    private var lostPacketsSinceLastLossUpdate = 0L
    private var expectedPacketsSinceLastLossUpdate = 0L

    private var acknowledgedRate: Bandwidth? = null
    private var currentTarget: Bandwidth = Bandwidth.ZERO
    private var lastLoggedTarget: Bandwidth = Bandwidth.ZERO
    private var minBitrateConfigured: Bandwidth = kCongestionControllerMinBitrate
    private var maxBitrateConfigured: Bandwidth = kDefaultMaxBitrate
    private var lastLowBitrateLog: Instant = Instant.MIN

    private var hasDecreasedSinceLastFractionLoss: Boolean = false

    @SuppressFBWarnings("URF_UNREAD_FIELD")
    private var lastLossFeedback: Instant = Instant.MIN
    private var lastLossPacketReport: Instant = Instant.MIN
    private var lastFractionLoss: UByte = 0u
    private var lastLoggedFractionLoss: UByte = 0u
    private var lastRoundTripTime: Duration = Duration.ZERO

    // The max bitrate as set by the receiver in the call. This is typically
    // signalled using the REMB RTCP message and is used when we don't have any
    // send side delay based estimate.
    private var receiverLimit: Bandwidth = Bandwidth.INFINITY
    private var delayBasedLimit: Bandwidth = Bandwidth.INFINITY
    private var timeLastDecrease: Instant = Instant.MIN
    private var firstReportTime: Instant = Instant.MIN
    private var initiallyLostPackets: Int = 0
    private var bitrateAt2Seconds: Bandwidth = Bandwidth.ZERO
    private var umaUpdateState: UmaState = UmaState.kNoUpdate
    private var umaRttState: UmaState = UmaState.kNoUpdate
    private val rampupUmaStatsUpdated = ArrayList<Boolean>(kUmaRampupMetrics.size)
    private var lastRtcEventLog: Instant = Instant.MIN
    private val lowLossThreshold: Float = kDefaultLowLossThreshold
    private val highLossThreshold: Float = kDefaultHighLossThreshold
    private val bitrateThreshold: Bandwidth = kDefaultBitrateThreshold
    var lossBasedBandwidthEstimatorV2 = LossBasedBweV2().apply {
        setMinMaxBitrate(minBitrateConfigured, maxBitrateConfigured)
    }
        private set
    private var lossBasedState: LossBasedState = LossBasedState.kDelayBasedEstimate

    fun onRouteChange() {
        lostPacketsSinceLastLossUpdate = 0
        expectedPacketsSinceLastLossUpdate = 0
        currentTarget = Bandwidth.ZERO
        minBitrateConfigured = kCongestionControllerMinBitrate
        maxBitrateConfigured = kDefaultMaxBitrate
        lastLowBitrateLog = Instant.MIN
        hasDecreasedSinceLastFractionLoss = false
        lastLossFeedback = Instant.MIN
        lastLossPacketReport = Instant.MIN
        lastFractionLoss = 0u
        lastLoggedFractionLoss = 0u
        lastRoundTripTime = Duration.ZERO
        receiverLimit = Bandwidth.INFINITY
        delayBasedLimit = Bandwidth.INFINITY
        timeLastDecrease = Instant.MIN
        firstReportTime = Instant.MIN
        initiallyLostPackets = 0
        bitrateAt2Seconds = Bandwidth.ZERO
        umaUpdateState = UmaState.kNoUpdate
        umaRttState = UmaState.kNoUpdate
        lastRtcEventLog = Instant.MIN
        if (lossBasedBandwidthEstimatorV2.useInStartPhase()) {
            lossBasedBandwidthEstimatorV2 = LossBasedBweV2()
        }
    }

    fun targetRate(): Bandwidth {
        var target = currentTarget
        if (!disableReceiverLimitCapsOnly) {
            target = min(target, receiverLimit)
        }
        return max(minBitrateConfigured, target)
    }

    fun lossBasedState(): LossBasedState = lossBasedState

    // Return whether the current rtt is higher than the rtt limited configured in
    // RttBasedBackoff.
    fun isRttAboveLimit(): Boolean = rttBackoff.isRttAboveLimit()

    fun fractionLoss() = lastFractionLoss

    fun roundTripTime() = lastRoundTripTime

    fun getEstimatedLinkCapacity(): Bandwidth = linkCapacity.estimate()

    // Call periodically to update estimate
    fun updateEstimate(atTime: Instant) {
        if (rttBackoff.isRttAboveLimit()) {
            if (Duration.between(timeLastDecrease, atTime) >= rttBackoff.dropInterval &&
                currentTarget > rttBackoff.bandwidthFloor
            ) {
                timeLastDecrease = atTime
                val newBitrate = (currentTarget * rttBackoff.dropFraction).coerceAtLeast(rttBackoff.bandwidthFloor)
                linkCapacity.onRttBackoff(newBitrate, atTime)
                updateTargetBitrate(newBitrate, atTime)
                return
            }
            // TODO(srte): This is likely redundant in most cases.
            applyTargetLimits(atTime)
            return
        }

        // We trust the REMB and/or delay-based estimate during the first 2 seconds if
        // we haven't had any packet loss reported, to allow startup bitrate probing.
        if (lastFractionLoss.toUInt() == 0u && isInStartPhase(atTime) &&
            !lossBasedBandwidthEstimatorV2.readyToUseInStartPhase()
        ) {
            var newBitrate = currentTarget
            // TODO(srte): We should not allow the new_bitrate to be larger than the
            // receiver limit here.
            if (receiverLimit.isFinite()) {
                newBitrate = max(receiverLimit, newBitrate)
            }
            if (delayBasedLimit.isFinite()) {
                newBitrate = max(delayBasedLimit, newBitrate)
            }

            if (newBitrate != currentTarget) {
                minBitrateHistory.clear()
                minBitrateHistory.add(Pair(atTime, currentTarget))
                updateTargetBitrate(newBitrate, atTime)
                return
            }
        }
        updateMinHistory(atTime)
        if (lastLossPacketReport.isInfinite()) {
            // No feedback received.
            // TODO(srte): This is likely redundant in most cases.
            applyTargetLimits(atTime)
            return
        }
        if (lossBasedBandwidthEstimatorV2.isReady()) {
            val result = lossBasedBandwidthEstimatorV2.getLossBasedResult()
            lossBasedState = result.state
            updateTargetBitrate(result.bandwidthEstimate, atTime)
            return
        }

        val timeSinceLossPacketReport = Duration.between(lastLossPacketReport, atTime)
        if (timeSinceLossPacketReport < 1.2 * kMaxRtcpFeedbackInterval) {
            // We only care about loss above a given bitrate threshold.
            val loss = lastFractionLoss.toFloat() / 256f
            // We only make decisions based on loss when the bitrate is above a
            // threshold. This is a crude way of handling loss which is uncorrelated
            // to congestion.
            if (currentTarget < bitrateThreshold || loss <= lowLossThreshold) {
                // Loss < 2%: Increase rate by 8% of the min bitrate in the last
                // kBweIncreaseInterval.
                // Note that by remembering the bitrate over the last second one can
                // rampup up one second faster than if only allowed to start ramping
                // at 8% per second rate now. E.g.:
                //   If sending a constant 100kbps it can rampup immediately to 108kbps
                //   whenever a receiver report is received with lower packet loss.
                //   If instead one would do: current_bitrate_ *= 1.08^(delta time),
                //   it would take over one second since the lower packet loss to achieve
                //   108kbps.
                var newBitrate = (minBitrateHistory.first().second.bps * 1.08 + 0.5).bps

                // Add 1 kbps extra, just to make sure that we do not get stuck
                // (gives a little extra increase at low rates, negligible at higher
                // rates).
                newBitrate += 1000.bps
                updateTargetBitrate(newBitrate, atTime)
                return
            } else if (currentTarget > bitrateThreshold) {
                if (loss <= highLossThreshold) {
                    // Loss between 2% - 10%: Do nothing.
                } else {
                    // Loss > 10%: Limit the rate decreases to once a kBweDecreaseInterval
                    // + rtt.
                    if (!hasDecreasedSinceLastFractionLoss &&
                        Duration.between(timeLastDecrease, atTime) >= (kBweDecreaseInterval + lastRoundTripTime)
                    ) {
                        timeLastDecrease = atTime

                        // Reduce rate:
                        //   newRate = rate * (1 - 0.5*lossRate);
                        //   where packetLoss = 256*lossRate;
                        val newBitrate = (currentTarget.bps * (512u - lastFractionLoss.toUInt()).toDouble() / 512.0).bps
                        hasDecreasedSinceLastFractionLoss = true
                        updateTargetBitrate(newBitrate, atTime)
                        return
                    }
                }
            }
        }
        // TODO(srte): This is likely redundant in most cases.
        applyTargetLimits(atTime)
    }

    fun onSentPacket(sentPacket: SentPacket) {
        // Only feedback-triggering packets will be reported here.
        rttBackoff.lastPacketSent = sentPacket.sendTime
    }

    fun updatePropagationRtt(atTime: Instant, propagationRtt: Duration) {
        rttBackoff.updatePropagationRtt(atTime, propagationRtt)
    }

    // Call when we receive a RTCP message with TMMBR or REMB.
    fun updateReceiverEstimate(atTime: Instant, bandwidth: Bandwidth) {
        // TODO(srte): Ensure caller passes PlusInfinity, not zero, to represent no
        // limitation.
        receiverLimit = if (bandwidth == Bandwidth.ZERO) {
            Bandwidth.INFINITY
        } else {
            bandwidth
        }
        applyTargetLimits(atTime)
    }

    // Call when a new delay-based estimate is available.
    fun updateDelayBasedEstimate(atTime: Instant, bitrate: Bandwidth) {
        linkCapacity.updateDelayBasedEstimate(atTime, bitrate)
        // TODO(srte): Ensure caller passes PlusInfinity, not zero, to represent no
        // limitation.
        delayBasedLimit = if (bitrate == Bandwidth.ZERO) {
            Bandwidth.INFINITY
        } else {
            bitrate
        }
        applyTargetLimits(atTime)
    }

    // Call when we receive a RTCP message with a ReceiveBlock.
    fun updatePacketsLost(packetsLost: Long, numberOfPackets: Long, atTime: Instant) {
        lastLossFeedback = atTime
        if (firstReportTime.isInfinite()) {
            firstReportTime = atTime
        }

        // Check sequence number diff and weight loss report
        if (numberOfPackets > 0) {
            val expected = expectedPacketsSinceLastLossUpdate + numberOfPackets

            // Don't generate a loss rate until it can be based on enough packets.
            if (expected < kLimitNumPackets) {
                // Accumulate reports
                expectedPacketsSinceLastLossUpdate = expected
                lostPacketsSinceLastLossUpdate = packetsLost
                return
            }

            hasDecreasedSinceLastFractionLoss = false
            val lostQ8 = (lostPacketsSinceLastLossUpdate + packetsLost).coerceAtLeast(0L) shl 8
            lastFractionLoss = (lostQ8 / expected).coerceAtMost(255).toUByte()

            // Reset accumulators
            lostPacketsSinceLastLossUpdate = 0
            expectedPacketsSinceLastLossUpdate = 0
            lastLossPacketReport = atTime
            updateEstimate(atTime)
        }
    }

    // Call when we receive a RTCP message with a ReceiveBlock.
    fun updateRtt(rtt: Duration, atTime: Instant) {
        // Update RTT if we were able to compute an RTT based on this RTCP.
        // FlexFEC doesn't send RTCP SR, which means we won't be able to compute RTT.
        if (rtt > Duration.ZERO) {
            lastRoundTripTime = rtt
        }

        if (!isInStartPhase(atTime) && umaRttState == UmaState.kNoUpdate) {
            umaRttState = UmaState.kDone
            /* TODO: histograms */
        }
    }

    fun setBitrates(sendBitrate: Bandwidth?, minBitrate: Bandwidth, maxBitrate: Bandwidth, atTime: Instant) {
        setMinMaxBitrate(minBitrate, maxBitrate)
        if (sendBitrate != null) {
            linkCapacity.onStartingRate(sendBitrate)
            setSendBitrate(sendBitrate, atTime)
        }
    }

    fun setSendBitrate(bitrate: Bandwidth, atTime: Instant) {
        assert(bitrate >= Bandwidth.ZERO)
        // Reset to avoid being capped by the estimate.
        delayBasedLimit = Bandwidth.INFINITY
        updateTargetBitrate(bitrate, atTime)
        minBitrateHistory.clear()
    }

    fun setMinMaxBitrate(minBitrate: Bandwidth, maxBitrate: Bandwidth) {
        minBitrateConfigured = max(minBitrate, kCongestionControllerMinBitrate)
        if (maxBitrate > Bandwidth.ZERO && maxBitrate.isFinite()) {
            maxBitrateConfigured = max(minBitrateConfigured, maxBitrate)
        } else {
            maxBitrateConfigured = kDefaultMaxBitrate
        }
        lossBasedBandwidthEstimatorV2.setMinMaxBitrate(minBitrateConfigured, maxBitrateConfigured)
    }

    fun getMinBitrate(): Int {
        return minBitrateConfigured.bps.toInt()
    }

    fun setAcknowledgedRate(acknowledgedRate: Bandwidth?, atTime: Instant) {
        this.acknowledgedRate = acknowledgedRate
        if (acknowledgedRate == null) {
            return
        }
        lossBasedBandwidthEstimatorV2.setAcknowledgedBitrate(acknowledgedRate)
    }

    fun updateLossBasedEstimator(
        report: TransportPacketsFeedback,
        delayDetectorState: BandwidthUsage,
        probeBitrate: Bandwidth?,
        inAlr: Boolean
    ) {
        lossBasedBandwidthEstimatorV2.updateBandwidthEstimate(
            report.packetFeedbacks,
            delayBasedLimit,
            inAlr
        )
        updateEstimate(report.feedbackTime)
    }

    private fun isInStartPhase(atTime: Instant): Boolean {
        return firstReportTime.isInfinite() ||
            Duration.between(firstReportTime, atTime) <= kStartPhase
    }

    private fun updateUmaStatsPacketsLost(atTime: Instant, packetsLost: Int) {
        val bitrateKbps = ((currentTarget.bps + 500) / 1000).kbps
        for (i in kUmaRampupMetrics.indices) {
            if (!rampupUmaStatsUpdated[i] &&
                bitrateKbps.kbps >= kUmaRampupMetrics[i].bitrateKbps
            ) {
                /* TODO: histograms */
                rampupUmaStatsUpdated[i] = true
            }
        }
        if (isInStartPhase(atTime)) {
            initiallyLostPackets += packetsLost
        } else if (umaUpdateState == UmaState.kNoUpdate) {
            umaUpdateState = UmaState.kFirstDone
            bitrateAt2Seconds = bitrateKbps
            /* TODO: histograms */
        } else if (umaUpdateState == UmaState.kFirstDone &&
            Duration.between(firstReportTime, atTime) >= kBweConverganceTime
        ) {
            umaUpdateState = UmaState.kDone
            val bitrateDiffKbps = bitrateAt2Seconds.kbps.toInt().coerceAtLeast(0)
            /* TODO: histograms */
        }
    }

    // Updates history of min bitrates.
    // After this method returns min_bitrate_history_.front().second contains the
    // min bitrate used during last kBweIncreaseIntervalMs.
    private fun updateMinHistory(atTime: Instant) {
        // Remove old data points from history.
        // Since history precision is in ms, add one so it is able to increase
        // bitrate if it is off by as little as 0.5ms.
        while (!minBitrateHistory.isEmpty() &&
            (Duration.between(minBitrateHistory.first().first, atTime) + 1.ms) > kBweIncreaseInterval
        ) {
            minBitrateHistory.removeFirst()
        }

        // Typical minimum sliding-window algorithm: Pop values higher than current
        // bitrate before pushing it.
        while (!minBitrateHistory.isEmpty() &&
            currentTarget <= minBitrateHistory.last().second
        ) {
            minBitrateHistory.removeLast()
        }

        minBitrateHistory.add(Pair(atTime, currentTarget))
    }

    // Gets the upper limit for the target bitrate. This is the minimum of the
    // delay based limit, the receiver limit and the loss based controller limit.
    private fun getUpperLimit(): Bandwidth {
        var upperLimit = delayBasedLimit
        if (disableReceiverLimitCapsOnly) {
            upperLimit = min(upperLimit, receiverLimit)
        }
        return min(upperLimit, maxBitrateConfigured)
    }

    // Prints a warning if `bitrate` if sufficiently long time has past since last
    // warning.
    private fun maybeLogLowBitrateWarning(bitrate: Bandwidth, atTime: Instant) {
        if (Duration.between(lastLowBitrateLog, atTime) > kLowBitrateLogPeriod) {
            logger.warn("Estimated available bandwidth $bitrate is below configured min bitrate $minBitrateConfigured.")
            lastLowBitrateLog = atTime
        }
    }

    // Stores an update to the event log if the loss rate has changed, the target
    // has changed, or sufficient time has passed since last stored event.
    private fun maybeLogLossBasedEvent(atTime: Instant) {
        if (currentTarget != lastLoggedTarget ||
            lastFractionLoss != lastLoggedFractionLoss ||
            Duration.between(lastRtcEventLog, atTime) > kRtcEventLogPeriod
        ) {
            timeSeriesLogger.trace {
                diagnosticContext.makeTimeSeriesPoint("RtcEventBweUpdateLossBased", atTime)
                    .addField("currentTarget", currentTarget.bps)
                    .addField("lastFractionLoss", lastFractionLoss)
                    .addField("expectedPacketsSinceLastLossUpdate", expectedPacketsSinceLastLossUpdate)
            }
            lastLoggedFractionLoss = lastFractionLoss
            lastLoggedTarget = currentTarget
            lastRtcEventLog = atTime
        }
    }

    // Cap `bitrate` to [min_bitrate_configured_, max_bitrate_configured_] and
    // set `current_bitrate_` to the capped value and updates the event log.
    private fun updateTargetBitrate(bitrate: Bandwidth, atTime: Instant) {
        val newBitrate = min(bitrate, getUpperLimit())
        if (newBitrate < minBitrateConfigured) {
            maybeLogLowBitrateWarning(newBitrate, atTime)
        }
        currentTarget = newBitrate
        maybeLogLossBasedEvent(atTime)
        linkCapacity.onRateUpdate(acknowledgedRate, currentTarget, atTime)
    }

    // Applies lower and upper bounds to the current target rate.
    // TODO(srte): This seems to be called even when limits haven't changed, that
    // should be cleaned up.
    private fun applyTargetLimits(atTime: Instant) {
        updateTargetBitrate(currentTarget, atTime)
    }

    private enum class UmaState { kNoUpdate, kFirstDone, kDone }

    companion object {
        const val disableReceiverLimitCapsOnly = false
    }
}

private data class UmaRampUpMetric(
    val metricName: String,
    val bitrateKbps: Int
)

private val kUmaRampupMetrics = arrayOf(
    UmaRampUpMetric("WebRTC.BWE.RampUpTimeTo500kbpsInMs", 500),
    UmaRampUpMetric("WebRTC.BWE.RampUpTimeTo1000kbpsInMs", 1000),
    UmaRampUpMetric("WebRTC.BWE.RampUpTimeTo2000kbpsInMs", 2000)
)
