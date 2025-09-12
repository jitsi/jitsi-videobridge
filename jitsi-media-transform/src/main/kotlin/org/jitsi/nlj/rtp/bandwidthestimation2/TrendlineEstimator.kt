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
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import kotlin.collections.ArrayDeque
import kotlin.math.abs
import kotlin.math.min

/**
 * Trendline-based delay increase detector
 * *
 * Based on WebRTC modules/congestion_controller/goog_cc/trendline_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
class TrendlineEstimator(
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext
) : DelayIncreaseDetectorInterface {
    private val settings = TrendlineEstimatorSettings()

    // Parameters
    private val smoothingCoef = kDefaultTrendlineSmoothingCoeff
    private val thresholdGain = kDefaultTrendlineThresholdGain

    // Used by the existing threshold.
    private var numOfDeltas: Int = 0

    // Keep the arrival times small by using the change from the first packet
    private var firstArrivalTimeMs: Long = -1

    // Exponential backoff filtering.
    private var accumulatedDelay: Double = 0.0
    private var smoothedDelay: Double = 0.0

    // Linear least squares regression
    private val delayHist = ArrayDeque<PacketTiming>()

    private val kUp = 0.0087
    private val kDown = 0.039
    private val overusingTimeThreshold = kOverusingTimeThreshold
    var threshold = 12.5
        private set

    var prevModifiedTrend = Double.NaN
        private set

    private var lastUpdateMs = -1L
    var prevTrend = 0.0
        private set
    private var timeOverUsing = -1.0
    private var overuseCounter = 0
    private var hypothesis = BandwidthUsage.kBwNormal

    // Only used with networkStatePredictor
    // private var hypothesisPredicted = BandwidthUsage.kBwNormal

    // private var networkStatePredictor: NetworkStatePredictor? = null

    fun updateTrendline(
        recvDeltaMs: Double,
        sendDeltaMs: Double,
        sendTimeMs: Long,
        arrivalTimeMs: Long,
        packetSize: Long
    ) {
        val deltaMs = recvDeltaMs - sendDeltaMs
        ++numOfDeltas
        numOfDeltas = min(numOfDeltas, kDeltaCounterMax)
        if (firstArrivalTimeMs == -1L) {
            firstArrivalTimeMs = arrivalTimeMs
        }

        // Exponential backoff filter.
        accumulatedDelay += deltaMs
        smoothedDelay = smoothingCoef * smoothedDelay + (1 - smoothingCoef) * accumulatedDelay

        // Maintain packet window
        delayHist.addLast(
            PacketTiming((arrivalTimeMs - firstArrivalTimeMs).toDouble(), smoothedDelay, accumulatedDelay)
        )
        if (settings.enableSort) {
            // TODO - not currently enabled
        }
        if (delayHist.size > settings.windowSize) {
            delayHist.removeFirst()
        }

        // Simple linear regression.
        var trend = prevTrend
        if (delayHist.size == settings.windowSize) {
            // Update trend_ if it is possible to fit a line to the data. The delay
            // trend can be seen as an estimate of (send_rate - capacity)/capacity.
            // 0 < trend < 1   ->  the delay increases, queues are filling up
            //   trend == 0    ->  the delay does not change
            //   trend < 0     ->  the delay decreases, queues are being emptied
            trend = linearFitSlope(delayHist) ?: trend
            if (settings.enableCap) {
                val cap = computeSlopeCap(delayHist, settings)
                // We only use the cap to filter out overuse detections, not
                // to detect additional underuses.
                if (trend >= 0 && cap != null && trend > cap) {
                    trend = cap
                }
            }
        }
        timeSeriesLogger.trace {
            diagnosticContext.makeTimeSeriesPoint("trendline_updated")
                .addField("accumulated_delay_ms", accumulatedDelay)
                .addField("smoothed_delay_ms", smoothedDelay)
        }

        detect(trend, sendDeltaMs, arrivalTimeMs)
    }

    override fun update(
        recvDeltaMs: Double,
        sendDeltaMs: Double,
        sendTimeMs: Long,
        arrivalTimeMs: Long,
        packetSize: Long,
        calculatedDeltas: Boolean
    ) {
        if (calculatedDeltas) {
            updateTrendline(recvDeltaMs, sendDeltaMs, sendTimeMs, arrivalTimeMs, packetSize)
        }
        /* if (networkStatePredictor != null) {
               hypothesisPredicted = networkStatePredictor.update(sendTimeMs, arrivalTimeMs, hypothesis)
           }
         */
    }

    override fun state(): BandwidthUsage {
        /* if (networkStatePredictor != null) {
              return hypothesisPredicted
         */
        return hypothesis
    }

    private fun detect(trend: Double, tsDelta: Double, nowMs: Long) {
        if (numOfDeltas < 2) {
            hypothesis = BandwidthUsage.kBwNormal
            return
        }
        val modifiedTrend = min(numOfDeltas, kMinNumDeltas) * trend * thresholdGain
        prevModifiedTrend = modifiedTrend
        timeSeriesLogger.trace {
            diagnosticContext.makeTimeSeriesPoint("trendline_detect", nowMs)
                .addField("trend", modifiedTrend)
                .addField("threshold", threshold)
        }
        if (modifiedTrend > threshold) {
            if (timeOverUsing == -1.0) {
                // Initialize the timer. Assume that we've been
                // over-using half of the time since the previous
                // sample.
                timeOverUsing = tsDelta / 2
            } else {
                // Increment timer
                timeOverUsing += tsDelta
            }
            overuseCounter++
            if (timeOverUsing > overusingTimeThreshold && overuseCounter > 1) {
                if (trend >= prevTrend) {
                    timeOverUsing = 0.0
                    overuseCounter = 0
                    hypothesis = BandwidthUsage.kBwOverusing
                }
            }
        } else if (modifiedTrend < -threshold) {
            timeOverUsing = -1.0
            overuseCounter = 0
            hypothesis = BandwidthUsage.kBwUnderusing
        } else {
            timeOverUsing = -1.0
            overuseCounter = 0
            hypothesis = BandwidthUsage.kBwNormal
        }
        prevTrend = trend
        updateThreshold(modifiedTrend, nowMs)
    }

    private fun updateThreshold(modifiedTrend: Double, nowMs: Long) {
        if (lastUpdateMs == -1L) {
            lastUpdateMs = nowMs
        }

        if (abs(modifiedTrend) > threshold + kMaxAdaptOffsetMs) {
            // Avoid adapting the threshold to big latency spikes, caused e.g.,
            // by a sudden capacity drop.
            lastUpdateMs = nowMs
            return
        }

        val k = if (abs(modifiedTrend) < threshold) {
            kDown
        } else {
            kUp
        }
        val kMaxTimeDeltaMs = 100L
        val timeDeltaMs = min(nowMs - lastUpdateMs, kMaxTimeDeltaMs)
        threshold += k * (abs(modifiedTrend) - threshold) * timeDeltaMs
        threshold = threshold.coerceIn(6.0, 600.0)
        lastUpdateMs = nowMs
    }

    companion object {
        const val kDefaultTrendlineSmoothingCoeff = 0.9
        const val kDefaultTrendlineThresholdGain = 4.0

        const val kMaxAdaptOffsetMs = 15.0
        const val kOverusingTimeThreshold = 10.0
        const val kMinNumDeltas = 60
        const val kDeltaCounterMax = 1000

        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(TrendlineEstimator::class.java)
    }
}

