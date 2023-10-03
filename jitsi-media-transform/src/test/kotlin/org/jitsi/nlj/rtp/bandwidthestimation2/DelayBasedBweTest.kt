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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.createLogger
import org.jitsi.utils.ms

/**
 * Unit tests for Delay-Based BWE,
 * based on WebRTC modules/congestion_controller/goog_cc/delay_based_bwe_unittest.cc in
 * WebRTC 8284f2b4e8670529d039a8b6c73ec5f1d760bd21.
 *
 */
class DelayBasedBweTest : ShouldSpec() {
    val logger = createLogger()
    val diagnosticContext = DiagnosticContext()

    init {
        context("ProbeDetection") {
            should("work properly") {
                val test = OneDelayBasedBweTest(logger, diagnosticContext)

                var nowMs = test.clock.millis()

                // First burst sent at 8 * 1000 / 10 = 800 kbps.
                repeat(kNumProbesCluster0) {
                    test.clock.elapse(10.ms)
                    nowMs = test.clock.millis()
                    test.incomingFeedback(nowMs, nowMs, 1000, kPacingInfo0)
                }
                test.bitrateObserver.updated shouldBe true

                // Second burst sent at 8 * 1000 / 5 = 1600 kbps.
                repeat(kNumProbesCluster1) {
                    test.clock.elapse(5.ms)
                    nowMs = test.clock.millis()
                    test.incomingFeedback(nowMs, nowMs, 1000, kPacingInfo1)
                }
                test.bitrateObserver.updated shouldBe true
                test.bitrateObserver.latestBitrate shouldBeGreaterThan 1500000
            }
        }
    }

    companion object {
        const val kNumProbesCluster0 = 5
        const val kNumProbesCluster1 = 8
        val kPacingInfo0 = PacedPacketInfo(0, kNumProbesCluster0, 2000)
        val kPacingInfo1 = PacedPacketInfo(1, kNumProbesCluster1, 4000)
        const val kTargetUtilizationFraction = 0.95
    }
}
