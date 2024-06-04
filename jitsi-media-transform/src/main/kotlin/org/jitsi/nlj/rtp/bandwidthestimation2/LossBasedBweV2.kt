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
// This file uses WebRTC's naming style for enums
@file:Suppress("ktlint:standard:enum-entry-name-case")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.isFinite
import org.jitsi.nlj.util.isInfinite
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.min
import org.jitsi.nlj.util.per
import org.jitsi.utils.div
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Loss-based bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/loss_based_bwe_v2.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Field trial settings are settable through the API, but not currently as config options.
 */
enum class LossBasedState {
    kIncreasing,
    kDecreasing,
    kDelayBasedEstimate
}

private fun isValid(datarate: Bandwidth?): Boolean {
    return datarate?.isFinite() ?: false
}

private fun isValid(timestamp: Instant) = timestamp.isFinite()

private class PacketResultsSummary {
    var numPackets = 0
    var numLostPackets = 0
    var totalSize = DataSize.ZERO
    var firstSendTime = Instant.MAX
    var lastSendTime = Instant.MIN
}

private fun getPacketResultsSummary(packetResults: List<PacketResult>): PacketResultsSummary {
    val packetResultsSummary = PacketResultsSummary()

    packetResultsSummary.numPackets = packetResults.size
    for (packet in packetResults) {
        if (!packet.isReceived()) {
            packetResultsSummary.numLostPackets++
        }
        packetResultsSummary.totalSize += packet.sentPacket.size
        packetResultsSummary.firstSendTime = min(packetResultsSummary.firstSendTime, packet.sentPacket.sendTime)
        packetResultsSummary.lastSendTime = max(packetResultsSummary.lastSendTime, packet.sentPacket.sendTime)
    }

    return packetResultsSummary
}

private fun getLossProbability(inherentLoss_: Double, lossLimitedBandwidth: Bandwidth, sendingRate: Bandwidth): Double {
    if (inherentLoss_ < 0.0 || inherentLoss_ > 1.0) {
        LossBasedBweV2.logger.warn("The inherent loss must be in [0,1]: $inherentLoss_")
    }
    val inherentLoss = inherentLoss_.coerceIn(0.0, 1.0)
    if (!sendingRate.isFinite()) {
        LossBasedBweV2.logger.warn("The sending rate must be finite: $sendingRate")
    }
    if (!lossLimitedBandwidth.isFinite()) {
        LossBasedBweV2.logger.warn("The loss limited bandwidth must be finite: $lossLimitedBandwidth")
    }
    var lossProbability = inherentLoss

    if (isValid(sendingRate) && isValid(lossLimitedBandwidth) &&
        (sendingRate > lossLimitedBandwidth)
    ) {
        lossProbability += (1 - inherentLoss) *
            ((sendingRate - lossLimitedBandwidth) / sendingRate)
    }
    return lossProbability.coerceIn(1.0e-6, 1.0 - 1.0e-6)
}

class LossBasedBweV2(configIn: Config = defaultConfig) {
    private val config = configIn.copy()
    private var acknowledgedBitrate: Bandwidth? = null
    private var currentEstimate: ChannelParameters =
        ChannelParameters(inherentLoss = config.initialInherentLossEstimate)
    private var numObservations = 0
    private val observations = MutableList(config.observationWindowSize) { Observation() }
    private var partialObservation = PartialObservation()
    private var lastSendTimeMostRecentObservation = Instant.MAX
    private var lastTimeEstimateReduced = Instant.MIN
    private var cachedInstantUpperBound: Bandwidth? = null
    private val instantUpperBoundTemporalWeights = MutableList(config.observationWindowSize) { 0.0 }
    private val temporalWeights = MutableList(config.observationWindowSize) { 0.0 }
    private val delayDetectorStates = ArrayDeque<BandwidthUsage>()
    private var recoveringAfterLossTimestamp = Instant.MIN
    private var bandwidthLimitInCurrentWindow = Bandwidth.INFINITY
    private var minBitrate = 1.kbps
    private var maxBitrate = Bandwidth.INFINITY
    private var currentState = LossBasedState.kDelayBasedEstimate
    private var probeBitrate = Bandwidth.INFINITY
    private var delayBasedEstimate = Bandwidth.INFINITY
    private var lastProbeTimestamp = Instant.MIN

