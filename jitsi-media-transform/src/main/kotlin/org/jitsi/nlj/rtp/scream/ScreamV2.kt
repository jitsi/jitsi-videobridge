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

import org.jitsi.nlj.rtp.bandwidthestimation2.TransportPacketsFeedback
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.min
import org.jitsi.utils.NEVER
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.utils.max
import org.jitsi.utils.min
import org.jitsi.utils.ms
import org.jitsi.utils.toRoundedMillis
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** ScreamV2,
 * based on WebRTC modules/congestion_controller/scream/scream_v2.{h,cc} in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * Implements ScreamV2 based on the draft RFC in
 * https://datatracker.ietf.org/doc/draft-johansson-ccwg-rfc8298bis-screamv2/
 * Note, this class is currently in development and not all features are yet
 * implemented.
 * TODO: bugs.webrtc.org/447037083 - revisit this comment when implementation is
 * done.
 */
class ScreamV2(
    parentLogger: Logger,
    private val params: ScreamV2Parameters = ScreamV2Parameters()
) {
    private val logger = createChildLogger(parentLogger)

    fun setTargetBitrateConstraints(min: Bandwidth, max: Bandwidth, start: Bandwidth) {
        check(max >= min)
        minTargetBitrate = min
        maxTargetBitrate = max
        if (!firstFeedbackProcessed) {
            targetRate = start
        }
        logger.debug {
            "min_target_bitrate_=$minTargetBitrate max_target_bitrate_=$maxTargetBitrate " +
                "start_bitrate_=$targetRate"
        }
    }

    fun onPacketSent(dataInFlight: DataSize) {
        maxDataInFlightThisRtt = maxOf(maxDataInFlightThisRtt, dataInFlight)
    }

    fun onTransportPacketsFeedback(msg: TransportPacketsFeedback) {
        val feedback = parseScreamFeedback(msg, params)
        if (feedback.numReceivedPackets == 0) {
            logger.info("No received packets in feedback, ignoring.")
            return
        }
        maxDataInFlightThisRtt = maxOf(maxDataInFlightThisRtt, feedback.dataInFlight)
        updateReceiveRate(feedback)

        if (params.enableAlr) {
            isApplicationLimited = maxAllowedRefWindow() < refWindow &&
                receivedRate < targetRate.scaledBy(params.alrThreshold)
        }

        feedback.delayMetrics?.let { delayMetrics ->
            delayBasedCongestionControl.update(feedback, isApplicationLimited)

            if (!isApplicationLimited) {
                updateFeedbackHoldTime(delayMetrics.feedbackHoldTime)
            }
        }

        if (!firstFeedbackProcessed) {
            refWindow = maxOf(
                params.minRefWindow,
                dataSentAt(targetRate, delayBasedCongestionControl.rtt() + feedbackHoldTime)
            )
            logger.info {
                "Initial RTT: ${delayBasedCongestionControl.rtt().toRoundedMillis()} " +
                    "feedback_hold_time: ${feedbackHoldTime.toRoundedMillis()}ms, " +
                    "Start Bitrate: ${targetRate.toRoundedKbps()}kbps ref_window_=${refWindow.wholeBytes()}"
            }
            firstFeedbackProcessed = true
        }

        updateFeedbackInterval(feedback.feedbackTime)
        updateL4SAlpha(feedback)
        updateRefWindow(feedback)
        updateTargetRate(feedback)

        if (timeBetween(lastDataInFlightUpdate, feedback.feedbackTime) >=
            max(params.virtualRtt, delayBasedCongestionControl.rtt())
        ) {
            lastDataInFlightUpdate = feedback.feedbackTime
            maxDataInFlightPrevRtt = maxDataInFlightThisRtt
            maxDataInFlightThisRtt = DataSize.ZERO
        }
    }

    fun targetRate(): Bandwidth = min(maxTargetBitrate, targetRate)

    fun pacingRate(): Bandwidth {
        if (receivedRate.isFinite()) {
            return max(
                targetRate().scaledBy(params.pacingFactor),
                receivedRate.scaledBy(params.pacingRateReceivedFactor)
            )
        }
        return targetRate().scaledBy(params.pacingFactor)
    }

    fun rtt(): Duration = delayBasedCongestionControl.rtt()

    // Max data in flight before the send window is full.
    fun maxDataInFlight(): DataSize {
        // 4.3.1. Send Window Calculation
        val refWindowOverhead = params.refWindowOverheadMin +
            (params.refWindowOverheadMax - params.refWindowOverheadMin) *
            delayBasedCongestionControl.refWindowScaleFactorDueToAvgMinDelay(allowZero = true)

        return maxOf(refWindow.scaledBy(refWindowOverhead), params.maxSegmentSize * params.minMaxDataInFlightMss)
    }

    // Target for the upper limit of the number of bytes that can be in
    // flight (transmitted but not yet acknowledged)
    fun refWindow(): DataSize = refWindow

    // Last inflection point where ref_window started to decrease.
    fun refWindowI(): DataSize = refWindowI

    // Returns the maximum allowed reference window based on data in flight during
    // the last RTT.
    fun maxAllowedRefWindow(): DataSize {
        // 4.2.2.2.
        // Increase ref_window only if bytes in flight is large enough.
        // Quite a lot of slack is allowed here to avoid that bitrate locks to low
        // values.
        return maxOf(
            params.maxSegmentSize +
                maxOf(maxDataInFlightThisRtt, maxDataInFlightPrevRtt).scaledBy(params.bytesInFlightHeadRoom),
            params.minRefWindow
        )
    }

    fun receivedRate(): Bandwidth = receivedRate

    // Returns the average fraction of ECN-CE marked data units per RTT.
    fun l4sAlpha(): Double = l4sAlpha

    fun lossCongestionLevel(): Double = lossEstimator.congestionLevel()

    fun lastReferenceWindowDecreaseTime(): Instant = lastRefWindowDecreaseTime

    fun lastReactionToCongestionTime(): Instant = lastReactionToCongestionTime

    // Exposed for easier logging.
    fun delayBasedCongestionControl(): DelayBasedCongestionControl = delayBasedCongestionControl

    // Average time feedback is delayed in the receiver.
    fun feedbackHoldTime(): Duration = feedbackHoldTime

    // Ratio between `max_segment_size` and `ref_window_`.
    fun refWindowMssRatio(): Double = min(1.0, params.maxSegmentSize.ratioTo(refWindow))

    fun lastRefWindowIncreaseScaleFactor(): Double = lastRefWindowIncreaseScaleFactor

    // Scaling factor for reference window adjustment
    // when close to the last known inflection point.
    // (4.2.2.1)
    fun refWindowScaleFactorCloseToRefWindowI(): Double {
        val scaleFactor = params.backoffScaleFactorCloseToRefWindowI
        val scl = if (refWindow > refWindowI) {
            (refWindow - refWindowI).scaledBy(scaleFactor).ratioTo(refWindowI)
        } else {
            (refWindowI - refWindow).scaledBy(scaleFactor).ratioTo(refWindowI)
        }
        return (scl * scl).coerceIn(0.1, 1.0)
    }

    // Scale factor for reference window increase. (4.2.2.2)
    // Always > 1.0.
    fun refWindowMultiplicativeScaleFactor(): Double =
        1.0 + refWindow.scaledBy(params.multiplicativeIncreaseFactor).ratioTo(params.maxSegmentSize)

    fun isApplicationLimited(): Boolean = isApplicationLimited

    // Minimum interval between congestion backoff reactions.
    private fun reactionInterval(): Duration = if (params.useFeedbackIntervalForVirtualRtt) {
        max(params.virtualRtt, feedbackInterval)
    } else {
        params.virtualRtt
    }

    // Scales `backoff` so a full reduction is applied immediately, and a new
    // backoff cycle only starts one RTT after `data_in_flight <= ref_window_`.
    // Until then, only increases in `backoff` above `last_backoff_` are applied.
    private fun scaleBackoff(parsed: ScreamFeedback, backoff: Double): Double {
        if (!params.useNonLinearBackoffScale) {
            // Legacy linear scaling: spread `backoff` evenly across the estimated
            // number of feedbacks per RTT (`rtt / ReactionInterval()`).
            return backoff / max(1.0, delayBasedCongestionControl.rtt().ratioTo(reactionInterval()))
        }
        // During a burst (e.g. a keyframe or pacing burst), `data_in_flight` can
        // temporarily exceed `ref_window_`. Scale down the backoff proportionally so
        // `ref_window_` is not overly reduced due to queue buildup caused by the
        // excess in-flight bytes rather than `ref_window_` itself being too large.
        val refWindowShare = if (parsed.dataInFlight > refWindow.scaledBy(params.bytesInFlightHeadRoom)) {
            refWindow.ratioTo(parsed.dataInFlight)
        } else {
            1.0
        }

        if (timeBetween(lastRefWindowDecreaseTime, parsed.feedbackTime) < delayBasedCongestionControl.rtt() &&
            parsed.dataInFlight > refWindow
        ) {
            // State 1: Draining in-flight data after a window reduction.
            // A reduction in `ref_window_` does not reduce the bottleneck queue until
            // actual `data_in_flight` drops down to the reduced `ref_window_`. Keep
            // resetting the 1-RTT observation timer until `data_in_flight <=
            // ref_window_`, for at most one RTT after the last window reduction.
            lastBackoffRttStartTime = parsed.feedbackTime
        }
        if (timeBetween(lastBackoffRttStartTime, parsed.feedbackTime) >= delayBasedCongestionControl.rtt()) {
            // State 2: Start of a new backoff cycle.
            // Either this is the very first congestion event
            // (`last_backoff_rtt_start_time_` is MinusInfinity), or at least one full
            // RTT has elapsed since `data_in_flight` drained to `ref_window_` (or at
            // most two RTTs since the last window reduction). Apply the full `backoff`
            // immediately.
            lastBackoffRttStartTime = parsed.feedbackTime
            lastBackoff = backoff
            return backoff * refWindowShare
        }
        if (backoff > lastBackoff && lastBackoff < 1.0) {
            // State 3: Congestion is worsening within the current backoff cycle.
            // `l4s_alpha_` or `l4s_alpha_v()` has increased since the cycle started.
            // Apply only the incremental reduction `delta_backoff` such that
            // `(1 - last_backoff_) * (1 - delta_backoff) = 1 - backoff`, ensuring
            // the cumulative reduction over the cycle matches the peak `backoff`.
            val deltaBackoff = refWindowShare * (backoff - lastBackoff) / (1.0 - lastBackoff)
            lastBackoff = backoff
            return deltaBackoff
        }
        // State 4: Waiting for the current backoff to take effect.
        // Within the current cycle and `backoff <= last_backoff_`; do not reduce
        // `ref_window_` further while waiting one RTT after `data_in_flight <=
        // ref_window_` to observe the effect of the already-applied reduction.
        return 0.0
    }

    private fun updateL4SAlpha(parsed: ScreamFeedback) {
        // 4.2.1.3.
        if (parsed.numReceivedPackets == 0) {
            return
        }

        val stepsPerRtt = if (params.scaleL4sAlphaWithRtt) {
            max(1.0, delayBasedCongestionControl.rtt().ratioTo(reactionInterval()))
        } else {
            1.0
        }
        val fractionMarked = parsed.numCeMarkedPackets.toDouble() / parsed.numReceivedPackets
        val g = params.l4sAvgG / stepsPerRtt
        l4sAlpha = (g * fractionMarked + (1.0 - g) * l4sAlpha).coerceIn(0.0, 1.0)
    }

    private fun updateRefWindow(parsed: ScreamFeedback) {
        val isCe = parsed.numCeMarkedPackets > 0
        var isLoss = lossEstimator.update(parsed, delayBasedCongestionControl.rtt())
        val isVirtualCe = delayBasedCongestionControl.isQueueDelayDetected()
        if (isLoss) {
            if (!lossEstimator.congested() && !isVirtualCe) {
                isLoss = false
            }
        }

        val previousRefWindow = refWindow

        val timeSinceLastReaction = timeBetween(lastReactionToCongestionTime, parsed.feedbackTime)
        val reactionInterval = reactionInterval()

        if ((isVirtualCe || isCe || isLoss) &&
            timeSinceLastReaction >= min(delayBasedCongestionControl.rtt(), reactionInterval)
        ) {
            lastReactionToCongestionTime = parsed.feedbackTime
            var backoff = 0.0
            if (isLoss) { // Back off due to loss
                backoff = 1.0 - params.betaLoss
            } else if (isCe) { // Backoff due to ECN-CE marking
                backoff = scaleBackoff(parsed, l4sAlpha / 2.0)

                if (!delayBasedCongestionControl.isQueueDelayDetected()) {
                    // Scale down backoff if close to the last known max reference window
                    // This is complemented with a scale down of the reference window
                    // increase
                    backoff *= max(0.25, refWindowScaleFactorCloseToRefWindowI())
                    // Counterbalance the limitation in reference window increase when the
                    // queue delay varies. This helps to avoid starvation in the presence
                    // of competing TCP Prague flows.
                    backoff *= max(0.1, delayBasedCongestionControl.refWindowScaleFactorDueToAvgMinDelay())
                }

                if (timeSinceLastReaction.isFinite() &&
                    timeSinceLastReaction >
                    max(params.virtualRtt, delayBasedCongestionControl.rtt())
                        .multipliedBy(params.numberOfRttsBetweenResetRefWindowIOnCongestion.toLong()) &&
                    maxDataInFlightPrevRtt > DataSize.ZERO
                ) {
                    // A long time(> 100 RTTs) since last congested because
                    // link throughput exceeds max video bitrate.
                    // There is a certain risk that ref_wnd has increased way above
                    // bytes in flight, so we reduce it here to get it better on
                    // track and thus the congestion episode is shortened.
                    refWindow = clamp(maxDataInFlightPrevRtt, params.minRefWindow, refWindow)
                }
            } else if (isVirtualCe) { // Back off due to delay
                backoff = scaleBackoff(parsed, delayBasedCongestionControl.l4sAlphaV() / 2.0)
            }
            if (allowInitialRefWindowClamping && receivedRate.isFinite()) {
                // Clamp ref_window to received rate on the first congestion event,
                // to adjust if starting rate was much higher than link capacity.
                val clampedRefWindow = clamp(
                    dataSentAt(receivedRate, delayBasedCongestionControl.rtt() + feedbackHoldTime),
                    params.minRefWindow,
                    refWindow
                )
                val initialBackoff = 1.0 - clampedRefWindow.ratioTo(refWindow)
                if (initialBackoff > backoff) {
                    backoff = initialBackoff
                    lastBackoff = max(lastBackoff, initialBackoff)
                }
                allowInitialRefWindowClamping = false
            }
            refWindow = refWindow.scaledBy(1.0 - backoff)
        }

        // Increase ref_window.
        // 4.2.2.2.  Reference Window Increase
        if ((!isCe && lossCongestionLevel() < 0.01 && !isVirtualCe) ||
            lastReactionToCongestionTime == parsed.feedbackTime
        ) {
            // Allow increase if no event has occurred, or we are at the same time
            // backing off.
            // Just because there is a CE event, does not mean we send too much. At
            // rates close to the capacity, it is quite likely that one packet is CE
            // marked in every feedback.
            var increaseScaleFactor = refWindowMssRatio()

            // Limit increase for small RTTs
            if (delayBasedCongestionControl.rtt() + feedbackHoldTime < params.virtualRtt) {
                val rttRatio = (delayBasedCongestionControl.rtt() + feedbackHoldTime).ratioTo(params.virtualRtt)
                increaseScaleFactor = increaseScaleFactor * rttRatio
            }

            // Limit increase when close to the last inflection point.
            increaseScaleFactor = increaseScaleFactor * max(0.25, refWindowScaleFactorCloseToRefWindowI())

            // Put a additional restriction on reference window growth if rtt varies a
            // lot.
            // Better to enforce a slow increase in reference window and get
            // a more stable bitrate.
            increaseScaleFactor =
                increaseScaleFactor * delayBasedCongestionControl.refWindowScaleFactorDueToAvgMinDelay()
            increaseScaleFactor =
                increaseScaleFactor * delayBasedCongestionControl.refWindowScaleFactorDueToLatencyDifference()

            // Use lower multiplicative scale factor if congestion was detected
            // recently.
            val maxOfVirtualAndSmothedRtt = max(params.virtualRtt, delayBasedCongestionControl.rtt())
            val postCongestionScale = timeBetween(lastReactionToCongestionTime, parsed.feedbackTime)
                .ratioTo(maxOfVirtualAndSmothedRtt.multipliedBy(params.postCongestionDelayRtts.toLong()))
                .coerceIn(0.0, 1.0)
            val multiplicativeScale = 1.0 +
                (refWindowMultiplicativeScaleFactor() - 1.0) * postCongestionScale *
                refWindowScaleFactorCloseToRefWindowI()
            check(multiplicativeScale >= 1.0)
            increaseScaleFactor = increaseScaleFactor * multiplicativeScale

            val increase = parsed.ackedNotMarkedSize.scaledBy(increaseScaleFactor)
            lastRefWindowIncreaseScaleFactor = increaseScaleFactor
            val maxRefWindow = maxAllowedRefWindow()
            if (refWindow < maxRefWindow) {
                refWindow = clamp(refWindow + increase, params.minRefWindow, maxRefWindow)
            }
        }

        if (previousRefWindow < refWindow) {
            // Allow setting a new `ref_window_i` if `ref_window_` increase.
            // It means that `ref_window_i` can increase if `rew_window` increase and
            // there is a congestion event.
            allowRefWindowIUpdate = true
        }
        if (previousRefWindow > refWindow) {
            lastRefWindowDecreaseTime = parsed.feedbackTime
            if (allowRefWindowIUpdate) {
                refWindowI = previousRefWindow
                allowRefWindowIUpdate = false
            }
        }

        if (previousRefWindow != refWindow) {
            logger.trace {
                "ScreamV2: , ref_window = $refWindow ref_window_i_=$refWindowI, " +
                    "change=${refWindow.wholeBytes() - previousRefWindow.wholeBytes()} bytes , " +
                    "l4s_alpha=$l4sAlpha, is_ce=$isCe is_virtual_ce=$isVirtualCe is_loss=$isLoss " +
                    "smoothed_rtt=${delayBasedCongestionControl.rtt().toRoundedMillis()}, " +
                    "queue_delay=${delayBasedCongestionControl.queueDelay()} " +
                    "feedback_hold${feedbackHoldTime.toRoundedMillis()}, " +
                    "target_rate =${targetRate.toRoundedKbps()}"
            }
        }
    }

    private fun updateFeedbackHoldTime(feedbackHoldTime: Duration) {
        if (this.feedbackHoldTime.isZero && params.feedbackHoldTimeAvgG > 0.0) {
            this.feedbackHoldTime = feedbackHoldTime
        }
        this.feedbackHoldTime = feedbackHoldTime.scaledBy(params.feedbackHoldTimeAvgG) +
            this.feedbackHoldTime.scaledBy(1.0 - params.feedbackHoldTimeAvgG)
    }

    private fun updateFeedbackInterval(feedbackTime: Instant) {
        if (!lastFeedbackTime.isInfinite()) {
            val delta = Duration.between(lastFeedbackTime, feedbackTime)
            if (delta > Duration.ZERO) {
                if (feedbackInterval.isZero) {
                    feedbackInterval = delta
                } else {
                    val g = params.feedbackIntervalAvgG
                    feedbackInterval = delta.scaledBy(g) + feedbackInterval.scaledBy(1.0 - g)
                }
            }
        }
        lastFeedbackTime = feedbackTime
    }

    private fun updateTargetRate(parsed: ScreamFeedback) {
        // Avoid division by zero.
        val nonZeroSmoothedRtt = max(delayBasedCongestionControl.rtt(), 1.ms)

        var scaleTargetRate = 1.0
        // Scale down target rate slightly when the reference window is very small
        // compared to MSS
        scaleTargetRate *= (1.0 - (refWindowMssRatio() - 0.1).coerceIn(0.0, 0.2))

        var targetRate = rateOf(refWindow, nonZeroSmoothedRtt + feedbackHoldTime).scaledBy(scaleTargetRate)

        if (!delayBasedCongestionControl.isQueueDrainedInTime(parsed.feedbackTime)) {
            // If estimated min queue delay is too high for too long, target rate is
            // reduced for a period of time.
            // If the min queue delay is still too high, the queue delay estimate is
            // reset. This can happen if the one way delay increase for other reasons
            // than self congestion.
            if (drainQueueStart.isInfinite()) {
                drainQueueStart = parsed.feedbackTime
                logger.info("Reduce target rate to attempt to drain queue.")
            }
            if (Duration.between(drainQueueStart, parsed.feedbackTime) <
                max(100.ms, nonZeroSmoothedRtt.multipliedBy(params.queueDelayDrainRtts.toLong()))
            ) {
                targetRate = targetRate.scaledBy(0.5)
            } else {
                logger.info("Reset queue delay estimate due to high queue delay.")
                delayBasedCongestionControl.resetQueueDelay()
            }
        } else {
            drainQueueStart = NEVER
        }

        // TODO: bugs.webrtc.org/447037083 -  Consider implementing 4.4, compensation
        // for increased pacer delay.
        targetRate = clamp(targetRate, minTargetBitrate, maxTargetBitrate)

        this.targetRate = targetRate
    }

    private fun updateReceiveRate(feedback: ScreamFeedback) {
        accumulatedReceivedBytes += feedback.received
        val delayMetrics = feedback.delayMetrics ?: return
        val lastPacketReceiveTime = delayMetrics.lastPacketReceiveTime

        if (lastWindowReceiveTime.isInfinite()) {
            // At the first feedback, set the received rate to infinite to ensure ALR
            // can not be entered until a valid receive rate estimate exists.
            lastWindowReceiveTime = lastPacketReceiveTime
            receivedRate = Bandwidth.INFINITY
            accumulatedReceivedBytes = DataSize.ZERO
        } else if (Duration.between(lastWindowReceiveTime, lastPacketReceiveTime) >= params.receivedRateWindow) {
            val duration = Duration.between(lastWindowReceiveTime, lastPacketReceiveTime)
            receivedRate = rateOf(accumulatedReceivedBytes, duration)
            accumulatedReceivedBytes = DataSize.ZERO
            lastWindowReceiveTime = lastPacketReceiveTime
        }
    }

    private var maxTargetBitrate = Bandwidth.INFINITY
    private var minTargetBitrate = Bandwidth.ZERO
    private var targetRate = Bandwidth.ZERO

    // Upper limit on the number of bytes that should be in
    // flight (transmitted but not yet acknowledged)
    private var refWindow: DataSize = params.minRefWindow

    // Reference window inflection point. I.e, `ref_window_` when congestion was
    // noticed. Increase and decrease of `ref_window_` is scaled down around
    // `ref_window_i_`.
    private var refWindowI: DataSize = 1.bytes

    // `allow_ref_window_i_update_` is set to true if `ref_window_` has increased
    // since `ref_window_i_` was last set.
    private var allowRefWindowIUpdate = true
    private var lastRefWindowIncreaseScaleFactor = 1.0
    private var lastBackoff = 0.0
    private var lastBackoffRttStartTime: Instant = NEVER

    // `l4s_alpha_` tracks the average fraction of ECN-CE marked data units per
    // Round-Trip Time.
    private var l4sAlpha = 0.0
    private val lossEstimator = LossEstimator(params)

    private var feedbackHoldTime: Duration = Duration.ZERO
    private var lastFeedbackTime: Instant = NEVER
    private var feedbackInterval: Duration = Duration.ZERO

    // Per-RTT stats
    private var lastDataInFlightUpdate: Instant = NEVER
    private var maxDataInFlightThisRtt = DataSize.ZERO
    private var maxDataInFlightPrevRtt = DataSize.ZERO
    private var allowInitialRefWindowClamping = params.allowInitialRefWindowClamping
    private var receivedRate = Bandwidth.ZERO
    private var accumulatedReceivedBytes = DataSize.ZERO
    private var lastWindowReceiveTime: Instant = NEVER

    // `last_reaction_to_congestion_time` is called
    // `last_congestion_detected_time` in 4.2.2. Reference Window Update.
    // Last received feedback that contained a congestion event that may have
    // caused a reaction.
    private var lastReactionToCongestionTime: Instant = NEVER

    // Last time the reference window decreased. This is not the same
    // as `last_reaction_to_congestion_time_` since a single CE mark does not
    // necessarily cause a reference window decrease.
    private var lastRefWindowDecreaseTime: Instant = NEVER

    private var drainQueueStart: Instant = NEVER

    private val delayBasedCongestionControl = DelayBasedCongestionControl(params)
    private var firstFeedbackProcessed = false

    // Tracks if the send rate is less than the network path can currently
    // support. This is done by checking if max_allowed_ref_window() <
    // ref_window_.
    private var isApplicationLimited = false
}

/** std::clamp */
internal fun <T : Comparable<T>> clamp(value: T, low: T, high: T): T = when {
    value < low -> low
    high < value -> high
    else -> value
}
