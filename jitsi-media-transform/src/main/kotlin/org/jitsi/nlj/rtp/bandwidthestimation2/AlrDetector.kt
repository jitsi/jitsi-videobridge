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

/**
 * An Application-limited region detector.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/alr_detector.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 *
 * Field trial settings have been generally removed, set to their default settings.
 */

data class AlrDetectorConfig(
    // Sent traffic ratio as a function of network capacity used to determine
    // application-limited region. ALR region start when bandwidth usage drops
    // below kAlrStartUsageRatio and ends when it raises above
    // kAlrEndUsageRatio. NOTE: This is intentionally conservative at the moment
    // until BW adjustments of application limited region is fine tuned.
    val bandwidthUsageRatio: Double = 0.65,
    val startBudgetLevelRatio: Double = 0.80,
    val stopBudgetLevelRatio: Double = 0.50
)

/** Application limited region detector is a class that utilizes signals of
elapsed time and bytes sent to estimate whether network traffic is
currently limited by the application's ability to generate traffic.

AlrDetector provides a signal that can be utilized to adjust
estimate bandwidth.
Note: This class is not thread-safe.
*/
class AlrDetector {
    fun onBytesSent(bytesSent: Long, sendTimeMs: Long) {
        if (lastSendTimeMs == null) {
            lastSendTimeMs = sendTimeMs
            // Since the duration for sending the bytes is unknwon, return without
            // updating alr state.
            return
        }
        val deltaTimeMs = sendTimeMs - lastSendTimeMs!!
        lastSendTimeMs = sendTimeMs

        alrBudget.useBudget(bytesSent)
        alrBudget.increaseBudget(deltaTimeMs)
        var stateChanged = false
        if (alrBudget.budgetRatio() > conf.startBudgetLevelRatio &&
            alrStartedTimeMs == null
        ) {
            alrStartedTimeMs = System.currentTimeMillis()
            stateChanged = true
        } else if (alrBudget.budgetRatio() < conf.startBudgetLevelRatio &&
            alrStartedTimeMs != null
        ) {
            stateChanged = true
            alrStartedTimeMs = null
        }
        if (stateChanged) {
            // TODO?  Log event
        }
    }

    // Set current estimated bandwidth.
    fun setEstimatedBitrate(bitrateBps: Int) {
        val targetRateKbps = bitrateBps.toDouble() * conf.bandwidthUsageRatio / 1000
        alrBudget.setTargetRateKbps(targetRateKbps.toInt())
    }

    // Returns time in milliseconds when the current application-limited region
    // started or empty result if the sender is currently not application-limited.
    fun getApplicationLimitedRegionStartTime(): Long? = alrStartedTimeMs

    private val conf = kDefaultProbingScreenshareBweSettings

    private var lastSendTimeMs: Long? = null

    private val alrBudget = IntervalBudget(0, true)
    private var alrStartedTimeMs: Long? = null

    companion object {
        /* From rtc_base/experiments/alr_experiment.cc */
        val kDefaultProbingScreenshareBweSettings = AlrDetectorConfig(
            bandwidthUsageRatio = 0.80,
            startBudgetLevelRatio = 0.40,
            stopBudgetLevelRatio = -0.60
        )
    }
}