    init {
        if (!config.enabled) {
            logger.debug(
                "The configuration does not specify that the " +
                    "estimator should be enabled, disabling it."
            )
        }
        if (!config.isValid()) {
            logger.warn("The configuration is not valid, disabling the estimator.")
            config.enabled = false
        }
        calculateTemporalWeights()
    }

    class Result(
        var bandwidthEstimate: Bandwidth = Bandwidth.ZERO,
        var state: LossBasedState = LossBasedState.kDelayBasedEstimate
    )

    fun isEnabled(): Boolean = config.enabled

    /** Returns true iff a BWE can be calculated, i.e., the estimator has been
     initialized with a BWE and then has received enough `PacketResult`s.
     */
    fun isReady(): Boolean {
        return isEnabled() && isValid(currentEstimate.lossLimitedBandwidth) &&
            numObservations > 0
    }

    fun readyToUseInStartPhase(): Boolean {
        return isReady() && config.useInStartPhase
    }

    /** Returns [Bandwidth.INFINITY] if no BWE can be calculated. */
    fun getLossBasedResult(): Result {
        val result = Result()
        result.state = currentState
        if (!isReady()) {
            if (!isEnabled()) {
                logger.warn("The estimator must be enabled before it can be used.")
            } else {
                if (!isValid(currentEstimate.lossLimitedBandwidth)) {
                    logger.warn("The estimator must be initialized before it can be used.")
                }
                if (numObservations <= 0) {
                    logger.warn("The estimator must receive enough loss statistics before it can be used.")
                }
            }

            result.bandwidthEstimate = if (isValid(delayBasedEstimate)) {
                delayBasedEstimate
            } else {
                Bandwidth.INFINITY
            }
            return result
        }

        result.bandwidthEstimate =
            if (isValid(delayBasedEstimate)) {
                minOf(currentEstimate.lossLimitedBandwidth, getInstantUpperBound(), delayBasedEstimate)
            } else {
                min(currentEstimate.lossLimitedBandwidth, getInstantUpperBound())
            }
        return result
    }

    fun setAcknowledgedBitrate(acknowledgedBitrate: Bandwidth) {
        if (isValid(acknowledgedBitrate)) {
            this.acknowledgedBitrate = acknowledgedBitrate
        } else {
            logger.warn("The acknowledged bitrate must be finite: $acknowledgedBitrate")
        }
    }

    fun setMinMaxBitrate(minBitrate: Bandwidth, maxBitrate: Bandwidth) {
        if (isValid(minBitrate)) {
            this.minBitrate = minBitrate
        } else {
            logger.warn("The min bitrate must be finite: $minBitrate")
        }

        if (isValid(maxBitrate)) {
            this.maxBitrate = maxBitrate
        } else {
            logger.warn("The max bitrate must be finite: $maxBitrate")
        }
    }

