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

import org.jitsi.nlj.util.DataSize

/** Congestion window pushback controller,
 * based on WebRTC modules/congestion_controller/goog_cc/congestion_window_pushback_controller.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings.
 */
class CongestionWindowPushbackController {
    fun updateOutstandingData(outstandingBytes: Long) {
        this.outstandingBytes = outstandingBytes
    }

    fun updatePacingQueue(pacingBytes: Long) {
        this.pacingBytes = pacingBytes
    }

    fun updateTargetBitrate(bitrateBps: Int): Int {
        if (currentDataWindow == null || currentDataWindow == DataSize.ZERO) {
            return bitrateBps
        }
        var totalBytes = outstandingBytes
        if (addPacing) {
            totalBytes += pacingBytes
        }
        val fillRatio = totalBytes / currentDataWindow!!.bytes.toLong()
        if (fillRatio > 1.5) {
            encodingRateRatio *= 0.9
        } else if (fillRatio > 1.0) {
            encodingRateRatio *= 0.95
        } else if (fillRatio < 0.1) {
            encodingRateRatio = 1.0
        } else {
            encodingRateRatio *= 1.05
            encodingRateRatio = Math.min(encodingRateRatio, 1.0)
        }
        val adjustedTargetBitrateBps = (bitrateBps * encodingRateRatio).toInt()

        // Do not adjust below the minimum pushback bitrate but do obey if the
        // original estimate is below it.
        return if (adjustedTargetBitrateBps < minPushbackTargetBitrateBps) {
            Math.min(bitrateBps, minPushbackTargetBitrateBps)
        } else {
            adjustedTargetBitrateBps
        }
    }

    fun setDataWindow(dataWindow: DataSize) {
        currentDataWindow = dataWindow
    }

    private val addPacing = false

    private val minPushbackTargetBitrateBps: Int = kDefaultMinPushbackTargetBitrateBps

    private var currentDataWindow: DataSize? = null

    private var outstandingBytes = 0L

    private var pacingBytes = 0L

    private var encodingRateRatio = 1.0
}

/** From rtc_base/experiments/rate_control_settings.cc */

private const val kDefaultMinPushbackTargetBitrateBps = 30000
