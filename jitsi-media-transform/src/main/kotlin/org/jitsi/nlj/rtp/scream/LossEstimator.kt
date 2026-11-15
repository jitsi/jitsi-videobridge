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

import org.jitsi.nlj.util.timeBetween
import org.jitsi.utils.NEVER
import org.jitsi.utils.max
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

/** Loss estimator,
 * based on WebRTC modules/congestion_controller/scream/loss_estimator.{h,cc} in
 * WebRTC commit 42a497714596d8f726da4dae4fb4f133cbaa15df (main, 2026-11-15).
 *
 * Estimates the short-term congestion level of the connection using a biased
 * asymmetric step filter (counting a weighted sum of RTTs with loss minus
 * RTTs without loss).
 *
 * The congestion level is updated on RTT boundaries. If one or more packets are
 * reported lost during an RTT interval, the filter treats the interval as
 * a loss event.
 *
 * To handle packet reordering robustly, the estimator maintains a count of
 * outstanding unrecovered lost packets. When a packet is reported lost for the
 * first time, the outstanding count increments. If a subsequently received
 * feedback report indicates that an out-of-order packet has been recovered,
 * the count decrements. If the unrecovered count reaches zero, the connection
 * is considered loss-free, and the congestion level is reset to zero.
 *
 * Asymmetric Step Filter Motivation:
 * A simple sliding window or traditional EWMA is highly sensitive to spurious
 * uniform losses (e.g. 1% uniform random loss in wireless links). Because we
 * transmit many packets per RTT (e.g. 50), a 1% packet loss scales up to a
 * ~40% RTT loss event probability.
 *
 * This filter solves this by using an asymmetric step filter (leaky bucket)
 * scaled between [0.0, 1.0] (with step-up of +1/3 on loss, step-down of -0.5 on
 * no loss). This guarantees that the expected drift per RTT under 1% uniform
 * loss is strongly negative (-0.5), keeping the congestion level at ~0.0.
 * Meanwhile, it takes exactly 3 consecutive loss RTTs to climb to 1.0 to react.
 */
class LossEstimator(params: ScreamV2Parameters) {
    // Updates the congestion level estimate. Returns true if a loss has been
    // detected in this feedback message, false otherwise.
    fun update(parsed: ScreamFeedback, rtt: Duration): Boolean {
        val maxRtt = max(virtualRtt, rtt)
        if (timeBetween(lastLossOrRecoveryTime, parsed.feedbackTime) > maxRtt) {
            // Reset the unrecovered lost packet count if no new losses or recoveries
            // were reported for a full RTT. This prevents old, permanently lost
            // packets (which will never be recovered) from keeping this counter
            // positive indefinitely.
            unrecoveredLostPackets = 0
        }

        var hasLostPackets = false
        if (parsed.numLostPackets > 0 || parsed.numRecoveredPackets > 0) {
            lastLossOrRecoveryTime = parsed.feedbackTime
        }

        if (parsed.numRecoveredPackets > 0) {
            unrecoveredLostPackets = max(0, unrecoveredLostPackets - parsed.numRecoveredPackets)
            if (unrecoveredLostPackets == 0) {
                congestionLevel = 0.0
                lossEventThisRtt = false
            }
        }

        if (parsed.numLostPackets > 0) {
            hasLostPackets = true
            unrecoveredLostPackets += parsed.numLostPackets
            lossEventThisRtt = true
        }

        if (timeBetween(lastRttUpdateTime, parsed.feedbackTime) >= maxRtt) {
            lastRttUpdateTime = parsed.feedbackTime
            if (lossEventThisRtt) {
                // Asymmetric step filter: increment by 1.0 /
                // rtts_with_loss_before_backoff_ on a [0.0, 1.0] normalized scale. This
                // takes exactly rtts_with_loss_before_backoff_ consecutive RTTs with loss
                // to trigger backoff (reaching the 1.0 threshold).
                congestionLevel = min(1.0, congestionLevel + 1.0 / rttsWithLossBeforeBackoff)
            } else {
                // Asymmetric step filter: decrement by 1.0 / lossless_rtts_before_clear_
                // on a [0.0, 1.0] normalized scale.
                // Assuming ~50 packets are transmitted per RTT, a 1% uniform random
                // packet loss rate results in a ~40% probability of at least one loss
                // event per RTT. Under this noise profile (40% loss RTTs, 60% lossless
                // RTTs), the expected drift per RTT is strongly negative:
                // 0.4 * (+1.0/3) + 0.6 * (-1.0/2) = -0.167 (or -0.5 on a [0, 3] scale).
                // This completely filters out spurious uniform random losses, while
                // preserving memory of recent congestion after one lossless RTT (drops
                // to 0.5, allowing fast 2-RTT back-to-back reaction).
                congestionLevel = max(0.0, congestionLevel - 1.0 / losslessRttsBeforeClear)
            }
            lossEventThisRtt = false
        }
        return hasLostPackets
    }

    // Returns the congestion level, which is a value between 0.0 (loss-free)
    // and 1.0 (congested).
    fun congestionLevel(): Double = congestionLevel

    // Returns true if the short-term congestion level has reached the threshold.
    fun congested(): Boolean = congestionLevel >= 0.99

    private val virtualRtt: Duration = params.virtualRtt
    private val rttsWithLossBeforeBackoff: Int = params.rttsWithLossBeforeBackoff
    private val losslessRttsBeforeClear: Int = params.losslessRttsBeforeClear

    // `loss_event_this_rtt_` tracks whether any packet loss was detected
    // specifically within the current RTT interval. Consumed and reset on RTT
    // boundaries. Needed separately from `unrecovered_lost_packets_` because
    // unrecovered counts persist across RTT updates to track delayed reordered
    // recoveries.
    private var lossEventThisRtt = false
    private var congestionLevel = 0.0
    private var unrecoveredLostPackets = 0
    private var lastLossOrRecoveryTime: Instant = NEVER
    private var lastRttUpdateTime: Instant = NEVER
}
