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

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.bytes
/**
 * Unit tests for CongestionWindowPushbackController.
 *
 * Based on WebRTC modules/congestion_controller/goog_cc/congestion_window_pushback_controller_unittest.cc in
 * WebRTC tag branch-heads/7204 (Chromium 138), skipping tests of field trials.
 */
class CongestionWindowPushbackControllerTest : FreeSpec() {
    init {
        "FullCongestionWindow" {
            val cwndController = CongestionWindowPushbackController()
            cwndController.updateOutstandingData(100000)
            cwndController.setDataWindow(50000.bytes)

            var bitrateBps = 80000
            bitrateBps = cwndController.updateTargetBitrate(bitrateBps)
            bitrateBps shouldBe 72000

            cwndController.setDataWindow(50000.bytes)
            bitrateBps = cwndController.updateTargetBitrate(bitrateBps)
            bitrateBps shouldBe (72000 * 0.9 * 0.9).toInt()
        }

        "NormalCongestionWindow" {
            val cwndController = CongestionWindowPushbackController()
            cwndController.updateOutstandingData(199999)
            cwndController.setDataWindow(200000.bytes)

            var bitrateBps = 80000
            bitrateBps = cwndController.updateTargetBitrate(bitrateBps)
            bitrateBps shouldBe 80000
        }

        "LowBitrate" {
            val cwndController = CongestionWindowPushbackController()
            cwndController.updateOutstandingData(100000)
            cwndController.setDataWindow(50000.bytes)

            var bitrateBps = 35000
            bitrateBps = cwndController.updateTargetBitrate(bitrateBps)
            bitrateBps shouldBe (35000 * 0.9).toInt()

            cwndController.setDataWindow(20000.bytes)
            bitrateBps = cwndController.updateTargetBitrate(bitrateBps)
            bitrateBps shouldBe 30000
        }

        "NoPushbackOnDataWindowUnset" {
            val cwndController = CongestionWindowPushbackController()
            cwndController.updateOutstandingData(1e8.toLong()) // Large number

            var bitrateBps = 80000
            bitrateBps = cwndController.updateTargetBitrate(bitrateBps)
            bitrateBps shouldBe 80000
        }

        /* Skipping tests with field trials set */
    }
}