/* TODO: this class is redundant if we don't have field trial settings - remove it? */
private class TrendlineEstimatorSettings() {
    // Sort the packets in the window. Should be redundant,
    // but then almost no cost.
    val enableSort = false

    // Cap the trendline slope based on the minimum delay seen
    // in the beginning_packets and end_packets respectively.
    val enableCap = false

    val beginningPackets = 7
    val endPackets = 7
    val capUncertainty = 0.0

    val windowSize = kDefaultTrendlineWindowSize

    companion object {
        const val kDefaultTrendlineWindowSize = 20
    }
}

private data class PacketTiming(
    val arrivalTimeMs: Double,
    val smoothedDelayMs: Double,
    val rawDelayMs: Double
)

private fun linearFitSlope(packets: ArrayDeque<PacketTiming>): Double? {
    check(packets.size >= 2)
    // Compute the "center of mass"
    var sumX = 0.0
    var sumY = 0.0
    for (packet in packets) {
        sumX += packet.arrivalTimeMs
        sumY += packet.smoothedDelayMs
    }
    val xAvg = sumX / packets.size
    val yAvg = sumY / packets.size
    // Compute the slope k = \sum (x_i-x_avg)(y_i-y_avg) / \sum (x_i-x_avg)^2
    var numerator = 0.0
    var denominator = 0.0
    for (packet in packets) {
        val x = packet.arrivalTimeMs
        val y = packet.smoothedDelayMs
        numerator += (x - xAvg) * (y - yAvg)
        denominator += (x - xAvg) * (x - xAvg)
    }
    if (denominator == 0.0) {
        return null
    }
    return numerator / denominator
}

private fun computeSlopeCap(packets: ArrayDeque<PacketTiming>, settings: TrendlineEstimatorSettings): Double? {
    check(1 <= settings.beginningPackets && settings.beginningPackets < packets.size)
    check(1 <= settings.endPackets && settings.endPackets < packets.size)
    check(settings.beginningPackets + settings.endPackets <= packets.size)
    var early = packets.first()
    for (i in 1 until settings.beginningPackets) {
        if (packets[i].rawDelayMs < early.rawDelayMs) {
            early = packets[i]
        }
    }
    val lateStart = packets.size - settings.endPackets
    var late = packets[lateStart]
    for (i in lateStart + 1 until packets.size) {
        if (packets[i].rawDelayMs < late.rawDelayMs) {
            late = packets[i]
        }
    }
    if (late.arrivalTimeMs - early.arrivalTimeMs < 1) {
        return null
    }
    return (late.rawDelayMs - early.rawDelayMs) / (late.arrivalTimeMs - early.arrivalTimeMs) + settings.capUncertainty
}
