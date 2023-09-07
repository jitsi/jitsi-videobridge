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
package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.kbps
import kotlin.math.max
import kotlin.math.sqrt

/** Link capacity estimator,
 * based on WebRTC modules/congestion_controller/goog_cc/link_capacity_estimator.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 */
class LinkCapacityEstimator {
    private var estimateKbps: Double? = null
    private var deviationKbps: Double = 0.4

    fun upperBound(): Bandwidth {
        return estimateKbps?.let {
            (it + 3 * deviationEstimateKbps()).kbps
        } ?: Bandwidth.INFINITY
    }

    fun lowerBound(): Bandwidth {
        return estimateKbps?.let {
            (max(0.0, it - 3 * deviationEstimateKbps())).kbps
        } ?: Bandwidth.ZERO
    }

    fun reset() {
        estimateKbps = null
    }

    fun onOveruseDetected(acknowledgedRate: Bandwidth) = update(acknowledgedRate, 0.05)

    fun onProbeRate(probeRate: Bandwidth) = update(probeRate, 0.5)

    fun hasEstimate(): Boolean = estimateKbps != null

    val estimate: Bandwidth
        get() = estimateKbps!!.kbps

    private fun update(capacitySample: Bandwidth, alpha: Double) {
        val sampleKbps = capacitySample.kbps
        estimateKbps = if (estimateKbps == null) {
            sampleKbps
        } else {
            (1 - alpha) * estimateKbps!! + alpha * sampleKbps
        }
        // Estimate the variance of the link capacity estimate and normalize the
        // variance with the link capacity estimate.
        val norm = max(estimateKbps!!, 1.0)
        val errorKbps = estimateKbps!! - sampleKbps
        deviationKbps = (1 - alpha) * deviationKbps + alpha * errorKbps * errorKbps / norm
        // 0.4 ~= 14 kbit/s at 500 kbit/s
        // 2.5 ~= 35 kbit/s at 500 kbit/s
        deviationKbps = deviationKbps.coerceIn(0.4, 2.5)
    }

    private fun deviationEstimateKbps(): Double =
        // Calculate the max bit rate std dev given the normalized
        // variance and the current throughput bitrate. The standard deviation will
        // only be used if estimateKbps has a value.
        sqrt(deviationKbps * estimateKbps!!)
}
