/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

// This file uses WebRTC's naming style for enums
@file:Suppress("ktlint:standard:enum-entry-name-case")

package org.jitsi.nlj.rtp.bandwidthestimation2

import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.utils.secs

/** Common defines for bandwidth estimation,
 * based on WebRTC modules/remote_bitrate_estimator/{include/bwe_defines.h,bwe_defines.cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */

val kCongestionControllerMinBitrate = 5000.bps

val kBitrateWindow = 1.secs

/**
 * Input to Rate Control.
 */
data class RateControlInput(
    var bwState: BandwidthUsage,
    var estimatedThroughput: Bandwidth?
) {
    /**
     * Assigns the values of the fields of [source] to the respective
     * fields of this [RateControlInput].
     *
     * @param source the [RateControlInput] the values of the fields of
     * which are to be assigned to the respective fields of this
     * [RateControlInput]
     */
    fun copy(source: RateControlInput) {
        bwState = source.bwState
        estimatedThroughput = source.estimatedThroughput
    }
}

/**
 * This is from WebRTC api/transport/bandwidth_usage.h in the same WebRTC revision.
 */
enum class BandwidthUsage {
    kBwNormal,
    kBwUnderusing,
    kBwOverusing
    // kLast not needed in Kotlin
}