    fun updateBandwidthEstimate(
        packetResults: List<PacketResult>,
        delayBasedEstimate: Bandwidth,
        delayDetectorState: BandwidthUsage,
        probeBitrate: Bandwidth?,
        inAlr: Boolean
    ) {
        this.delayBasedEstimate = delayBasedEstimate
        if (!isEnabled()) {
            logger.warn("The estimator must be enabled before it can be used.")
            return
        }

        if (packetResults.isEmpty()) {
            logger.debug("The estimate cannot be updated without any loss statistics.")
            return
        }

        if (!pushBackObservation(packetResults, delayDetectorState)) {
            return
        }

        setProbeBitrate(probeBitrate)

        if (!isValid(currentEstimate.lossLimitedBandwidth)) {
            if (!isValid(delayBasedEstimate)) {
                logger.warn("The delay based estimate must be finite: $delayBasedEstimate.")
                return
            }
            currentEstimate.lossLimitedBandwidth = delayBasedEstimate
        }

        var bestCandidate = currentEstimate
        var objectiveMax = -Double.MAX_VALUE
        for (candidate in getCandidates(inAlr)) {
            newtonsMethodUpdate(candidate)

            val candidateObjective = getObjective(candidate)
            if (candidateObjective > objectiveMax) {
                objectiveMax = candidateObjective
                bestCandidate = candidate
            }
        }

        if (bestCandidate.lossLimitedBandwidth < currentEstimate.lossLimitedBandwidth) {
            lastTimeEstimateReduced = lastSendTimeMostRecentObservation
        }

        // Do not increase the estimate if the average loss is greater than current
        // inherent loss.
        if (getAverageReportedLossRatio() > bestCandidate.inherentLoss &&
            config.notIncreaseIfInherentLossLessThanAverageLoss &&
            currentEstimate.lossLimitedBandwidth < bestCandidate.lossLimitedBandwidth
        ) {
            bestCandidate.lossLimitedBandwidth = currentEstimate.lossLimitedBandwidth
        }

        if (isBandwidthLimitedDueToLoss()) {
            // Bound the estimate increase if:
            // 1. The estimate has been increased for less than
            // `delayed_increase_window` ago, and
            // 2. The best candidate is greater than bandwidth_limit_in_current_window.
            if (recoveringAfterLossTimestamp.isFinite() &&
                recoveringAfterLossTimestamp + config.delayedIncreaseWindow > lastSendTimeMostRecentObservation &&
                bestCandidate.lossLimitedBandwidth > bandwidthLimitInCurrentWindow
            ) {
                bestCandidate.lossLimitedBandwidth = bandwidthLimitInCurrentWindow
            }

            val increasingWhenLossLimited = isEstimateIncreasingWhenLossLimited(bestCandidate)
            // Bound the best candidate by the acked bitrate unless there is a recent
            // probe result.
            if (increasingWhenLossLimited && !isValid(this.probeBitrate) && isValid(acknowledgedBitrate)) {
                bestCandidate.lossLimitedBandwidth =
                    if (isValid(bestCandidate.lossLimitedBandwidth)) {
                        min(
                            bestCandidate.lossLimitedBandwidth,
                            acknowledgedBitrate!! * config.bandwidthRampupUpperBoundFactor
                        )
                    } else {
                        acknowledgedBitrate!! * config.bandwidthRampupUpperBoundFactor
                    }
            }
        }

        if (isEstimateIncreasingWhenLossLimited(bestCandidate) &&
            bestCandidate.lossLimitedBandwidth < delayBasedEstimate
        ) {
            currentState = LossBasedState.kIncreasing
        } else if (bestCandidate.lossLimitedBandwidth < delayBasedEstimate) {
            currentState = LossBasedState.kDecreasing
        } else if (bestCandidate.lossLimitedBandwidth >= delayBasedEstimate) {
            currentState = LossBasedState.kDelayBasedEstimate
        }

        // Use probe bitrate as the estimate limit when probes are requested.
        if (config.probeIntegrationEnabled && isValid(this.probeBitrate) &&
            isRequestingProbe()
        ) {
            if (lastProbeTimestamp + config.probeExpiration >=
                lastSendTimeMostRecentObservation
            ) {
                bestCandidate.lossLimitedBandwidth = min(this.probeBitrate, bestCandidate.lossLimitedBandwidth)
            }
        }

        currentEstimate = bestCandidate

        if (isBandwidthLimitedDueToLoss() &&
            recoveringAfterLossTimestamp.isInfinite() ||
            recoveringAfterLossTimestamp + config.delayedIncreaseWindow <
            lastSendTimeMostRecentObservation
        ) {
            bandwidthLimitInCurrentWindow =
                max(kCongestionControllerMinBitrate, currentEstimate.lossLimitedBandwidth * config.maxIncreaseFactor)
            recoveringAfterLossTimestamp = lastSendTimeMostRecentObservation
        }
    }

    // For unit testing only
    fun setBandwidthEstimate(bandwidthEstimate: Bandwidth) {
        if (isValid(bandwidthEstimate)) {
            currentEstimate.lossLimitedBandwidth = bandwidthEstimate
        } else {
            logger.warn("The bandwidth estimate must be finite: $bandwidthEstimate")
        }
    }

    private data class ChannelParameters(
        var inherentLoss: Double = 0.0,
        var lossLimitedBandwidth: Bandwidth = Bandwidth.MINUS_INFINITY
    )

