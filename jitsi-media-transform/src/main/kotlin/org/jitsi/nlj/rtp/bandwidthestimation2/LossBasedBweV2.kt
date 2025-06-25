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
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.min
import org.jitsi.nlj.util.per
import org.jitsi.nlj.util.times
import org.jitsi.utils.div
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.max
import org.jitsi.utils.min
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.times
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Loss-based bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/loss_based_bwe_v2.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings are settable through the API, but not currently as config options.
 */
enum class LossBasedState {
    kIncreasing,

    // TODO(bugs.webrtc.org/12707): Remove one of the increasing states once we
    // have decided if padding is usefull for ramping up when BWE is loss
    // limited.
    kIncreaseUsingPadding,
    kDecreasing,
    kDelayBasedEstimate
}

private val kInitHoldDuration = 300.ms
private val kMaxHoldDuration = 60.secs

private fun isValid(datarate: Bandwidth?): Boolean {
    return datarate?.isFinite() ?: false
}

private fun isValid(timestamp: Instant) = timestamp.isFinite()

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
    private var currentBestEstimate: ChannelParameters =
        ChannelParameters(inherentLoss = config.initialInherentLossEstimate)
    private var numObservations = 0
    private val observations = MutableList(config.observationWindowSize) { Observation() }
    private var partialObservation = PartialObservation()
    private var lastSendTimeMostRecentObservation = Instant.MAX
    private var lastTimeEstimateReduced = Instant.MIN
    private var cachedInstantUpperBound: Bandwidth? = null
    private var cachedInstantLowerBound: Bandwidth? = null
    private val instantUpperBoundTemporalWeights = MutableList(config.observationWindowSize) { 0.0 }
    private val temporalWeights = MutableList(config.observationWindowSize) { 0.0 }
    private var recoveringAfterLossTimestamp = Instant.MIN
    private var bandwidthLimitInCurrentWindow = Bandwidth.INFINITY
    private var minBitrate = 1.kbps
    private var maxBitrate = Bandwidth.INFINITY
    private var delayBasedEstimate = Bandwidth.INFINITY
    private var lossBasedResult = Result()
    private var lastHoldInfo = HoldInfo(duration = kInitHoldDuration)
    private var lastPaddingInfo = PaddingInfo()
    private var averageReportedLossRatio = 0.0

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

    data class Result(
        var bandwidthEstimate: Bandwidth = Bandwidth.ZERO,
        var state: LossBasedState = LossBasedState.kDelayBasedEstimate
    )

    fun isEnabled(): Boolean = config.enabled

    /** Returns true iff a BWE can be calculated, i.e., the estimator has been
     initialized with a BWE and then has received enough `PacketResult`s.
     */
    fun isReady(): Boolean {
        return isEnabled() &&
            isValid(currentBestEstimate.lossLimitedBandwidth) &&
            numObservations >= config.minNumObservations
    }

    fun readyToUseInStartPhase(): Boolean {
        return isReady() && config.useInStartPhase
    }

    fun useInStartPhase(): Boolean {
        return config.useInStartPhase
    }

    /** Returns [Bandwidth.INFINITY] if no BWE can be calculated. */
    fun getLossBasedResult(): Result {
        if (!isReady()) {
            if (!isEnabled()) {
                logger.warn("The estimator must be enabled before it can be used.")
            } else {
                if (!isValid(currentBestEstimate.lossLimitedBandwidth)) {
                    logger.warn("The estimator must be initialized before it can be used.")
                }
                if (numObservations <= config.minNumObservations) {
                    logger.warn("The estimator must receive enough loss statistics before it can be used.")
                }
            }

            return Result(
                bandwidthEstimate = if (isValid(delayBasedEstimate)) {
                    delayBasedEstimate
                } else {
                    Bandwidth.INFINITY
                },
                state = LossBasedState.kDelayBasedEstimate
            )
        }
        return lossBasedResult.copy()
    }

    fun setAcknowledgedBitrate(acknowledgedBitrate: Bandwidth) {
        if (isValid(acknowledgedBitrate)) {
            this.acknowledgedBitrate = acknowledgedBitrate
            calculateInstantLowerBound()
        } else {
            logger.warn("The acknowledged bitrate must be finite: $acknowledgedBitrate")
        }
    }

    fun setMinMaxBitrate(minBitrate: Bandwidth, maxBitrate: Bandwidth) {
        if (isValid(minBitrate)) {
            this.minBitrate = minBitrate
            calculateInstantLowerBound()
        } else {
            logger.warn("The min bitrate must be finite: $minBitrate")
        }

        if (isValid(maxBitrate)) {
            this.maxBitrate = maxBitrate
        } else {
            logger.warn("The max bitrate must be finite: $maxBitrate")
        }
    }

    fun updateBandwidthEstimate(packetResults: List<PacketResult>, delayBasedEstimate: Bandwidth, inAlr: Boolean) {
        this.delayBasedEstimate = delayBasedEstimate
        if (!isEnabled()) {
            logger.warn("The estimator must be enabled before it can be used.")
            return
        }

        if (packetResults.isEmpty()) {
            logger.debug("The estimate cannot be updated without any loss statistics.")
            return
        }

        if (!pushBackObservation(packetResults)) {
            return
        }

        if (!isValid(currentBestEstimate.lossLimitedBandwidth)) {
            if (!isValid(delayBasedEstimate)) {
                logger.warn("The delay based estimate must be finite: $delayBasedEstimate.")
                return
            }
            currentBestEstimate.lossLimitedBandwidth = delayBasedEstimate
            lossBasedResult = Result(
                bandwidthEstimate = delayBasedEstimate,
                state = LossBasedState.kDelayBasedEstimate
            )
        }

        var bestCandidate = currentBestEstimate
        var objectiveMax = -Double.MAX_VALUE
        for (candidate in getCandidates(inAlr)) {
            newtonsMethodUpdate(candidate)

            val candidateObjective = getObjective(candidate)
            if (candidateObjective > objectiveMax) {
                objectiveMax = candidateObjective
                bestCandidate = candidate
            }
        }

        if (bestCandidate.lossLimitedBandwidth < currentBestEstimate.lossLimitedBandwidth) {
            lastTimeEstimateReduced = lastSendTimeMostRecentObservation
        }

        // Do not increase the estimate if the average loss is greater than current
        // inherent loss.
        if (averageReportedLossRatio > bestCandidate.inherentLoss &&
            config.notIncreaseIfInherentLossLessThanAverageLoss &&
            currentBestEstimate.lossLimitedBandwidth < bestCandidate.lossLimitedBandwidth
        ) {
            bestCandidate.lossLimitedBandwidth = currentBestEstimate.lossLimitedBandwidth
        }

        if (isInLossLimitedState()) {
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

            val increasingWhenLossLimited =
                isEstimateIncreasingWhenLossLimited(
                    oldEstimate = currentBestEstimate.lossLimitedBandwidth,
                    newEstimate = bestCandidate.lossLimitedBandwidth
                )
            // Bound the best candidate by the acked bitrate.
            if (increasingWhenLossLimited && isValid(acknowledgedBitrate)) {
                val rampupFactor = if (isValid(lastHoldInfo.rate) &&
                    acknowledgedBitrate!! < config.bandwidthRampupHoldThreshold * lastHoldInfo.rate
                ) {
                    config.bandwidthRampupUpperBoundFactorInHold
                } else {
                    config.bandwidthRampupUpperBoundFactor
                }
                bestCandidate.lossLimitedBandwidth =
                    max(
                        currentBestEstimate.lossLimitedBandwidth,
                        min(
                            bestCandidate.lossLimitedBandwidth,
                            rampupFactor * acknowledgedBitrate!!
                        )
                    )
                // Increase current estimate by at least 1kbps to make sure that the state
                // will be switched to kIncreasing, thus padding is triggered.
                if (lossBasedResult.state == LossBasedState.kDecreasing &&
                    bestCandidate.lossLimitedBandwidth == currentBestEstimate.lossLimitedBandwidth
                ) {
                    bestCandidate.lossLimitedBandwidth =
                        currentBestEstimate.lossLimitedBandwidth + 1.bps
                }
            }
        }

        var boundedBandwidthEstimate = Bandwidth.INFINITY
        boundedBandwidthEstimate = if (isValid(delayBasedEstimate)) {
            max(
                getInstantLowerBound(),
                minOf(
                    bestCandidate.lossLimitedBandwidth,
                    getInstantUpperBound(),
                    delayBasedEstimate
                )
            )
        } else {
            max(
                getInstantLowerBound(),
                min(bestCandidate.lossLimitedBandwidth, getInstantUpperBound())
            )
        }
        if (config.boundBestCandidate &&
            boundedBandwidthEstimate < bestCandidate.lossLimitedBandwidth
        ) {
            // If network is lossy, cap the best estimate by the instant upper bound,
            // e.g. 450kbps if loss rate is 50%.
            // Otherwise, cap the estimate by the delay-based estimate or max_bitrate.
            currentBestEstimate.lossLimitedBandwidth = boundedBandwidthEstimate
            currentBestEstimate.inherentLoss = 0.0
        } else {
            currentBestEstimate = bestCandidate
            if (config.lowerBoundByAckedRateFactor > 0.0) {
                currentBestEstimate.lossLimitedBandwidth =
                    max(currentBestEstimate.lossLimitedBandwidth, getInstantLowerBound())
            }
        }

        if (lossBasedResult.state == LossBasedState.kDecreasing &&
            lastHoldInfo.timestamp > lastSendTimeMostRecentObservation &&
            boundedBandwidthEstimate < delayBasedEstimate
        ) {
            // Ensure that acked rate is the lower bound of HOLD rate
            if (config.lowerBoundByAckedRateFactor > 0.0) {
                lastHoldInfo.rate =
                    max(getInstantLowerBound(), lastHoldInfo.rate)
            }
            // BWE is not allowed to increase above the HOLD rate. The purpose of
            // HOLD is to not immediately ramp up BWE to a rate that may cause loss.
            lossBasedResult.bandwidthEstimate =
                min(lastHoldInfo.rate, boundedBandwidthEstimate)
            return
        }

        if (isEstimateIncreasingWhenLossLimited(
                oldEstimate = lossBasedResult.bandwidthEstimate,
                newEstimate = boundedBandwidthEstimate
            ) &&
            canKeepIncreasingState(boundedBandwidthEstimate) &&
            boundedBandwidthEstimate < delayBasedEstimate &&
            boundedBandwidthEstimate < maxBitrate
        ) {
            if (config.paddingDuration > Duration.ZERO &&
                boundedBandwidthEstimate > lastPaddingInfo.paddingRate
            ) {
                // Start a new padding duration.
                lastPaddingInfo.paddingRate = boundedBandwidthEstimate
                lastPaddingInfo.paddingTimestamp = lastSendTimeMostRecentObservation
            }
            lossBasedResult.state = if (config.paddingDuration > Duration.ZERO) {
                LossBasedState.kIncreaseUsingPadding
            } else {
                LossBasedState.kIncreasing
            }
        } else if (boundedBandwidthEstimate < delayBasedEstimate &&
            boundedBandwidthEstimate < maxBitrate
        ) {
            if (lossBasedResult.state != LossBasedState.kDecreasing &&
                config.holdDurationFactor > 0
            ) {
                logger.info {
                    "Switch to HOLD. Bounded BWE: $boundedBandwidthEstimate, duration: ${lastHoldInfo.duration}"
                }
                lastHoldInfo = HoldInfo(
                    timestamp = lastSendTimeMostRecentObservation + lastHoldInfo.duration,
                    duration = min(kMaxHoldDuration, lastHoldInfo.duration * config.holdDurationFactor),
                    rate = boundedBandwidthEstimate
                )
            }
            lossBasedResult.state = LossBasedState.kDecreasing
        } else {
            // Reset the HOLD info if delay based estimate works to avoid getting
            // stuck in low bitrate.
            lastHoldInfo = HoldInfo(
                timestamp = Instant.MIN,
                duration = kInitHoldDuration,
                rate = Bandwidth.INFINITY
            )
            lossBasedResult.state = LossBasedState.kDelayBasedEstimate
        }
        lossBasedResult.bandwidthEstimate = boundedBandwidthEstimate

        if (isInLossLimitedState() &&
            (
                recoveringAfterLossTimestamp.isInfinite() ||
                    recoveringAfterLossTimestamp + config.delayedIncreaseWindow <
                    lastSendTimeMostRecentObservation
                )
        ) {
            bandwidthLimitInCurrentWindow =
                max(
                    kCongestionControllerMinBitrate,
                    currentBestEstimate.lossLimitedBandwidth * config.maxIncreaseFactor
                )
            recoveringAfterLossTimestamp = lastSendTimeMostRecentObservation
        }
    }

    fun getMedianSendingRate(): Bandwidth {
        val sendingRates = mutableListOf<Bandwidth>()
        for (observation in observations) {
            if (!observation.isInitialized() ||
                !isValid(observation.sendingRate) ||
                observation.sendingRate == Bandwidth.ZERO
            ) {
                continue
            }
            sendingRates.add(observation.sendingRate)
        }
        if (sendingRates.isEmpty()) {
            return Bandwidth.ZERO
        }
        sendingRates.sort()
        if (sendingRates.size % 2 == 0) {
            return (
                sendingRates[sendingRates.size / 2 - 1] + sendingRates[sendingRates.size / 2]
                ) / 2
        }
        return sendingRates[sendingRates.size / 2]
    }

    // For unit testing only
    fun setBandwidthEstimate(bandwidthEstimate: Bandwidth) {
        if (isValid(bandwidthEstimate)) {
            currentBestEstimate.lossLimitedBandwidth = bandwidthEstimate
            lossBasedResult = Result(
                bandwidthEstimate = bandwidthEstimate,
                state = LossBasedState.kDelayBasedEstimate
            )
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
        val bandwidthRampupUpperBoundFactor: Double = 1.5,
        val bandwidthRampupUpperBoundFactorInHold: Double = 1.2,
        val bandwidthRampupHoldThreshold: Double = 1.3,
        val rampupAccelerationMaxFactor: Double = 0.0,
        val rampupAccelerationMaxoutTime: Duration = 60.secs,
        val candidateFactors: DoubleArray = doubleArrayOf(1.02, 1.0, 0.95),
        val higherBandwidthBiasFactor: Double = 0.0002,
        val higherLogBandwidthBiasFactor: Double = 0.02,
        val inherentLossLowerBound: Double = 1.0e-3,
        val lossThresholdOfHighBandwidthPreference: Double = 0.2,
        val bandwidthPreferenceSmoothingFactor: Double = 0.002,
        val inherentLossUpperBoundBandwidthBalance: Bandwidth = 100.kbps,
        val inherentLossUpperBoundOffset: Double = 0.05,
        val initialInherentLossEstimate: Double = 0.01,
        val newtonIterations: Int = 1,
        val newtonStepSize: Double = 0.75,
        val appendAcknowledgedRateCandidate: Boolean = true,
        val appendDelayBasedEstimateCandidate: Boolean = true,
        val appendUpperBoundCandidateInAlr: Boolean = false,
        val observationDurationLowerBound: Duration = 250.ms,
        val observationWindowSize: Int = 15,
        val sendingRateSmoothingFactor: Double = 0.0,
        val instantUpperBoundTemporalWeightFactor: Double = 0.9,
        val instantUpperBoundBandwidthBalance: Bandwidth = 100.kbps,
        val instantUpperBoundLossOffset: Double = 0.05,
        val temporalWeightFactor: Double = 0.9,
        val bandwidthBackoffLowerBoundFactor: Double = 1.0,
        val maxIncreaseFactor: Double = 1.3,
        val delayedIncreaseWindow: Duration = 300.ms,
        val notIncreaseIfInherentLossLessThanAverageLoss: Boolean = true,
        val notUseAckedRateInAlr: Boolean = true,
        val useInStartPhase: Boolean = true,
        val minNumObservations: Int = 3,
        val lowerBoundByAckedRateFactor: Double = 1.0,
        val holdDurationFactor: Double = 2.0,
        val useByteLossRate: Boolean = true,
        val paddingDuration: Duration = 2.secs,
        val boundBestCandidate: Boolean = true,
        val medianSendingRateFactor: Double = 2.0
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
            if (bandwidthRampupUpperBoundFactorInHold <= 1.0) {
                logger.warn(
                    "The bandwidth rampup upper bound factor in hold must be greater than 1: " +
                        bandwidthRampupUpperBoundFactorInHold
                )
                valid = false
            }
            if (bandwidthRampupHoldThreshold < 0.0) {
                logger.warn(
                    "The bandwidth rampup hold threshold must be non-negative.: " +
                        bandwidthRampupHoldThreshold
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
            if (maxIncreaseFactor <= 0.0) {
                logger.warn("The maximum increase factor must be positive: $maxIncreaseFactor")
                valid = false
            }
            if (delayedIncreaseWindow <= Duration.ZERO) {
                logger.warn("The delayed increase window must be positive: $delayedIncreaseWindow")
                valid = false
            }
            if (minNumObservations <= 0) {
                logger.warn("The min number of observations must be positive: $minNumObservations")
                valid = false
            }
            if (lowerBoundByAckedRateFactor < 0.0) {
                logger.warn(
                    "The estimate lower bound by acknowledged rate factor must be non-negative: " +
                        lowerBoundByAckedRateFactor
                )
                valid = false
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
        var size = DataSize.ZERO
        var lostSize = DataSize.ZERO
        var id = -1

        fun isInitialized() = id != -1
    }

    private class PartialObservation {
        var numPackets = 0
        val lostPackets = HashMap<Long, DataSize>()
        var size = DataSize.ZERO
    }

    private class PaddingInfo {
        var paddingRate = Bandwidth.MINUS_INFINITY
        var paddingTimestamp = Instant.MIN
    }

    private class HoldInfo(
        var timestamp: Instant = Instant.MIN,
        var duration: Duration = Duration.ZERO,
        var rate: Bandwidth = Bandwidth.INFINITY
    )

    /** Returns `0.0` if not enough loss statistics have been received. */
    private fun updateAverageReportedLossRatio() {
        averageReportedLossRatio = if (config.useByteLossRate) {
            calculateAverageReportedByteLossRatio()
        } else {
            calculateAverageReportedPacketLossRatio()
        }
    }

    private fun calculateAverageReportedPacketLossRatio(): Double {
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

    // Calculates the average loss ratio over the last `observation_window_size`
    // observations but skips the observation with min and max loss ratio in order
    // to filter out loss spikes.
    private fun calculateAverageReportedByteLossRatio(): Double {
        if (numObservations <= 0) {
            return 0.0
        }

        var totalBytes = DataSize.ZERO
        var lostBytes = DataSize.ZERO
        var minLossRate = 1.0
        var maxLossRate = 0.0
        var minLostBytes = DataSize.ZERO
        var maxLostBytes = DataSize.ZERO
        var minBytesReceived = DataSize.ZERO
        var maxBytesReceived = DataSize.ZERO

        var sendRateOfMaxLossObservation = Bandwidth.ZERO
        for (observation in observations) {
            if (!observation.isInitialized()) {
                continue
            }

            val instantTemporalWeight =
                instantUpperBoundTemporalWeights[(numObservations - 1) - observation.id]
            totalBytes += observation.size * instantTemporalWeight
            lostBytes += observation.lostSize * instantTemporalWeight

            val lossRate = if (observation.size != DataSize.ZERO) {
                observation.lostSize / observation.size
            } else {
                0.0
            }
            if (numObservations > 3) {
                if (lossRate > maxLossRate) {
                    maxLossRate = lossRate
                    maxLostBytes = instantTemporalWeight * observation.lostSize
                    maxBytesReceived = instantTemporalWeight * observation.size
                    sendRateOfMaxLossObservation = observation.sendingRate
                }
                if (lossRate < minLossRate) {
                    minLossRate = lossRate
                    minLostBytes = instantTemporalWeight * observation.lostSize
                    minBytesReceived = instantTemporalWeight * observation.size
                }
            }
        }
        if (getMedianSendingRate() * config.medianSendingRateFactor <=
            sendRateOfMaxLossObservation
        ) {
            // If the median sending rate is less than half of the sending rate of the
            // observation with max loss rate, i.e. we suddenly send a lot of data, then
            // the loss rate might not be due to a spike.
            return lostBytes / totalBytes
        }

        if (totalBytes == maxBytesReceived + minBytesReceived) {
            // It could happen if the observation window was 2.
            return lostBytes / totalBytes
        }

        return (lostBytes - minLostBytes - maxLostBytes) /
            (totalBytes - maxBytesReceived - minBytesReceived)
    }

    private fun getCandidateBandwidthUpperBound(): Bandwidth {
        var candidateBandwidthUpperBound = maxBitrate
        if (isInLossLimitedState() && isValid(bandwidthLimitInCurrentWindow)) {
            candidateBandwidthUpperBound = bandwidthLimitInCurrentWindow
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

            candidateBandwidthUpperBound += rampupAcceleration * acknowledgedBitrate!!
        }

        return candidateBandwidthUpperBound
    }

    private fun getCandidates(inAlr: Boolean): List<ChannelParameters> {
        val bestEstimate = currentBestEstimate.copy()
        val bandwidths = mutableListOf<Bandwidth>()
        for (candidateFactor in config.candidateFactors) {
            bandwidths.add(candidateFactor * currentBestEstimate.lossLimitedBandwidth)
        }

        if (acknowledgedBitrate != null &&
            config.appendAcknowledgedRateCandidate
        ) {
            if (!(config.notUseAckedRateInAlr && inAlr) ||
                (
                    config.paddingDuration > Duration.ZERO &&
                        lastPaddingInfo.paddingTimestamp + config.paddingDuration >= lastSendTimeMostRecentObservation
                    )
            ) {
                bandwidths.add(acknowledgedBitrate!! * config.bandwidthBackoffLowerBoundFactor)
            }
        }

        if (isValid(delayBasedEstimate) && config.appendDelayBasedEstimateCandidate) {
            if (delayBasedEstimate > bestEstimate.lossLimitedBandwidth) {
                bandwidths.add(delayBasedEstimate)
            }
        }

        if (inAlr && config.appendUpperBoundCandidateInAlr &&
            bestEstimate.lossLimitedBandwidth > getInstantUpperBound()
        ) {
            bandwidths.add(getInstantUpperBound())
        }

        val candidateBandwidthUpperBound = getCandidateBandwidthUpperBound()

        val candidates = mutableListOf<ChannelParameters>()
        for (i in bandwidths.indices) {
            val candidate = bestEstimate.copy()
            candidate.lossLimitedBandwidth = min(
                bandwidths[i],
                max(bestEstimate.lossLimitedBandwidth, candidateBandwidthUpperBound)
            )
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

            if (config.useByteLossRate) {
                derivatives.first +=
                    temporalWeight *
                    (
                        (
                            observation.lostSize.kiloBytes / lossProbability -
                                ((observation.size - observation.lostSize).kiloBytes) / (1.0 - lossProbability)
                            )
                        )
                derivatives.second -=
                    temporalWeight *
                    (
                        (
                            observation.lostSize.kiloBytes / lossProbability.pow(2) +
                                (observation.size - observation.lostSize).kiloBytes / (1.0 - lossProbability).pow(2)
                            )
                        )
            } else {
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

            if (config.useByteLossRate) {
                objective +=
                    temporalWeight *
                    (
                        (observation.lostSize.kiloBytes * ln(lossProbability)) +
                            (
                                (observation.size - observation.lostSize).kiloBytes * ln(1.0 - lossProbability)
                                )
                        )
                objective +=
                    temporalWeight * highBandwidthBias * observation.size.kiloBytes
            } else {
                objective +=
                    temporalWeight *
                    (
                        (observation.numLostPackets * ln(lossProbability)) +
                            (observation.numReceivedPackets * ln(1.0 - lossProbability))
                        )
                objective +=
                    temporalWeight * highBandwidthBias * observation.numPackets
            }
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

        return config.sendingRateSmoothingFactor * sendingRatePreviousObservation +
            (1.0 - config.sendingRateSmoothingFactor) * instantaneousSendingRate
    }

    private fun getInstantUpperBound(): Bandwidth {
        return cachedInstantUpperBound ?: maxBitrate
    }

    private fun calculateInstantUpperBound() {
        var instantLimit = maxBitrate
        if (averageReportedLossRatio > config.instantUpperBoundLossOffset) {
            instantLimit = config.instantUpperBoundBandwidthBalance /
                (
                    averageReportedLossRatio -
                        config.instantUpperBoundLossOffset
                    )
        }

        cachedInstantUpperBound = instantLimit
    }

    private fun getInstantLowerBound(): Bandwidth {
        return cachedInstantLowerBound ?: Bandwidth.ZERO
    }

    private fun calculateInstantLowerBound() {
        var instanceLowerBound = Bandwidth.ZERO
        if (isValid(acknowledgedBitrate) &&
            config.lowerBoundByAckedRateFactor > 0.0
        ) {
            instanceLowerBound = config.lowerBoundByAckedRateFactor * acknowledgedBitrate!!
        }
        if (isValid(minBitrate)) {
            instanceLowerBound = max(instanceLowerBound, minBitrate)
        }
        cachedInstantLowerBound = instanceLowerBound
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

    /** Returns false if no observation was created. */
    private fun pushBackObservation(packetResults: List<PacketResult>): Boolean {
        if (packetResults.isEmpty()) {
            return false
        }

        partialObservation.numPackets += packetResults.size
        var lastSendTime = Instant.MIN
        var firstSendTime = Instant.MAX
        for (packet in packetResults) {
            if (packet.isReceived()) {
                partialObservation.lostPackets.remove(packet.sentPacket.sequenceNumber)
            } else {
                partialObservation.lostPackets[packet.sentPacket.sequenceNumber] = packet.sentPacket.size
            }
            partialObservation.size += packet.sentPacket.size
            lastSendTime = max(lastSendTime, packet.sentPacket.sendTime)
            firstSendTime = min(firstSendTime, packet.sentPacket.sendTime)
        }

        // This is the first packet report we have received.
        if (!isValid(lastSendTimeMostRecentObservation)) {
            lastSendTimeMostRecentObservation = firstSendTime
        }

        val observationDuration = Duration.between(lastSendTimeMostRecentObservation, lastSendTime)
        // Too small to be meaningful.
        // To consider: what if it is too long?, i.e. we did not receive any packets
        // for a long time, then all the packets we received are too old.
        if (observationDuration <= Duration.ZERO ||
            observationDuration < config.observationDurationLowerBound
        ) {
            return false
        }

        lastSendTimeMostRecentObservation = lastSendTime

        val observation = Observation()
        observation.numPackets = partialObservation.numPackets
        observation.numLostPackets = partialObservation.lostPackets.size
        observation.numReceivedPackets =
            observation.numPackets - observation.numLostPackets
        observation.sendingRate =
            getSendingRate(partialObservation.size.per(observationDuration))
        for ((_, packetSize) in partialObservation.lostPackets) {
            observation.lostSize += packetSize
        }
        observation.size = partialObservation.size
        observation.id = numObservations++
        observations[observation.id % config.observationWindowSize] =
            observation

        partialObservation = PartialObservation()

        updateAverageReportedLossRatio()
        calculateInstantUpperBound()
        return true
    }

    private fun isEstimateIncreasingWhenLossLimited(oldEstimate: Bandwidth, newEstimate: Bandwidth): Boolean {
        return (
            oldEstimate < newEstimate ||
                (
                    oldEstimate == newEstimate &&
                        (
                            lossBasedResult.state == LossBasedState.kIncreasing ||
                                lossBasedResult.state == LossBasedState.kIncreaseUsingPadding
                            )
                    )
            ) &&
            isInLossLimitedState()
    }

    private fun isInLossLimitedState(): Boolean {
        return lossBasedResult.state != LossBasedState.kDelayBasedEstimate
    }

    private fun canKeepIncreasingState(estimate: Bandwidth): Boolean {
        if (config.paddingDuration == Duration.ZERO ||
            lossBasedResult.state != LossBasedState.kIncreaseUsingPadding
        ) {
            return true
        }

        // Keep using the kIncreaseUsingPadding if either the state has been
        // kIncreaseUsingPadding for less than kPaddingDuration or the estimate
        // increases.
        return lastPaddingInfo.paddingTimestamp + config.paddingDuration >=
            lastSendTimeMostRecentObservation ||
            lastPaddingInfo.paddingRate < estimate
    }

    companion object {
        private val defaultConfig = Config()

        val logger = createLogger()
    }
}
