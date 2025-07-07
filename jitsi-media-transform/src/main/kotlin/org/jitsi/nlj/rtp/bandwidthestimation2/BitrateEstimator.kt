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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.bytes
import org.jitsi.nlj.util.kbps
import org.jitsi.nlj.util.per
import org.jitsi.utils.ms
import org.jitsi.utils.toRoundedEpochMilli
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Computes a bayesian estimate of the throughput given acks containing
 * the arrival time and payload size. Samples which are far from the current
 * estimate or are based on few packets are given a smaller weight, as they
 * are considered to be more likely to have been caused by, e.g., delay spikes
 * unrelated to congestion.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/bitrate_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
open class BitrateEstimator {
    private var sum = 0
    private val initialWindowMs = kInitialRateWindowMs
    private val noninitialWindowMs = kRateWindowMs
    private val uncertaintyScale = 10.0f
    private val uncertaintyScaleInAlr = uncertaintyScale
    private val smallSampleUncertaintyScale = uncertaintyScale
    private val smallSampleThreshold = DataSize.ZERO
    private val uncertaintySymmetryCap = Bandwidth.ZERO
    private val estimateFloor = Bandwidth.ZERO

    private var currentWindowMs = 0L
    private var prevTimeMs = -1L
    private var bitrateEstimateKbps = -1.0f
    private var bitrateEstimateVar = 50.0f

    open fun update(atTime: Instant, amount: DataSize, inAlr: Boolean) {
        val rateWindowMs = if (bitrateEstimateKbps < 0.0f) initialWindowMs else noninitialWindowMs
        val (bitrateSampleKbps, isSmallSample) =
            updateWindow(atTime.toRoundedEpochMilli(), amount.bytes.roundToInt(), rateWindowMs)
        if (bitrateSampleKbps < 0.0f) {
            return
        }
        if (bitrateEstimateKbps < 0.0f) {
            // This is the very first sample we get. Use it to initialize the estimate.
            bitrateEstimateKbps = bitrateSampleKbps
            return
        }
        // Optionally use higher uncertainty for very small samples to avoid dropping
        // estimate and for samples obtained in ALR.
        val scale = if (isSmallSample && bitrateSampleKbps < bitrateEstimateKbps) {
            smallSampleUncertaintyScale
        } else if (inAlr && bitrateSampleKbps < bitrateEstimateKbps) {
            uncertaintyScaleInAlr
        } else {
            uncertaintyScale
        }
        // Define the sample uncertainty as a function of how far away it is from the
        // current estimate. With low values of uncertaintySymmetryCap we add more
        // uncertainty to increases than to decreases. For higher values we approach
        // symmetry.
        val sampleUncertainty =
            scale * abs(bitrateEstimateKbps - bitrateSampleKbps) /
                (
                    bitrateEstimateKbps +
                        min(bitrateSampleKbps, uncertaintySymmetryCap.kbps.toFloat())
                    )

        val sampleVar = sampleUncertainty * sampleUncertainty
        // Update a bayesian estimate of the rate, weighting it lower if the sample
        // uncertainty is large.
        // The bitrate estimate uncertainty is increased with each update to model
        // that the bitrate changes over time.
        val predBitrateEstimateVar = bitrateEstimateVar + 5.0f
        bitrateEstimateKbps = (
            sampleVar * bitrateEstimateKbps +
                predBitrateEstimateVar * bitrateSampleKbps
            ) /
            (sampleVar + predBitrateEstimateVar)
        bitrateEstimateKbps = max(bitrateEstimateKbps, estimateFloor.kbps.toFloat())
        bitrateEstimateVar = sampleVar * predBitrateEstimateVar /
            (sampleVar + predBitrateEstimateVar)
    }

    private fun updateWindow(nowMs: Long, bytes: Int, rateWindowMs: Int): Pair<Float, Boolean> {
        // Reset if time moves backwards.
        if (nowMs < prevTimeMs) {
            prevTimeMs = -1
            sum = 0
            currentWindowMs = 0
        }
        if (prevTimeMs >= 0) {
            currentWindowMs += nowMs - prevTimeMs
            // Reset if nothing has been received for more than a full window.
            if (nowMs - prevTimeMs > rateWindowMs) {
                sum = 0
                currentWindowMs %= rateWindowMs
            }
        }
        prevTimeMs = nowMs
        val bitrateSample: Float
        val isSmallSample: Boolean
        if (currentWindowMs >= rateWindowMs) {
            isSmallSample = sum < smallSampleThreshold.bytes
            bitrateSample = 8.0f * sum / rateWindowMs.toFloat()
            currentWindowMs -= rateWindowMs
            sum = 0
        } else {
            isSmallSample = false
            bitrateSample = -1.0f
        }
        sum += bytes
        return Pair(bitrateSample, isSmallSample)
    }

    open fun bitrate(): Bandwidth? {
        return if (bitrateEstimateKbps < 0.0f) null else bitrateEstimateKbps.kbps
    }

    fun peekRate(): Bandwidth? {
        return if (currentWindowMs > 0) sum.bytes.per(currentWindowMs.ms) else null
    }

    open fun expectFastRateChange() {
        // By setting the bitrate-estimate variance to a higher value we allow the
        // bitrate to change fast for the next few samples.
        bitrateEstimateVar += 200
    }

    companion object {
        const val kInitialRateWindowMs = 500
        const val kRateWindowMs = 150
        const val kMinRateWindowMs = 150
        const val kMaxRateWindowMs = 1000
    }
}