    /** These are the parameters set by field trial parameters in libwebrtc.  They are initialized to their
     * default values.
     */
    data class Config(
        var enabled: Boolean = true,
        val bandwidthRampupUpperBoundFactor: Double = 1000000.0,
        val rampupAccelerationMaxFactor: Double = 0.0,
        val rampupAccelerationMaxoutTime: Duration = 60.secs,
        val candidateFactors: DoubleArray = doubleArrayOf(1.02, 1.0, 0.95),
        val higherBandwidthBiasFactor: Double = 0.0002,
        val higherLogBandwidthBiasFactor: Double = 0.02,
        val inherentLossLowerBound: Double = 1.0e-3,
        val lossThresholdOfHighBandwidthPreference: Double = 0.15,
        val bandwidthPreferenceSmoothingFactor: Double = 0.002,
        val inherentLossUpperBoundBandwidthBalance: Bandwidth = 75.kbps,
        val inherentLossUpperBoundOffset: Double = 0.05,
        val initialInherentLossEstimate: Double = 0.01,
        val newtonIterations: Int = 1,
        val newtonStepSize: Double = 0.75,
        val appendAcknowledgedRateCandidate: Boolean = true,
        val appendDelayBasedEstimateCandidate: Boolean = true,
        val observationDurationLowerBound: Duration = 250.ms,
        val observationWindowSize: Int = 20,
        val sendingRateSmoothingFactor: Double = 0.0,
        val instantUpperBoundTemporalWeightFactor: Double = 0.9,
        val instantUpperBoundBandwidthBalance: Bandwidth = 75.kbps,
        val instantUpperBoundLossOffset: Double = 0.05,
        val temporalWeightFactor: Double = 0.9,
        val bandwidthBackoffLowerBoundFactor: Double = 1.0,
        val trendlineIntegrationEnabled: Boolean = false,
        val trendlineObservationsWindowSize: Int = 20,
        val maxIncreaseFactor: Double = 1.3,
        val delayedIncreaseWindow: Duration = 300.ms,
        val useAckedBitrateOnlyWhenOverusing: Boolean = false,
        val notIncreaseIfInherentLossLessThanAverageLoss: Boolean = true,
        val highLossRateThreshold: Double = 1.0,
        val bandwidthCapAtHighLossRate: Bandwidth = 500.kbps,
        val slopeOfBweHighLossFunc: Double = 1000.0,
        val probeIntegrationEnabled: Boolean = false,
        val probeExpiration: Duration = 10.secs,
        val notUseAckedRateInAlr: Boolean = true,
        val useInStartPhase: Boolean = false
    ) {
        fun isValid(): Boolean {
            if (!enabled) {
                return false
            }

            var valid = true

            if (bandwidthRampupUpperBoundFactor <= 1.0) {
                logger.warn(
                    "The bandwidth rampup upper bound factor must be greater than 1: " +
                        bandwidthRampupUpperBoundFactor
                )
                valid = false
            }
            if (rampupAccelerationMaxFactor < 0.0) {
                logger.warn("The rampup acceleration max factor must be non-negative.: $rampupAccelerationMaxFactor")
                valid = false
            }
            if (rampupAccelerationMaxoutTime <= Duration.ZERO) {
                logger.warn("The rampup acceleration maxout time must be above zero: $rampupAccelerationMaxoutTime")
                valid = false
            }
            for (candidateFactor in candidateFactors) {
                if (candidateFactor <= 0.0) {
                    logger.warn("All candidate factors must be greater than zero: $candidateFactor")
                    valid = false
                }
            }

            // Ensure that the configuration allows generation of at least one candidate
            // other than the current estimate.
            if (!appendAcknowledgedRateCandidate && !appendDelayBasedEstimateCandidate &&
                !candidateFactors.any { it != 1.0 }
            ) {
                logger.warn(
                    "The configuration does not allow generating candidates. Specify " +
                        "a candidate factor other than 1.0, allow the acknowledged rate " +
                        "to be a candidate, and/or allow the delay based estimate to be a " +
                        "candidate."
                )
                valid = false
            }
            if (higherBandwidthBiasFactor < 0.0) {
                logger.warn("The higher bandwidth bias factor must be non-negative: $higherBandwidthBiasFactor")
                valid = false
            }
            if (inherentLossLowerBound < 0.0 || inherentLossLowerBound >= 1.0) {
                logger.warn("The inherent loss lower bound must be in [0, 1): $inherentLossLowerBound")
                valid = false
            }
            if (lossThresholdOfHighBandwidthPreference < 0.0 || lossThresholdOfHighBandwidthPreference >= 1.0) {
                logger.warn(
                    "The loss threshold of high bandwidth preference must be in [0, 1): " +
                        lossThresholdOfHighBandwidthPreference
                )
                valid = false
            }
            if (bandwidthPreferenceSmoothingFactor <= 0.0 || bandwidthPreferenceSmoothingFactor > 1.0) {
                logger.warn(
                    "The bandwidth preference smoothing factor must be in (0, 1]: " +
                        bandwidthPreferenceSmoothingFactor
                )
                valid = false
            }
            if (inherentLossUpperBoundBandwidthBalance <= Bandwidth.ZERO) {
                logger.warn(
                    "The inherent loss upper bound bandwidth balance must be positive: " +
                        inherentLossUpperBoundBandwidthBalance
                )
                valid = false
            }
            if (inherentLossUpperBoundOffset < inherentLossLowerBound || inherentLossUpperBoundOffset >= 1.0) {
                logger.warn(
                    "The inherent loss upper bound must be greater than or equal to the inherent loss " +
                        "lower bound, which is $inherentLossLowerBound, and less than 1: $inherentLossUpperBoundOffset"
                )
                valid = false
            }
            if (initialInherentLossEstimate < 0.0 || initialInherentLossEstimate >= 1.0) {
                logger.warn("The initial inherent loss estimate must be in [0, 1): $initialInherentLossEstimate")
                valid = false
            }
            if (newtonIterations <= 0) {
                logger.warn("The number of Newton iterations must be positive: $newtonIterations")
                valid = false
            }
            if (newtonStepSize <= 0.0) {
                logger.warn("The Newton step size must be positive: $newtonStepSize")
                valid = false
            }
            if (observationDurationLowerBound <= Duration.ZERO) {
                logger.warn("The observation duration lower bound must be positive: $observationDurationLowerBound")
                valid = false
            }
            if (observationWindowSize < 2) {
                logger.warn("The observation window size must be at least 2: $observationWindowSize")
                valid = false
            }
            if (sendingRateSmoothingFactor < 0.0 || sendingRateSmoothingFactor >= 1.0) {
                logger.warn("The sending rate smoothing factor must be in [0, 1): $sendingRateSmoothingFactor")
                valid = false
            }
            if (instantUpperBoundTemporalWeightFactor <= 0.0 || instantUpperBoundTemporalWeightFactor > 1.0) {
                logger.warn(
                    "The instant upper bound temporal weight factor must be in (0, 1]: " +
                        instantUpperBoundTemporalWeightFactor
                )
                valid = false
            }
            if (instantUpperBoundBandwidthBalance <= Bandwidth.ZERO) {
                logger.warn(
                    "The instant upper bound bandwidth balance must be positive: " +
                        instantUpperBoundBandwidthBalance
                )
                valid = false
            }
            if (instantUpperBoundLossOffset < 0.0 || instantUpperBoundLossOffset >= 1.0) {
                logger.warn("The instant upper bound loss offset must be in [0, 1): $instantUpperBoundLossOffset")
                valid = false
            }
            if (temporalWeightFactor <= 0.0 || temporalWeightFactor > 1.0) {
                logger.warn("The temporal weight factor must be in (0, 1]: $temporalWeightFactor")
                valid = false
            }
            if (bandwidthBackoffLowerBoundFactor > 1.0) {
                logger.warn(
                    "The bandwidth backoff lower bound factor must not be greater than 1: " +
                        bandwidthBackoffLowerBoundFactor
                )
                valid = false
            }
            if (trendlineObservationsWindowSize < 1) {
                logger.warn("The trendline window size must be at least 1: $trendlineObservationsWindowSize")
                valid = false
            }
            if (maxIncreaseFactor <= 0.0) {
                logger.warn("The maximum increase factor must be positive: $maxIncreaseFactor")
                valid = false
            }
            if (delayedIncreaseWindow <= Duration.ZERO) {
                logger.warn("The delayed increase window must be positive: $delayedIncreaseWindow")
                valid = false
            }
            if (highLossRateThreshold <= 0.0 || highLossRateThreshold > 1.0) {
                logger.warn("The high loss rate threshold must be in (0, 1]: $highLossRateThreshold")
            }

            return valid
        }
    }

