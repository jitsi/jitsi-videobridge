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
import org.jitsi.nlj.util.bps
import org.jitsi.utils.NEVER
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import org.jitsi.utils.toDoubleMillis
import org.jitsi.utils.toRoundedEpochMilli
import java.time.Duration
import java.time.Instant

/** Delay-based bandwidth estimation,
 * based on WebRTC modules/congestion_controller/goog_cc/delay_based_bwe.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings, and APIs that aren't
 * used by Chrome have also been removed.
 */
class DelayBasedBwe(
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext
) {
    private val logger = parentLogger.createChildLogger(javaClass.name)

    private var interArrivalDelta: InterArrivalDelta? = null
    val delayDetector: DelayIncreaseDetectorInterface = TrendlineEstimator(logger, diagnosticContext)

    private var lastSeenPacket: Instant = NEVER

    /* private var umaRecorded: Boolean = false */

    val rateControl = AimdRateControl(sendSide = true)
    private var prevBitrate: Bandwidth = Bandwidth.ZERO
    private var prevState: BandwidthUsage = BandwidthUsage.kBwNormal

    fun incomingPacketFeedbackVector(
        msg: TransportPacketsFeedback,
        ackedBitrate: Bandwidth?,
        probeBitrate: Bandwidth?,
        /* networkEstimate: NetworkStateEstimate?, */
        inAlr: Boolean
    ): Result {
        val packetFeedbackVector = msg.sortedByReceiveTime()
        if (packetFeedbackVector.isEmpty()) {
            // TODO(holmer): An empty feedback vector here likely means that
            // all acks were too late and that the send time history had
            // timed out. We should reduce the rate when this occurs.
            logger.warn("Very late feedback received.")
            return Result()
        }

        /* Skipping uma_recorded, it's just for histograms. */
        var delayedFeedback = true
        var recoveredFromOveruse = false

        var prevDetectorState = delayDetector.state()
        for (packetFeedback in packetFeedbackVector) {
            delayedFeedback = false
            incomingPacketFeedback(packetFeedback, msg.feedbackTime)
            if (prevDetectorState == BandwidthUsage.kBwUnderusing &&
                delayDetector.state() == BandwidthUsage.kBwNormal
            ) {
                recoveredFromOveruse = true
            }
            prevDetectorState = delayDetector.state()
        }

        if (delayedFeedback) {
            // TODO(bugs.webrtc.org/10125): Design a better mechanism to safe-guard
            // against building very large network queues.
            return Result()
        }

        rateControl.inAlr = inAlr
        // rateControl.networkStateEstimate = networkStateEstimate
        return maybeUpdateEstimate(
            ackedBitrate,
            probeBitrate,
            /* networkEstimate, */
            recoveredFromOveruse,
            inAlr,
            msg.feedbackTime
        )
    }

    private fun incomingPacketFeedback(packetFeedback: PacketResult, atTime: Instant) {
        // Reset if the stream has timed out.
        if (lastSeenPacket == NEVER || Duration.between(lastSeenPacket, atTime) > kStreamTimeOut) {
            interArrivalDelta = InterArrivalDelta(kSendTimeGroupLength)
        }
        lastSeenPacket = atTime
        val packetSize = packetFeedback.sentPacket.size

        val calculatedDeltas = interArrivalDelta!!.computeDeltas(
            packetFeedback.sentPacket.sendTime,
            packetFeedback.receiveTime,
            atTime,
            packetSize.bytes.toLong()
        )

        delayDetector.update(
            calculatedDeltas.arrivalTimeDelta.toDoubleMillis(),
            calculatedDeltas.sendTimeDelta.toDoubleMillis(),
            packetFeedback.sentPacket.sendTime.toRoundedEpochMilli(),
            packetFeedback.receiveTime.toRoundedEpochMilli(),
            packetSize.bytes.toLong(),
            calculatedDeltas.computed
        )
    }

    fun triggerOveruse(atTime: Instant, linkCapacity: Bandwidth?): Bandwidth {
        val input = RateControlInput(BandwidthUsage.kBwOverusing, linkCapacity)
        return rateControl.update(input, atTime)
    }

    private fun maybeUpdateEstimate(
        ackedBitrate: Bandwidth?,
        probeBitrate: Bandwidth?,
        /* networkEstimate: NetworkStateEstimate?, */
        recoveredFromOveruse: Boolean,
        inAlr: Boolean,
        atTime: Instant
    ): Result {
        val result =
            // Currently overusing the bandwidth.
            if (delayDetector.state() == BandwidthUsage.kBwOverusing) {
                if (ackedBitrate != null && rateControl.timeToReduceFurther(atTime, ackedBitrate)) {
                    val targetBitrate = updateEstimate(atTime, ackedBitrate)
                    if (targetBitrate != null) {
                        Result(updated = true, targetBitrate = targetBitrate)
                    } else {
                        Result(updated = false)
                    }
                } else if (ackedBitrate == null && rateControl.validEstimate() &&
                    rateControl.initialTimeToReduceFurther(atTime)
                ) {
                    rateControl.setEstimate(rateControl.latestEstimate() / 2, atTime)
                    Result(updated = true, probe = false, targetBitrate = rateControl.latestEstimate())
                } else {
                    Result()
                }
            } else {
                if (probeBitrate != null) {
                    rateControl.setEstimate(probeBitrate, atTime)
                    Result(probe = true, updated = true, targetBitrate = rateControl.latestEstimate())
                } else {
                    val targetBitrate = updateEstimate(atTime, ackedBitrate)
                    if (targetBitrate != null) {
                        Result(
                            updated = true,
                            targetBitrate = targetBitrate,
                            recoveredFromOveruse = recoveredFromOveruse
                        )
                    } else {
                        Result(updated = false, recoveredFromOveruse = recoveredFromOveruse)
                    }
                }
            }
        val detectorState = delayDetector.state()
        if ((result.updated && prevBitrate != result.targetBitrate) ||
            detectorState != prevState
        ) {
            val bitrate = if (result.updated) {
                result.targetBitrate
            } else {
                prevBitrate
            }
            timeSeriesLogger.trace {
                diagnosticContext.makeTimeSeriesPoint("bwe_update_delay_based", atTime)
                    .addField("bitrate_bps", bitrate.bps)
                    .addField("detector_state", detectorState.name)
            }

            prevBitrate = bitrate
            prevState = detectorState
        }
        result.delayDetectorState = detectorState
        return result
    }

    private fun updateEstimate(atTime: Instant, ackedBitrate: Bandwidth?): Bandwidth? {
        val input = RateControlInput(delayDetector.state(), ackedBitrate)
        val targetRate = rateControl.update(input, atTime)
        return if (rateControl.validEstimate()) {
            targetRate
        } else {
            null
        }
    }

    fun onRttUpdate(avgRtt: Duration) {
        rateControl.rtt = avgRtt
    }

    fun latestEstimate(): Bandwidth? {
        if (!rateControl.validEstimate()) {
            return null
        }
        return rateControl.latestEstimate()
    }

    fun setStartBitrate(startBitrate: Bandwidth) {
        logger.info("BWE setting start bitrate to $startBitrate")
        rateControl.setStartBitrate(startBitrate)
    }

    fun setMinBitrate(minBitrate: Bandwidth) {
        rateControl.setMinBitrate(minBitrate)
    }

    fun getExpectedBwePeriod(): Duration {
        return rateControl.getExpectedBandwidthPeriod()
    }

    fun lastEstimate(): Bandwidth = prevBitrate
    fun lastState(): BandwidthUsage = prevState

    companion object {
        val kStreamTimeOut = 2.secs
        val kSendTimeGroupLength = 5.ms

        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(DelayBasedBwe::class.java)
    }

    data class Result(
        val updated: Boolean = false,
        val probe: Boolean = false,
        val targetBitrate: Bandwidth = 0.bps,
        val recoveredFromOveruse: Boolean = false,
        var delayDetectorState: BandwidthUsage = BandwidthUsage.kBwNormal
    )
}
