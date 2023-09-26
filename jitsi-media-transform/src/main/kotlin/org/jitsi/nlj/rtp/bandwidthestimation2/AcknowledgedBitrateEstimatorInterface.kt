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
 * Interface to estimate acknowledged bitrate.
 * *
 * Based on WebRTC modules/congestion_controller/goog_cc/acknowledged_bitrate_estimator_interface.{h,cc} in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 */
interface AcknowledgedBitrateEstimatorInterface {
    fun incomingPacketFeedbackVector(packetFeedbackVector: List<PacketResult>)
    fun bitrate(): Bandwidth?
    fun peekRate(): Bandwidth?
    fun setAlr(inAlr: Boolean)
    fun setAlrEndedTime(alrEndedTime: Instant)

    companion object {
        fun create(): AcknowledgedBitrateEstimatorInterface {
            /* TODO: return RobustThroughputEstimator if requested? */
            return AcknowledgedBitrateEstimator()
        }
    }
}