    private data class Derivatives(
        var first: Double = 0.0,
        var second: Double = 0.0
    )

    private class Observation {
        var numPackets = 0
        var numLostPackets = 0
        var numReceivedPackets = 0
        var sendingRate = Bandwidth.MINUS_INFINITY
        var id = -1

        fun isInitialized() = id != -1
    }

    private class PartialObservation {
        var numPackets = 0
        var numLostPackets = 0
        var size = DataSize.ZERO
    }

    /** Returns `0.0` if not enough loss statistics have been received. */
    private fun getAverageReportedLossRatio(): Double {
        if (numObservations <= 0) {
            return 0.0
        }

        var numPackets = 0.0
        var numLostPackets = 0.0

        for (observation in observations) {
            if (!observation.isInitialized()) {
                continue
            }

            val instantTemporalWeight =
                instantUpperBoundTemporalWeights[
                    (numObservations - 1) -
                        observation.id
                ]
            numPackets += instantTemporalWeight * observation.numPackets
            numLostPackets += instantTemporalWeight * observation.numLostPackets
        }

        return numLostPackets / numPackets
    }

    private fun getCandidateBandwidthUpperBound(): Bandwidth {
        var candidateBandwidthUpperBound = maxBitrate
        if (isBandwidthLimitedDueToLoss() && isValid(bandwidthLimitInCurrentWindow)) {
            candidateBandwidthUpperBound = bandwidthLimitInCurrentWindow
        }

        if (config.trendlineIntegrationEnabled) {
            candidateBandwidthUpperBound = min(getInstantUpperBound(), candidateBandwidthUpperBound)
            if (isValid(delayBasedEstimate)) {
                candidateBandwidthUpperBound = min(delayBasedEstimate, candidateBandwidthUpperBound)
            }
        }

        if (acknowledgedBitrate == null) {
            return candidateBandwidthUpperBound
        }

        if (config.rampupAccelerationMaxFactor > 0.0) {
            val timeSinceBandwidthReduced = min(
                config.rampupAccelerationMaxoutTime,
                max(Duration.ZERO, Duration.between(lastTimeEstimateReduced, lastSendTimeMostRecentObservation))
            )
            val rampupAcceleration = config.rampupAccelerationMaxFactor *
                (timeSinceBandwidthReduced / config.rampupAccelerationMaxoutTime)

            candidateBandwidthUpperBound += acknowledgedBitrate!! * rampupAcceleration
        }

        return candidateBandwidthUpperBound
    }

