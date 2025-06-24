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
import java.time.Instant

/**
 * Basic implementation to estimate acknowledged bitrate.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/acknowledged_bitrate_estimator.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */
class AcknowledgedBitrateEstimator(
    val bitrateEstimator: BitrateEstimator
) : AcknowledgedBitrateEstimatorInterface {
    private var alrEndedTime: Instant? = null
    private var inAlr = false

    constructor() : this(BitrateEstimator())

    override fun incomingPacketFeedbackVector(packetFeedbackVector: List<PacketResult>) {
        check(packetFeedbackVector.isSortedByReceiveTime())
        for (packet in packetFeedbackVector) {
            if (alrEndedTime != null && packet.sentPacket.sendTime > alrEndedTime) {
                bitrateEstimator.expectFastRateChange()
                alrEndedTime = null
            }
            val acknowledgedEstimate = packet.sentPacket.size + packet.sentPacket.priorUnackedData
            bitrateEstimator.update(packet.receiveTime, acknowledgedEstimate, inAlr)
        }
    }

    override fun bitrate(): Bandwidth? = bitrateEstimator.bitrate()

    override fun peekRate(): Bandwidth? = bitrateEstimator.peekRate()

    override fun setAlr(inAlr: Boolean) {
        this.inAlr = inAlr
    }

    override fun setAlrEndedTime(alrEndedTime: Instant) {
        this.alrEndedTime = alrEndedTime
    }
}

private fun List<PacketResult>.isSortedByReceiveTime(): Boolean {
    return this.asSequence().zipWithNext { a, b -> a.receiveTime <= b.receiveTime }.all { it }
}
