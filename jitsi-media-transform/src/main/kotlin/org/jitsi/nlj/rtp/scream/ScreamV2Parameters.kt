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

import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.mins
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration

/** Parameters of ScreamV2,
 * based on WebRTC modules/congestion_controller/scream/scream_v2_parameters.{h,cc} in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 *
 * In WebRTC these are parameters of the field trial WebRTC-Bwe-ScreamV2. The defaults here are the defaults there.
 */
data class ScreamV2Parameters(
    // Minimum Reference Window
    val minRefWindow: DataSize = 1000.bytes,

    // Exponentially Weighted Moving Average (EWMA) factor for l4s_alpha.
    val l4sAvgGUp: Double = 1.0 / 8.0,
    val l4sAvgGDown: Double = 1.0 / 16.0,

    // If true, scales `l4s_avg_g_up` and `l4s_avg_g_down` by `steps_per_rtt` so
    // `l4s_alpha` is updated relative to `max(reaction_interval, rtt)`.
    val scaleL4sAlphaWithRtt: Boolean = true,

    // The number of consecutive RTTs with loss required to trigger a backoff.
    // Used as the step-up value (1.0 / rtts_with_loss_before_backoff) in the
    // short-term congestion level step filter.
    val rttsWithLossBeforeBackoff: Int = 3,

    // The number of consecutive lossless RTTs required to fully clear the
    // congestion level from maximum congestion (1.0) back to 0.0.
    // Used as the step-down value (1.0 / lossless_rtts_before_clear) in the
    // short-term congestion level step filter.
    val losslessRttsBeforeClear: Int = 2,

    // Exponentially Weighted Moving Average (EWMA) factor for smoothed rtt.
    val smoothedRttAvgG: Double = 1.0 / 8.0,
    val smoothedRttAvgInAlrG: Double = 1.0 / 128.0,

    // Maximum Segment Size (MSS)
    // Size of the largest data segment that a sender is able to transmit. I.e
    // largest possible IP packet.
    val maxSegmentSize: DataSize = 1280.bytes,

    // Headroom for bytes in flight when increasing reference window.
    val bytesInFlightHeadRoom: Double = 1.1,

    // Reference window scale factor due to loss event.
    val betaLoss: Double = 0.7,

    // Determines how many RTTs after a congestion event the reference window
    // growth should be cautious.
    val postCongestionDelayRtts: Int = 100,

    // Determines how much (as a fraction of ref_window) that the ref_window can
    // increase per RTT.
    val multiplicativeIncreaseFactor: Double = 0.02,

    // This mimics Prague's RTT fairness such that flows with RTT below
    // virtual_rtt should get a roughly equal share over an L4S path.
    val virtualRtt: Duration = 25.ms,

    // Increase and decrease of ref window is slower close to the last
    // inflection point. Both increase and decrease is scaled by
    // (backoff_scale_factor_close_to_ref_window_i* (ref_window_i -
    // ref_window)) / ref_window_i) ^2
    //
    // backoff_scale_factor_close_to_ref_window_i is set lower than in the
    // rfc(8.0). This means that increase/decrease around ref_window_i is
    // slower in this implementation.
    val backoffScaleFactorCloseToRefWindowI: Double = 2.0,

    // If CE is detected and this number of RTTs has passed since last
    // congestion, ref_window_i will be reset.
    val numberOfRttsBetweenResetRefWindowIOnCongestion: Int = 100,

    // Max/Min used for calculating how much larger the send window is allowed to
    // be than the ref window.
    val refWindowOverheadMin: Double = 1.5,
    val refWindowOverheadMax: Double = 3.0,

    // Exponentially Weighted Moving Average (EWMA) factor for updating queue
    // delay.
    val queueDelayAvgG: Double = 1.0 / 4.0,

    // Exponentially Weighted Moving Average (EWMA) factor for updating average
    // min queue delay and average latency difference.
    val delayMinAndLatencyDiffAvgG: Double = 1.0 / 32.0,

    // Thresholds for average min queue delay. Used for limiting ref_window
    // increase.
    val queueDelayMinThreshold: Duration = 10.ms,

    // Thresholds for average latency difference. Used for limiting ref_window
    // increase.
    val latencyDiffThreshold: Duration = 10.ms,

    // Minimum (base) window from the latest sent packet in feedback to compute
    // min and max one-way delay.
    val burstWindowMin: Duration = 25.ms,

    // Maximum window from the latest sent packet in feedback to bound burst
    // continuation when consecutive packets are closely spaced.
    val burstWindowMax: Duration = 50.ms,

    // Maximum send time gap between consecutive packets to extend the burst
    // window beyond `burst_window_min` up to `burst_window_max`.
    val burstWindowMaxGap: Duration = 3.ms,

    // Determines the length of the base delay history when estimating one way
    // delay (owd)
    val baseDelayWindowLength: Int = 10,

    // Determines how often the base delay history is updated.
    val baseDelayHistoryUpdateInterval: Duration = 1.mins,

    // Reference window is reduced if average queue delay is above
    // `queue_delay_target/2`
    // TODO: bugs.webrtc.org/447037083 -  Consider implementing 4.2.1.4.1.
    // Competing Flows Compensation.
    val queueDelayTarget: Duration = 60.ms,

    // If the minimum queue delay is below this threshold, queues are deamed to be
    // drained.
    val queueDelayDrainThreshold: Duration = 5.ms,

    // If the minimum queue delay has been above `queue_delay_drain_threshold` for
    // longer than `queue_delay_drain_period`, an attempt it made to drain the
    // queues, and if that fails, resets the estimates.
    val queueDelayDrainPeriod: Duration = 20.secs,

    // Number of RTTs where the target rate is reduced to attempt to drain.
    val queueDelayDrainRtts: Int = 5,

    // Padding is periodically used in order to increase target rate even if a
    // stream does not produce a high enough rate. Time between periodic padding
    // is at least this long.
    val timeBetweenPeriodicPadding: Duration = 3.secs,

    // Max duration padding is used when periodic padding start.
    // Padding is stopped if congestion occur.
    val periodicPaddingDuration: Duration = 3.secs,

    // Padding is allowed to be used after this duration since the last
    // time reference window was reduced but at least `periodic_padding_interval`
    // must have passed since last time padding was used.
    val allowPaddingAfterLastCongestionTime: Duration = 1.secs,

    // If initial BWE probing is allowed, allow periodic probing for this duration
    // even of no streams have been configured.
    val initialProbingDuration: Duration = 6.secs,

    // Factor multiplied by the current target rate to decide the pacing rate.
    val pacingFactor: Double = 1.1,

    // Minimum pacing rate relative to received_rate.
    val pacingRateReceivedFactor: Double = 0.8,

    // Exponentially Weighted Moving Average (EWMA) factor for calculating average
    // time feedback is delayed by the receiver. I.e the time from a packet is
    // received until feedback is sent. If zero, this delay is ignored.
    val feedbackHoldTimeAvgG: Double = 1.0 / 8.0,

    // Exponentially Weighted Moving Average (EWMA) factor for tracking average
    // feedback interval.
    val feedbackIntervalAvgG: Double = 0.1,

    // If true, effective virtual_rtt is calculated as
    // std::max(virtual_rtt, feedback_interval). Otherwise, virtual_rtt is used.
    val useFeedbackIntervalForVirtualRtt: Boolean = true,

    // If true, reference window is clamped to received rate on the first
    // congestion event after the first RTT.
    val allowInitialRefWindowClamping: Boolean = true,

    // If the time since last reaction to congestion is larger than this, the
    // pacing window is increased. I.e. packets are allowed to be sent in larger
    // bursts.
    val allowLargePacingBurstsAfterCongestionTime: Duration = 15.secs,

    // Enable application-limited (ALR) state tracking.
    // In ALR, reference window can not increase, and RTT is updated slower.
    val enableAlr: Boolean = true,

    // An application is deemed application-limited (ALR) if the reference window
    // exceeds the maximum allowed based on data in flight, and the received rate
    // is less than alr_threshold * target_rate.
    val alrThreshold: Double = 0.9,

    // Window over which received rate is calculated.
    val receivedRateWindow: Duration = 100.ms,

    // Minimum pacing delay before starting cwnd pushback reduction.
    val minPacingDelayForPushback: Duration = 100.ms,

    // Maximum pacing delay for full cwnd pushback reduction.
    val maxPacingDelayForPushback: Duration = 500.ms,

    // On route change with restart_bwe = true, cap the starting rate to
    // min(current_target_rate, starting_rate).
    val safeResetOnRouteChange: Boolean = true
)