    private fun getCandidates(inAlr: Boolean): List<ChannelParameters> {
        val bandwidths = mutableListOf<Bandwidth>()
        val canIncreaseBitrate = trendlineEsimateAllowBitrateIncrease()
        for (candidateFactor in config.candidateFactors) {
            if (!canIncreaseBitrate && candidateFactor > 1.0) {
                continue
            }
            bandwidths.add(currentEstimate.lossLimitedBandwidth * candidateFactor)
        }

        if (acknowledgedBitrate != null &&
            config.appendAcknowledgedRateCandidate &&
            trendlineEsimateAllowEmergencyBackoff()
        ) {
            if (!(config.notUseAckedRateInAlr && inAlr)) {
                bandwidths.add(acknowledgedBitrate!! * config.bandwidthBackoffLowerBoundFactor)
            }
        }

        if (isValid(delayBasedEstimate) && config.appendDelayBasedEstimateCandidate) {
            if (canIncreaseBitrate && delayBasedEstimate > currentEstimate.lossLimitedBandwidth) {
                bandwidths.add(delayBasedEstimate)
            }
        }

        val candidateBandwidthUpperBound = getCandidateBandwidthUpperBound()

        val candidates = mutableListOf<ChannelParameters>()
        for (i in bandwidths.indices) {
            val candidate = currentEstimate.copy()
            if (config.trendlineIntegrationEnabled) {
                candidate.lossLimitedBandwidth = min(bandwidths[i], candidateBandwidthUpperBound)
            } else {
                candidate.lossLimitedBandwidth = min(
                    bandwidths[i],
                    max(currentEstimate.lossLimitedBandwidth, candidateBandwidthUpperBound)
                )
            }
            candidate.inherentLoss = getFeasibleInherentLoss(candidate)
            candidates.add(candidate)
        }
        check(candidates.size == bandwidths.size)
        return candidates
    }

    private fun getDerivatives(channelParameters: ChannelParameters): Derivatives {
        val derivatives = Derivatives()

        for (observation in observations) {
            if (!observation.isInitialized()) {
                continue
            }

            val lossProbability = getLossProbability(
                channelParameters.inherentLoss,
                channelParameters.lossLimitedBandwidth,
                observation.sendingRate
            )

            val temporalWeight = temporalWeights[(numObservations - 1) - observation.id]

            derivatives.first +=
                temporalWeight *
                (
                    (observation.numLostPackets / lossProbability) -
                        (observation.numReceivedPackets / (1.0 - lossProbability))
                    )
            derivatives.second -=
                temporalWeight *
                (
                    (observation.numLostPackets / lossProbability.pow(2)) +
                        (
                            observation.numReceivedPackets /
                                (1.0 - lossProbability).pow(2)
                            )
                    )
        }

        if (derivatives.second >= 0.0) {
            logger.error(
                "The second derivative is mathematically guaranteed " +
                    "to be negative but is ${derivatives.second}."
            )
            derivatives.second = -1.0e6
        }

        return derivatives
    }

