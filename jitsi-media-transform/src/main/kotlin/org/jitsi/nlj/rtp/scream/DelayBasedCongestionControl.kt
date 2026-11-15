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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings
import org.jitsi.nlj.util.WindowedMinFilter
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.NEVER
import org.jitsi.utils.isFinite
import org.jitsi.utils.isInfinite
import org.jitsi.utils.min
import org.jitsi.utils.times
import java.time.Duration
import java.time.Instant

/** Delay based congestion control,
 * based on WebRTC modules/congestion_controller/scream/delay_based_congestion_control.{h,cc} in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * Implements logic necessary for backing off if queue delay increase as
 * proposed in
 * https://datatracker.ietf.org/doc/draft-johansson-ccwg-rfc8298bis-screamv2/.
 * The functionality is split out into a separate class since it may be possible
 * to replace this logic with the trend line filter from Goog CC or a ML model.
 */
class DelayBasedCongestionControl(private val params: ScreamV2Parameters) {
    fun update(feedback: ScreamFeedback, alr: Boolean) {
        val delay = checkNotNull(feedback.delayMetrics)
        check(delay.minOneWayDelay.isFinite())
        check(delay.maxOneWayDelay.isFinite())
        check(delay.rttSample.isFinite())
        nextBaseDelay = min(nextBaseDelay, delay.minOneWayDelay)
        updateSmoothedRtt(delay.rttSample, alr)

        val minQueueDelay = delay.minOneWayDelay - minBaseDelay()
        if (minQueueDelay > params.queueDelayDrainThreshold) {
            if (minQueueDelayAboveThresholdStart.isInfinite()) {
                minQueueDelayAboveThresholdStart = feedback.feedbackTime
            }
        } else {
            minQueueDelayAboveThresholdStart = NEVER
        }
        updateQueueDelayAverage(min(minQueueDelay, lastQueueDelaySample))
        lastQueueDelaySample = minQueueDelay
        updateQueueDelayMinAverage(minQueueDelay)
        updateLatencyDifferenceAverage(delay.maxOneWayDelay - delay.minOneWayDelay)

        if (timeBetween(lastBaseDelayUpdate, feedback.feedbackTime) >= params.baseDelayHistoryUpdateInterval) {
            baseDelayHistory.insert(nextBaseDelay)
            lastBaseDelayUpdate = feedback.feedbackTime
            nextBaseDelay = MAX_DURATION
        }
    }

    // Returns true if queue delay is detected and above a threshold.
    fun isQueueDelayDetected(): Boolean =
        queueDelayAvg.isFinite() && queueDelayAvg > params.queueDelayTarget.dividedBy(2)

    // Returns false if the minimum queue delay has been above the drain threshold
    // for a prolonged time. This can happen if minimum possible latency has
    // increased, or queues has been filled for a longer period of time without
    // being drained.
    fun isQueueDrainedInTime(now: Instant): Boolean = minQueueDelayAboveThresholdStart.isInfinite() ||
        Duration.between(minQueueDelayAboveThresholdStart, now) < params.queueDelayDrainPeriod

    // Resets queue delay estimates to start values.
    fun resetQueueDelay() {
        lastBaseDelayUpdate = NEVER
        nextBaseDelay = MAX_DURATION
        baseDelayHistory.reset()
        // Insert a start value to ensure GetMin returns a sensible value when empty.
        baseDelayHistory.insert(MAX_DURATION)

        minQueueDelayAboveThresholdStart = NEVER
        lastUpdateQdelayAvgTime = NEVER
        queueDelayAvg = MAX_DURATION
        queueDelayMinAvg = Duration.ZERO
        latencyDifferenceAvg = Duration.ZERO
    }

    fun queueDelay(): Duration = queueDelayAvg
    fun queueDelayMinAvg(): Duration = queueDelayMinAvg
    fun latencyDifferenceAvg(): Duration = latencyDifferenceAvg

    // Scale factor used for scaling reference window increase/decrease due to
    // minimum average queue delay per feedback. The scale factor is 0.1
    // if the delay is higher than the threshold, and increases linearly to 1.0 if
    // the delay is lower than the threshold / 4.
    fun refWindowScaleFactorDueToAvgMinDelay(allowZero: Boolean = false): Double {
        val norm = params.queueDelayMinThreshold
        // Reaches 0.1 at norm, and 1.0 at norm / 4
        return (0.1 + (norm - queueDelayMinAvg).scaledBy(1.2).ratioTo(norm))
            .coerceIn(if (allowZero) 0.0 else 0.1, 1.0)
    }

