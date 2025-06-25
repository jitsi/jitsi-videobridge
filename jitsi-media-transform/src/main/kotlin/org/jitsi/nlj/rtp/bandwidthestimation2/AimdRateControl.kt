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
// This file uses WebRTC's naming style for enums
@file:Suppress("ktlint:standard:enum-entry-name-case", "ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.div
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.per
import org.jitsi.nlj.util.times
import org.jitsi.utils.NEVER
import org.jitsi.utils.coerceIn
import org.jitsi.utils.div
import org.jitsi.utils.durationOfDoubleSeconds
import org.jitsi.utils.max
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.times
import org.jitsi.utils.toDouble
import java.time.Duration
import java.time.Instant
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.pow

/**
 * A rate control implementation based on additive increases of bitrate when no
 * over-use is detected and multiplicative decreases when over-uses are
 * detected. When we think the available bandwidth has changes or is unknown, we
 * will switch to a "slow-start mode" where we increase multiplicatively.
 *
 * Based on WebRTC modules/remote_bitrate_estimator/aimd_rate_control.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
class AimdRateControl(private val sendSide: Boolean = false) {

    private var minConfiguredBitrate: Bandwidth = kCongestionControllerMinBitrate
    private var maxConfiguredBitrate: Bandwidth = 30000.kbps
    private var currentBitrate: Bandwidth = maxConfiguredBitrate
    private var latestEstimatedThroughput: Bandwidth = currentBitrate

    val linkCapacity = LinkCapacityEstimator()

    // Omitted:
    // val networkEstimate: NetworkStateEstimate? = null
    // As far as I can tell it is not ever set in current Chromium?

    var rateControlState: RateControlState = RateControlState.kRcHold
        private set

    private var timeLastBitrateChange: Instant = NEVER

    @SuppressFBWarnings("URF_UNREAD_FIELD")
    private var timeLastBitrateDecrease: Instant = NEVER
    private var timeFirstThroughputEstimate: Instant = NEVER

    private var bitrateIsInitialized: Boolean = false
    private var beta: Double = kDefaultBackoffFactor
    var inAlr: Boolean = false
    var rtt: Duration = kDefaultRtt

    // TODO: field trials: remove code that checks these

    // Allow the delay based estimate to only increase as long as application
    // limited region (alr) is not detected.
    private val noBitrateIncreaseInAlr: Boolean = false

    // Only tested when networkEstimate != null
    // private val disableEstimateBoundedIncrease: Boolean = false
    // private val useCurrentEstimateAsMinUpperBound = true

    private var lastDecrease: Bandwidth? = null

    fun setStartBitrate(startBitrate: Bandwidth) {
        currentBitrate = startBitrate
        latestEstimatedThroughput = startBitrate
        bitrateIsInitialized = true
    }

    fun setMinBitrate(minBitrate: Bandwidth) {
        minConfiguredBitrate = minBitrate
        currentBitrate = max(minBitrate, currentBitrate)
    }

    fun validEstimate(): Boolean = bitrateIsInitialized

    fun getFeedbackInterval(): Duration {
        // Estimate how often we can send RTCP if we allocate up to 5% of bandwidth
        // to feedback.
        val kRtcpSize = 80.bytes
        val rtcpBitrate = currentBitrate * 0.05
        val interval = kRtcpSize / rtcpBitrate
        val kMinFeedbackInterval = 200.ms
        val kMaxFeedbackInterval = 1000.ms
        return interval.coerceIn(kMinFeedbackInterval, kMaxFeedbackInterval)
    }

    fun timeToReduceFurther(atTime: Instant, estimatedThroughput: Bandwidth): Boolean {
        val bitrateReductionInterval = rtt.coerceIn(10.ms, 200.ms)
        if (Duration.between(timeLastBitrateChange, atTime) >= bitrateReductionInterval) {
            return true
        }
        if (validEstimate()) {
            // TODO(terelius/holmer): Investigate consequences of increasing
            // the threshold to 0.95 * LatestEstimate().
            val threshold = 0.5 * latestEstimate()
            return estimatedThroughput < threshold
        }
        return false
    }

    fun initialTimeToReduceFurther(atTime: Instant): Boolean {
        return validEstimate() &&
            timeToReduceFurther(atTime, latestEstimate() / 2 - 1.bps)
    }

    fun latestEstimate() = currentBitrate

    fun update(input: RateControlInput, atTime: Instant): Bandwidth {
        // Set the initial bit rate value to what we're receiving the first half
        // second.
        // TODO(bugs.webrtc.org/9379): The comment above doesn't match to the code.
        if (!bitrateIsInitialized) {
            val kInitializionTime = 5.secs
            check(kBitrateWindow <= kInitializionTime)
            if (timeFirstThroughputEstimate == NEVER) {
                if (input.estimatedThroughput != null) {
                    timeFirstThroughputEstimate = atTime
                }
            } else if (Duration.between(timeFirstThroughputEstimate, atTime) > kInitializionTime) {
                input.estimatedThroughput?.let {
                    currentBitrate = it
                    bitrateIsInitialized = true
                }
            }
        }
        changeBitrate(input, atTime)
        return currentBitrate
    }

    fun setEstimate(bitrate: Bandwidth, atTime: Instant) {
        bitrateIsInitialized = true
        val prevBitrate = currentBitrate
        currentBitrate = clampBitrate(bitrate)
        timeLastBitrateChange = atTime
        if (currentBitrate < prevBitrate) {
            timeLastBitrateDecrease = atTime
        }
    }

    fun getNearMaxIncreaseRateBpsPerSecond(): Double {
        check(currentBitrate != 0.bps)
        val kFrameInterval = 1.secs / 30.0
        val frameSize = (currentBitrate * kFrameInterval).toWholeBytes()
        val kPacketSize = 1200.bytes
        val packetsPerFrame = ceil(frameSize / kPacketSize)
        val avgPacketSize = (frameSize / packetsPerFrame).toWholeBytes()

        // Approximate the over-use estimator delay to 100 ms.
        var responseTime = rtt + 100.ms

        responseTime = responseTime * 2
        val increaseRateBpsPerSecond = (avgPacketSize.per(responseTime)).bps.toDouble()
        val kMinIncreaseRateBpsPerSecond = 4000.0
        return increaseRateBpsPerSecond.coerceAtLeast(kMinIncreaseRateBpsPerSecond)
    }

    fun getExpectedBandwidthPeriod(): Duration {
        val kMinPeriod = 2.secs
        val kDefaultPeriod = 3.secs
        val kMaxPeriod = 50.secs

        val increaseRateBpsPerSecond = getNearMaxIncreaseRateBpsPerSecond()
        if (lastDecrease == null) {
            return kDefaultPeriod
        }

        val timeToRecoverDecreaseSeconds =
            lastDecrease!!.bps / increaseRateBpsPerSecond

        val period = durationOfDoubleSeconds(timeToRecoverDecreaseSeconds)
        return period.coerceIn(kMinPeriod, kMaxPeriod)
    }

    private fun changeBitrate(input: RateControlInput, atTime: Instant) {
        var newBitrate: Bandwidth? = null
        val estimatedThroughput = input.estimatedThroughput ?: latestEstimatedThroughput
        input.estimatedThroughput?.let { latestEstimatedThroughput = it }

        // An over-use should always trigger us to reduce the bitrate, even though
        // we have not yet established our first estimate. By acting on the over-use,
        // we will end up with a valid estimate.
        if (!bitrateIsInitialized && input.bwState != BandwidthUsage.kBwOverusing) {
            return
        }

        changeState(input, atTime)

        when (rateControlState) {
            RateControlState.kRcHold -> Unit

            RateControlState.kRcIncrease -> {
                if (estimatedThroughput > linkCapacity.upperBound()) {
                    linkCapacity.reset()
                }

                // We limit the new bitrate based on the troughput to avoid unlimited
                // bitrate increases. We allow a bit more lag at very low rates to not too
                // easily get stuck if the encoder produces uneven outputs.

                var increaseLimit = 1.5 * estimatedThroughput + 10.kbps
                if (sendSide && inAlr && noBitrateIncreaseInAlr) {
                    // TODO this is dead code because we're not using the noBitrateIncreaseInAlr field trial
                    // Do not increase the delay based estimate in alr since the estimator
                    // will not be able to get transport feedback necessary to detect if
                    // the new estimate is correct.
                    // If we have previously increased above the limit (for instance due to
                    // probing), we don't allow further changes.
                    increaseLimit = currentBitrate
                }

                if (currentBitrate < increaseLimit) {
                    var increasedBitrate = Bandwidth.MINUS_INFINITY
                    if (linkCapacity.hasEstimate()) {
                        // The link_capacity estimate is reset if the measured throughput
                        // is too far from the estimate. We can therefore assume that our
                        // target rate is reasonably close to link capacity and use additive
                        // increase.
                        val additiveIncrease =
                            additiveRateIncrease(atTime, timeLastBitrateChange)
                        increasedBitrate = currentBitrate + additiveIncrease
                    } else {
                        // If we don't have an estimate of the link capacity, use faster ramp
                        // up to discover the capacity.
                        val multiplicativeIncrease = multiplicativeRateIncrease(
                            atTime,
                            timeLastBitrateChange,
                            currentBitrate
                        )
                        increasedBitrate = currentBitrate + multiplicativeIncrease
                    }
                    newBitrate = increasedBitrate.coerceAtMost(increaseLimit)
                }
                timeLastBitrateChange = atTime
            }
            RateControlState.kRcDecrease -> {
                var decreasedBitrate = Bandwidth.INFINITY

                // Set bit rate to something slightly lower than the measured throughput
                // to get rid of any self-induced delay.
                decreasedBitrate = estimatedThroughput * beta
                if (decreasedBitrate > 5.kbps) {
                    decreasedBitrate -= 5.kbps
                }
                if (decreasedBitrate > currentBitrate) {
                    // TODO(terelius): The link_capacity estimate may be based on old
                    // throughput measurements. Relying on them may lead to unnecessary
                    // BWE drops.
                    if (linkCapacity.hasEstimate()) {
                        decreasedBitrate = linkCapacity.estimate!! * beta
                    }
                }
                // Avoid increasing the rate when over-using.
                if (decreasedBitrate < currentBitrate) {
                    newBitrate = decreasedBitrate
                }

                if (bitrateIsInitialized && estimatedThroughput < currentBitrate) {
                    if (newBitrate == null) {
                        lastDecrease = Bandwidth.ZERO
                    } else {
                        lastDecrease = currentBitrate - newBitrate
                    }
                }
                if (estimatedThroughput < linkCapacity.lowerBound()) {
                    // The current throughput is far from the estimated link capacity. Clear
                    // the estimate to allow an immediate update in onOveruseDetected.
                    linkCapacity.reset()
                }

                bitrateIsInitialized = true
                linkCapacity.onOveruseDetected(estimatedThroughput)
                // Stay on hold until the pipes are cleared.
                rateControlState = RateControlState.kRcHold
                timeLastBitrateChange = atTime
                timeLastBitrateDecrease = atTime
            }
        }
        currentBitrate = clampBitrate(newBitrate ?: currentBitrate)
    }

    private fun clampBitrate(newBitrate: Bandwidth): Bandwidth {
        /* Skipping some conditions related to networkEstimate != null */

        return newBitrate.coerceAtLeast(minConfiguredBitrate)
    }

    private fun multiplicativeRateIncrease(atTime: Instant, lastTime: Instant, currentBitrate: Bandwidth): Bandwidth {
        var alpha = 1.08
        if (lastTime != NEVER) {
            val timeSinceLastUpdate = Duration.between(lastTime, atTime)
            alpha = alpha.pow(min(timeSinceLastUpdate.toDouble(), 1.0))
        }
        val multiplicativeIncrease =
            max(currentBitrate * (alpha - 1.0), 1000.bps)
        return multiplicativeIncrease
    }

    private fun additiveRateIncrease(atTime: Instant, lastTime: Instant): Bandwidth {
        val timePeriodSeconds = Duration.between(lastTime, atTime).toDouble()
        val dataRateIncreaseBps = getNearMaxIncreaseRateBpsPerSecond() * timePeriodSeconds
        return dataRateIncreaseBps.bps
    }

    private fun changeState(input: RateControlInput, atTime: Instant) {
        when (input.bwState) {
            BandwidthUsage.kBwNormal ->
                if (rateControlState == RateControlState.kRcHold) {
                    timeLastBitrateChange = atTime
                    rateControlState = RateControlState.kRcIncrease
                }
            BandwidthUsage.kBwOverusing ->
                if (rateControlState != RateControlState.kRcDecrease) {
                    rateControlState = RateControlState.kRcDecrease
                }
            BandwidthUsage.kBwUnderusing ->
                rateControlState = RateControlState.kRcHold
        }
    }

    enum class RateControlState {
        kRcHold,
        kRcIncrease,
        kRcDecrease
    }

    companion object {
        private val kDefaultRtt = 200.ms
        private const val kDefaultBackoffFactor = 0.85
    }
}
