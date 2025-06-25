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
// This file uses WebRTC's naming style for constants
@file:Suppress("ktlint:standard:property-naming")

package org.jitsi.nlj.rtp.bandwidthestimation2

import kotlin.math.max
import kotlin.math.min

/** Interval budget,
 * based on WebRTC modules/pacing/interval_budget.{h,cc} in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */

class IntervalBudget(
    initialTargetRateKbps: Int,
    private val canBuildUpUnderuse: Boolean = false
) {
    private var targetRateKbps: Int = 0
    private var maxBytesInBudget: Long = 0
    private var bytesRemaining: Long = 0

    init {
        setTargetRateKbps(initialTargetRateKbps)
    }

    fun setTargetRateKbps(targetRateKbps: Int) {
        this.targetRateKbps = targetRateKbps
        maxBytesInBudget = (kWindowMs * targetRateKbps) / 8
        bytesRemaining = min(max(-maxBytesInBudget, bytesRemaining), maxBytesInBudget)
    }

    // TODO(tschumim): Unify IncreaseBudget and UseBudget to one function.
    fun increaseBudget(deltaTimeMs: Long) {
        val bytes = targetRateKbps * deltaTimeMs / 8
        if (bytesRemaining < 0 || canBuildUpUnderuse) {
            // We overused last interval, compensate this interval.
            bytesRemaining = min(bytesRemaining + bytes, maxBytesInBudget)
        } else {
            // If we underused last interval we can't use it this interval.
            bytesRemaining = min(bytes, maxBytesInBudget)
        }
    }

    fun useBudget(bytes: Long) {
        bytesRemaining = max(bytesRemaining - bytes, -maxBytesInBudget)
    }

    fun bytesRemaining(): Long {
        return max(0, bytesRemaining)
    }

    fun budgetRatio(): Double {
        if (maxBytesInBudget == 0L) {
            return 0.0
        }
        return bytesRemaining.toDouble() / maxBytesInBudget
    }

    fun targetRateKbps(): Int = targetRateKbps

    companion object {
        private const val kWindowMs = 500L
    }
}
