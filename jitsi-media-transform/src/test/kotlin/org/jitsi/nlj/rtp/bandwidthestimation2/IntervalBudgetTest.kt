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

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe

const val kWindowMs = 500L
const val kBitrateKbps = 100
const val kCanBuildUpUnderuse = true
const val kCanNotBuildUpUnderuse = false
fun timeToBytes(bitrateKbps: Int, timeMs: Long): Long {
    return bitrateKbps.toLong() * timeMs / 8
}

/** Unit tests for IntervalBudget,
 * based on WebRTC modules/pacing/interval_budget_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138).
 */

class IntervalBudgetTest : FreeSpec() {
    init {
        "InitailState" {
            val intervalBudget = IntervalBudget(kBitrateKbps)
            intervalBudget.budgetRatio() shouldBe 0.0
            intervalBudget.bytesRemaining() shouldBe 0
        }

        "Underuse" {
            val intervalBudget = IntervalBudget(kBitrateKbps)
            val deltaTimeMs = 50L
            intervalBudget.increaseBudget(deltaTimeMs)
            intervalBudget.budgetRatio() shouldBe kWindowMs / (100 * deltaTimeMs).toDouble()
            intervalBudget.bytesRemaining() shouldBe timeToBytes(kBitrateKbps, deltaTimeMs)
        }

        "DontUnderuseMoreThanMaxWindow" {
            val intervalBudget = IntervalBudget(kBitrateKbps)
            val deltaTimeMs = 1000L
            intervalBudget.increaseBudget(deltaTimeMs)
            intervalBudget.budgetRatio() shouldBe 1.0
            intervalBudget.bytesRemaining() shouldBe timeToBytes(kBitrateKbps, kWindowMs)
        }

        "DontUnderuseMoreThanMaxWindowWhenChangeBitrate" {
            val intervalBudget = IntervalBudget(kBitrateKbps)
            val deltaTimeMs = kWindowMs / 2
            intervalBudget.increaseBudget(deltaTimeMs)
            intervalBudget.setTargetRateKbps(kBitrateKbps / 10)
            intervalBudget.budgetRatio() shouldBe 1.0
            intervalBudget.bytesRemaining() shouldBe timeToBytes(kBitrateKbps / 10, kWindowMs)
        }

        "BalanceChangeOnBitrateChange" {
            val intervalBudget = IntervalBudget(kBitrateKbps)
            val deltaTimeMs = kWindowMs
            intervalBudget.increaseBudget(deltaTimeMs)
            intervalBudget.setTargetRateKbps(kBitrateKbps * 2)
            intervalBudget.budgetRatio() shouldBe 0.5
            intervalBudget.bytesRemaining() shouldBe timeToBytes(kBitrateKbps, kWindowMs)
        }

        "Overuse" {
            val intervalBudget = IntervalBudget(kBitrateKbps)
            val overuseTimeMs = 50L
            val usedBytes = timeToBytes(kBitrateKbps, overuseTimeMs)
            intervalBudget.useBudget(usedBytes)
            intervalBudget.budgetRatio() shouldBe -kWindowMs / (100 * overuseTimeMs).toDouble()
            intervalBudget.bytesRemaining() shouldBe 0
        }

        "DontOveruseMoreThanMaxWindow" {
            val intervalBudget = IntervalBudget(kBitrateKbps)
            val overuseTimeMs = 1000L
            val usedBytes = timeToBytes(kBitrateKbps, overuseTimeMs)
            intervalBudget.useBudget(usedBytes)
            intervalBudget.budgetRatio() shouldBe -1.0
            intervalBudget.bytesRemaining() shouldBe 0
        }

        "CanBuildUpUnderuseWhenConfigured" {
            val intervalBudget = IntervalBudget(kBitrateKbps, kCanBuildUpUnderuse)
            val deltaTimeMs = 50L
            intervalBudget.increaseBudget(deltaTimeMs)
            intervalBudget.budgetRatio() shouldBe kWindowMs / (100 * deltaTimeMs).toDouble()
            intervalBudget.bytesRemaining() shouldBe timeToBytes(kBitrateKbps, deltaTimeMs)

            intervalBudget.increaseBudget(deltaTimeMs)
            intervalBudget.budgetRatio() shouldBe 2 * kWindowMs / (100 * deltaTimeMs).toDouble()
            intervalBudget.bytesRemaining() shouldBe timeToBytes(kBitrateKbps, 2 * deltaTimeMs)
        }

        "CanNotBuildUpUnderuseWhenConfigured" {
            val intervalBudget = IntervalBudget(kBitrateKbps, kCanNotBuildUpUnderuse)
            val deltaTimeMs = 50L
            intervalBudget.increaseBudget(deltaTimeMs)
            intervalBudget.budgetRatio() shouldBe kWindowMs / (100 * deltaTimeMs).toDouble()
            intervalBudget.bytesRemaining() shouldBe timeToBytes(kBitrateKbps, deltaTimeMs)

            intervalBudget.increaseBudget(deltaTimeMs)
            intervalBudget.budgetRatio() shouldBe kWindowMs / (100 * deltaTimeMs).toDouble()
            intervalBudget.bytesRemaining() shouldBe timeToBytes(kBitrateKbps, deltaTimeMs)
        }
    }
}