    private fun getFeasibleInherentLoss(channelParameters: ChannelParameters): Double {
        return min(
            max(channelParameters.inherentLoss, config.inherentLossLowerBound),
            getInherentLossUpperBound(channelParameters.lossLimitedBandwidth)
        )
    }

    private fun getInherentLossUpperBound(bandwidth: Bandwidth): Double {
        if (bandwidth == Bandwidth.ZERO) {
            return 1.0
        }

        val inherentLossUpperBound =
            config.inherentLossUpperBoundOffset +
                config.inherentLossUpperBoundBandwidthBalance / bandwidth

        return min(inherentLossUpperBound, 1.0)
    }

    private fun adjustBiasFactor(lossRate: Double, biasFactor: Double): Double {
        return biasFactor *
            (config.lossThresholdOfHighBandwidthPreference - lossRate) /
            (
                config.bandwidthPreferenceSmoothingFactor +
                    abs(config.lossThresholdOfHighBandwidthPreference - lossRate)
                )
    }

    private fun getHighBandwidthBias(bandwidth: Bandwidth): Double {
        if (isValid(bandwidth)) {
            val averageReportedLossRatio = getAverageReportedLossRatio()
            return adjustBiasFactor(averageReportedLossRatio, config.higherBandwidthBiasFactor) *
                bandwidth.kbps +
                adjustBiasFactor(averageReportedLossRatio, config.higherLogBandwidthBiasFactor) *
                ln(1.0 + bandwidth.kbps)
        }
        return 0.0
    }

    private fun getObjective(channelParameters: ChannelParameters): Double {
        var objective = 0.0

        val highBandwidthBias = getHighBandwidthBias(channelParameters.lossLimitedBandwidth)

        for (observation in observations) {
            if (!observation.isInitialized()) {
                continue
            }

            val lossProbability = getLossProbability(
                channelParameters.inherentLoss,
                channelParameters.lossLimitedBandwidth,
                observation.sendingRate
            )

            val temporalWeight = temporalWeights[(numObservations - 1) - observation.id]

            objective +=
                temporalWeight *
                (
                    (observation.numLostPackets * ln(lossProbability)) +
                        (observation.numReceivedPackets * ln(1.0 - lossProbability))
                    )
            objective +=
                temporalWeight * highBandwidthBias * observation.numPackets
        }

        return objective
    }

    private fun getSendingRate(instantaneousSendingRate: Bandwidth): Bandwidth {
        if (numObservations <= 0) {
            return instantaneousSendingRate
        }

        val mostRecentObservationIdx =
            (numObservations - 1) % config.observationWindowSize
        val mostRecentObservation = observations[mostRecentObservationIdx]
        val sendingRatePreviousObservation =
            mostRecentObservation.sendingRate

        return sendingRatePreviousObservation * config.sendingRateSmoothingFactor +
            instantaneousSendingRate * (1.0 - config.sendingRateSmoothingFactor)
    }

    private fun getInstantUpperBound(): Bandwidth {
        return cachedInstantUpperBound ?: maxBitrate
    }

    private fun calculateInstantUpperBound() {
        var instantLimit = maxBitrate
        val averageReportedLossRatio = getAverageReportedLossRatio()
        if (averageReportedLossRatio > config.instantUpperBoundLossOffset) {
            instantLimit = config.instantUpperBoundBandwidthBalance /
                (
                    averageReportedLossRatio -
                        config.instantUpperBoundLossOffset
                    )
            if (averageReportedLossRatio > config.highLossRateThreshold) {
                instantLimit = min(
                    instantLimit,
                    (
                        max(
                            minBitrate.kbps.toLong().toDouble(),
                            config.bandwidthCapAtHighLossRate.kbps.toLong().toDouble() -
                                config.slopeOfBweHighLossFunc *
                                averageReportedLossRatio
                        )
                        ).kbps
                )
            }
        }

        cachedInstantUpperBound = instantLimit
    }

    private fun calculateTemporalWeights() {
        for (i in 0 until config.observationWindowSize) {
            temporalWeights[i] = config.temporalWeightFactor.pow(i)
            instantUpperBoundTemporalWeights[i] = config.instantUpperBoundTemporalWeightFactor.pow(i)
        }
    }