    // Scale factor used for scaling reference window increase if
    // latency differences per feedback is larger than the threshold. If the send
    // rate is close to link capacity, the difference between min and max latency
    // per feedback increases and the scale factor decreases.
    fun refWindowScaleFactorDueToLatencyDifference(): Double {
        val norm = params.latencyDiffThreshold
        // Reaches 0.1 at norm, and 1.0 at norm / 4
        return (0.1 + (norm - latencyDifferenceAvg).scaledBy(1.2).ratioTo(norm)).coerceIn(0.1, 1.0)
    }

    fun rtt(): Duration = lastSmoothedRtt

    fun l4sAlphaV(): Double {
        // 4.2.2.1
        val halfTarget = params.queueDelayTarget.dividedBy(2)
        val l4sAlphaV = if (queueDelayAvg.isFinite()) {
            (queueDelayAvg - halfTarget).ratioTo(halfTarget)
        } else {
            Double.POSITIVE_INFINITY
        }
        return l4sAlphaV.coerceIn(0.0, 1.0)
    }

    private fun minBaseDelay(): Duration = min(nextBaseDelay, baseDelayHistory.getMin())

    private fun updateSmoothedRtt(rttSample: Duration, alr: Boolean) {
        check(rttSample >= Duration.ZERO)
        if (lastSmoothedRtt.isZero) {
            lastSmoothedRtt = rttSample
        } else {
            val g = if (alr) params.smoothedRttAvgInAlrG else params.smoothedRttAvgG
            lastSmoothedRtt = rttSample.scaledBy(g) + lastSmoothedRtt.scaledBy(1.0 - g)
        }
    }

    private fun updateQueueDelayAverage(minQdelayInFeedback: Duration) {
        // `queue_delay_avg_` is updated with a slow attack,fast decay EWMA
        // filter.
        if (minQdelayInFeedback < queueDelayAvg) {
            queueDelayAvg = minQdelayInFeedback
        } else {
            queueDelayAvg = minQdelayInFeedback.scaledBy(params.queueDelayAvgG) +
                queueDelayAvg.scaledBy(1.0 - params.queueDelayAvgG)
        }
    }

    private fun updateQueueDelayMinAverage(packetQdelay: Duration) {
        check(packetQdelay >= Duration.ZERO)
        queueDelayMinAvg = queueDelayMinAvg.scaledBy(1.0 - params.delayMinAndLatencyDiffAvgG) +
            min(packetQdelay, 2 * params.queueDelayMinThreshold).scaledBy(params.delayMinAndLatencyDiffAvgG)
    }

    private fun updateLatencyDifferenceAverage(packetLatencyDiff: Duration) {
        check(packetLatencyDiff >= Duration.ZERO)
        latencyDifferenceAvg = latencyDifferenceAvg.scaledBy(1.0 - params.delayMinAndLatencyDiffAvgG) +
            min(packetLatencyDiff, 2 * params.latencyDiffThreshold).scaledBy(params.delayMinAndLatencyDiffAvgG)
    }

    // For computing min one way delay and compensate for clock drift.
    // Based on https://datatracker.ietf.org/doc/html/rfc6817
    private var lastBaseDelayUpdate: Instant = NEVER
    private var nextBaseDelay: Duration = MAX_DURATION
    private val baseDelayHistory = WindowedMinFilter(params.baseDelayWindowLength, Duration.ZERO)

    private var minQueueDelayAboveThresholdStart: Instant = NEVER

    private var lastSmoothedRtt: Duration = Duration.ZERO

    @SuppressFBWarnings("URF_UNREAD_FIELD")
    private var lastUpdateQdelayAvgTime: Instant = NEVER
    private var lastQueueDelaySample: Duration = MAX_DURATION
    private var queueDelayAvg: Duration = MAX_DURATION
    private var queueDelayMinAvg: Duration = Duration.ZERO
    private var latencyDifferenceAvg: Duration = Duration.ZERO

    init {
        resetQueueDelay()
    }
}
