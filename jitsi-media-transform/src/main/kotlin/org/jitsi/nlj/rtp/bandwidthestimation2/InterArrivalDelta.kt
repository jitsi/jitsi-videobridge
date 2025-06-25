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

import org.jitsi.utils.NEVER
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.max
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Duration
import java.time.Instant

/**
 * Helper class to compute the inter-arrival time delta and the size delta
 * between two timestamp groups. This code is branched from
 * modules/remote_bitrate_estimator/inter_arrival.
 * *
 * Based on WebRTC modules/congestion_controller/goog_cc/inter_arrival_delta.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
class InterArrivalDelta(
    private val sendTimeGroupLength: Duration
) {
    // TODO: pass parent logger in so we have log contexts
    private val logger = createLogger()

    private var currentTimestampGroup: SendTimeGroup = SendTimeGroup()
    private var prevTimestampGroup: SendTimeGroup = SendTimeGroup()
    private var numConsecutiveReorderedPackets: Int = 0

    data class ComputeDeltasResult(
        val computed: Boolean,
        val sendTimeDelta: Duration = Duration.ZERO,
        val arrivalTimeDelta: Duration = Duration.ZERO,
        val packetSizeDelta: Int = 0
    )

    fun computeDeltas(
        sendTime: Instant,
        arrivalTime: Instant,
        systemTime: Instant,
        packetSize: Long
    ): ComputeDeltasResult {
        var sendTimeDelta = Duration.ZERO
        var arrivalTimeDelta = Duration.ZERO
        var packetSizeDelta = 0

        var calculatedDeltas = false

        if (currentTimestampGroup.isFirstPacket()) {
            // We don't have enough data to update the filter, so we store it until we
            // have two frames of data to process.
            currentTimestampGroup.sendTime = sendTime
            currentTimestampGroup.firstSendTime = sendTime
            currentTimestampGroup.firstArrival = arrivalTime
        } else if (currentTimestampGroup.firstSendTime > sendTime) {
            // Reordered packet
            return ComputeDeltasResult(false)
        } else if (newTimestampGroup(arrivalTime, sendTime)) {
            // First packet of a later frame, the previous frame sample is ready.
            if (prevTimestampGroup.completeTime != NEVER) {
                sendTimeDelta = Duration.between(prevTimestampGroup.sendTime, currentTimestampGroup.sendTime)
                arrivalTimeDelta = Duration.between(prevTimestampGroup.completeTime, currentTimestampGroup.completeTime)
                val systemTimeDelta =
                    Duration.between(prevTimestampGroup.lastSystemTime, currentTimestampGroup.lastSystemTime)

                if (arrivalTimeDelta - systemTimeDelta >= kArrivalTimeOffsetThreshold) {
                    logger.warn(
                        "The arrival clock offset has changed (diff = " +
                            "${arrivalTimeDelta - systemTimeDelta}), resetting"
                    )
                    reset()
                    return ComputeDeltasResult(false)
                }
                if (arrivalTimeDelta < Duration.ZERO) {
                    // The group of packets has been reordered since receiving its local
                    // arrival timestamp.
                    ++numConsecutiveReorderedPackets
                    if (numConsecutiveReorderedPackets >= kReorderedResetThreshold) {
                        logger.warn(
                            "Packets between send burst arrived out of order, restting: " +
                                "arrivalTimeDelta=$arrivalTimeDelta, sendTimeDelta=$sendTimeDelta"
                        )
                        reset()
                    }
                    return ComputeDeltasResult(false)
                } else {
                    numConsecutiveReorderedPackets = 0
                }
                packetSizeDelta = currentTimestampGroup.size.toInt() - prevTimestampGroup.size.toInt()
                calculatedDeltas = true
            }
            prevTimestampGroup = currentTimestampGroup.copy()
            // The new timestamp is now the current frame
            currentTimestampGroup.firstSendTime = sendTime
            currentTimestampGroup.sendTime = sendTime
            currentTimestampGroup.firstArrival = arrivalTime
            currentTimestampGroup.size = 0
        } else {
            currentTimestampGroup.sendTime = max(currentTimestampGroup.sendTime, sendTime)
        }
        // Accumulate the frame size.
        currentTimestampGroup.size += packetSize
        currentTimestampGroup.completeTime = arrivalTime
        currentTimestampGroup.lastSystemTime = systemTime

        return ComputeDeltasResult(calculatedDeltas, sendTimeDelta, arrivalTimeDelta, packetSizeDelta)
    }

    private fun newTimestampGroup(arrivalTime: Instant, sendTime: Instant): Boolean {
        if (currentTimestampGroup.isFirstPacket()) {
            return false
        } else if (belongsToBurst(arrivalTime, sendTime)) {
            return false
        } else {
            return Duration.between(currentTimestampGroup.firstSendTime, sendTime) > sendTimeGroupLength
        }
    }

    private fun belongsToBurst(arrivalTime: Instant, sendTime: Instant): Boolean {
        check(currentTimestampGroup.completeTime != NEVER)
        val arrivalTimeDelta = Duration.between(currentTimestampGroup.completeTime, arrivalTime)
        val sendTimeDelta = Duration.between(currentTimestampGroup.sendTime, sendTime)
        if (sendTimeDelta == Duration.ZERO) {
            return true
        }
        val propagationDelta = arrivalTimeDelta - sendTimeDelta
        if (propagationDelta < Duration.ZERO &&
            arrivalTimeDelta <= kBurstDeltaThreshold &&
            Duration.between(currentTimestampGroup.firstArrival, arrivalTime) < kMaxBurstDuration
        ) {
            return true
        }
        return false
    }

    private fun reset() {
        numConsecutiveReorderedPackets = 0
        currentTimestampGroup = SendTimeGroup()
        prevTimestampGroup = SendTimeGroup()
    }

    data class SendTimeGroup(
        var size: Long = 0,
        var firstSendTime: Instant = NEVER,
        var sendTime: Instant = NEVER,
        var firstArrival: Instant = NEVER,
        var completeTime: Instant = NEVER,
        var lastSystemTime: Instant = NEVER
    ) {
        fun isFirstPacket(): Boolean = completeTime == NEVER
    }

    companion object {
        /**
         * After this many packet groups received out of order InterArrival will
         * reset, assuming that clocks have made a jump.
         */
        const val kReorderedResetThreshold = 3

        val kArrivalTimeOffsetThreshold = 3.secs

        val kBurstDeltaThreshold = 5.ms
        val kMaxBurstDuration = 100.ms
    }
}
