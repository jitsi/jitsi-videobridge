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
package org.jitsi.nlj.rtp.scream

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.ms
import org.jitsi.utils.time.FakeClock
import java.time.Instant

/** Unit tests for LossEstimator,
 * based on WebRTC modules/congestion_controller/scream/loss_estimator_unittest.cc in
 * WebRTC tag branch-heads/8078 (Chromium 156).
 */
private fun createFeedbackWithLoss(feedbackTime: Instant, withLoss: Boolean): ScreamFeedback {
    val feedback = ScreamFeedback()
    feedback.feedbackTime = feedbackTime
    feedback.numReceivedPackets = 1
    feedback.ackedNotMarkedSize = 1000.bytes
    if (withLoss) {
        feedback.numLostPackets = 1
        feedback.ackedNotMarkedSize += 1000.bytes
    }
    return feedback
}

class LossEstimatorTest : FreeSpec() {
    init {
        "EstimatesCongestionLevel" {
            val clock = FakeClock().apply { setTime(Instant.ofEpochSecond(1000)) }
            val params = ScreamV2Parameters()
            val estimator = LossEstimator(params)

            estimator.congestionLevel() shouldBe 0.0
            estimator.congested() shouldBe false

            // RTT 1: Loss occurs. Since last_rtt_update_time_ starts at MinusInfinity,
            // this report executes the RTT boundary update immediately.
            val feedback1 = createFeedbackWithLoss(clock.instant(), withLoss = true)
            estimator.update(feedback1, 25.ms) shouldBe true
            estimator.congestionLevel() shouldBe (1.0 / 3.0 plusOrMinus 0.001)
            estimator.congested() shouldBe false

            // RTT 2: Loss occurs.
            clock.elapse(25.ms)
            val feedback2 = createFeedbackWithLoss(clock.instant(), withLoss = true)
            estimator.update(feedback2, 25.ms) shouldBe true
            estimator.congestionLevel() shouldBe (2.0 / 3.0 plusOrMinus 0.001)
            estimator.congested() shouldBe false

            // RTT 3: Loss occurs.
            clock.elapse(25.ms)
            val feedback3 = createFeedbackWithLoss(clock.instant(), withLoss = true)
            estimator.update(feedback3, 25.ms) shouldBe true
            estimator.congestionLevel() shouldBe (1.0 plusOrMinus 0.001)
            estimator.congested() shouldBe true

            // RTT 4: No Loss (Step Down of 0.5).
            clock.elapse(25.ms)
            val feedback4 = createFeedbackWithLoss(clock.instant(), withLoss = false)
            estimator.update(feedback4, 25.ms) shouldBe false
            estimator.congestionLevel() shouldBe (0.5 plusOrMinus 0.001)
            estimator.congested() shouldBe false

            // RTT 5: Loss occurs.
            clock.elapse(25.ms)
            val feedback5 = createFeedbackWithLoss(clock.instant(), withLoss = true)
            estimator.update(feedback5, 25.ms) shouldBe true
            estimator.congestionLevel() shouldBe (0.5 + 1.0 / 3.0 plusOrMinus 0.001)
            estimator.congested() shouldBe false
        }

        "ClearsCongestionLevelUponFullRecovery" {
            val clock = FakeClock().apply { setTime(Instant.ofEpochSecond(1000)) }
            val params = ScreamV2Parameters()
            val estimator = LossEstimator(params)

            // Report loss.
            val feedback1 = createFeedbackWithLoss(clock.instant(), withLoss = true)
            estimator.update(feedback1, 25.ms) shouldBe true
            estimator.congestionLevel() shouldBeGreaterThan 0.0

            // Report recovery.
            val recoveryFeedback = ScreamFeedback()
            recoveryFeedback.feedbackTime = clock.instant()
            recoveryFeedback.numReceivedPackets = 1
            recoveryFeedback.ackedNotMarkedSize = 1000.bytes
            recoveryFeedback.numRecoveredPackets = 1
            estimator.update(recoveryFeedback, 25.ms) shouldBe false
            estimator.congestionLevel() shouldBe 0.0
        }
    }
}