    private fun newtonsMethodUpdate(channelParameters: ChannelParameters) {
        if (numObservations <= 0) {
            return
        }

        for (i in 0 until config.newtonIterations) {
            val derivatives = getDerivatives(channelParameters)
            channelParameters.inherentLoss -=
                config.newtonStepSize * derivatives.first / derivatives.second
            channelParameters.inherentLoss =
                getFeasibleInherentLoss(channelParameters)
        }
    }

    /** Returns false if there exists a kBwOverusing or kBwUnderusing in the window. */
    private fun trendlineEsimateAllowBitrateIncrease(): Boolean {
        if (!config.trendlineIntegrationEnabled) {
            return true
        }

        for (detectorState in delayDetectorStates) {
            if (detectorState == BandwidthUsage.kBwOverusing ||
                detectorState == BandwidthUsage.kBwUnderusing
            ) {
                return false
            }
        }
        return true
    }

    /** Returns true if there exists an overusing state in the window. */
    private fun trendlineEsimateAllowEmergencyBackoff(): Boolean {
        if (!config.trendlineIntegrationEnabled) {
            return true
        }

        if (!config.useAckedBitrateOnlyWhenOverusing) {
            return true
        }

        for (detectorState in delayDetectorStates) {
            if (detectorState == BandwidthUsage.kBwOverusing) {
                return true
            }
        }

        return false
    }

    /** Returns false if no observation was created. */
    private fun pushBackObservation(packetResults: List<PacketResult>, delayDetectorState: BandwidthUsage): Boolean {
        delayDetectorStates.addFirst(delayDetectorState)
        if (delayDetectorStates.size > config.trendlineObservationsWindowSize) {
            delayDetectorStates.removeLast()
        }

        if (packetResults.isEmpty()) {
            return false
        }

        val packetResultsSummary = getPacketResultsSummary(packetResults)

        partialObservation.numPackets += packetResultsSummary.numPackets
        partialObservation.numLostPackets += packetResultsSummary.numLostPackets
        partialObservation.size += packetResultsSummary.totalSize

        // This is the first packet report we have received.
        if (!isValid(lastSendTimeMostRecentObservation)) {
            lastSendTimeMostRecentObservation = packetResultsSummary.firstSendTime
        }

        val lastSendTime = packetResultsSummary.lastSendTime
        val observationDuration = Duration.between(lastSendTimeMostRecentObservation, lastSendTime)
        // Too small to be meaningful.
        if (observationDuration <= Duration.ZERO ||
            observationDuration < config.observationDurationLowerBound &&
            (
                delayDetectorState != BandwidthUsage.kBwOverusing ||
                    !config.trendlineIntegrationEnabled
                )
        ) {
            return false
        }

        lastSendTimeMostRecentObservation = lastSendTime

        val observation = Observation()
        observation.numPackets = partialObservation.numPackets
        observation.numLostPackets = partialObservation.numLostPackets
        observation.numReceivedPackets =
            observation.numPackets - observation.numLostPackets
        observation.sendingRate =
            getSendingRate(partialObservation.size.per(observationDuration))
        observation.id = numObservations++
        observations[observation.id % config.observationWindowSize] =
            observation

        partialObservation = PartialObservation()

        calculateInstantUpperBound()
        return true
    }

    private fun isEstimateIncreasingWhenLossLimited(bestCandidate: ChannelParameters): Boolean {
        return (
            currentEstimate.lossLimitedBandwidth < bestCandidate.lossLimitedBandwidth ||
                (
                    currentEstimate.lossLimitedBandwidth == bestCandidate.lossLimitedBandwidth &&
                        currentState == LossBasedState.kIncreasing
                    )
            ) &&
            isBandwidthLimitedDueToLoss()
    }

    private fun isBandwidthLimitedDueToLoss(): Boolean {
        return currentState != LossBasedState.kDelayBasedEstimate
    }

    private fun setProbeBitrate(probeBitrate: Bandwidth?) {
        if (probeBitrate != null && isValid(probeBitrate)) {
            this.probeBitrate = probeBitrate
            lastProbeTimestamp = lastSendTimeMostRecentObservation
        }
    }

    private fun isRequestingProbe(): Boolean {
        return currentState == LossBasedState.kIncreasing
    }

    companion object {
        private val defaultConfig = Config()

        val logger = createLogger()
    }
}
