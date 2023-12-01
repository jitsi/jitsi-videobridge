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
package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.isFinite
import org.jitsi.nlj.util.isInfinite
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.max
import org.jitsi.nlj.util.min
import org.jitsi.utils.div
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant
import kotlin.math.exp
import kotlin.math.min

/** Send-side bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/send_side_bandwidth_estimation.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 * Field trial settings have been generally removed, set to their default settings.
 */
class LinkCapacityTracker {
    private val trackingRate: Duration = 10.secs
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
                exp(-(delta / trackingRate))
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

class SendSideBandwidthEstimation
