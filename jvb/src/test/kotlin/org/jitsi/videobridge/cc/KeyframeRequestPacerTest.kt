/*
 * Copyright @ 2026 - present 8x8, Inc.
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
package org.jitsi.videobridge.cc

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.videobridge.cc.KeyframeRequestPacer.Companion.MIN_KEY_FRAME_WAIT
import java.time.Instant

class KeyframeRequestPacerTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val pacer = KeyframeRequestPacer()
    private val t0: Instant = Instant.ofEpochMilli(1_000_000)
    private val wait = MIN_KEY_FRAME_WAIT.toMillis()

    init {
        context("Before any frame") {
            should("allow a request, so that a needed one is not held off") {
                pacer.mayRequest() shouldBe true
                pacer.shouldRequest(true) shouldBe true
                pacer.shouldRequest(false) shouldBe false
            }
            should("be out of any switching phase, since no keyframe group has arrived, unless the time is not known") {
                pacer.isOutOfSwitchingPhase(t0) shouldBe true
                pacer.isOutOfSwitchingPhase(null) shouldBe false
            }
        }
        context("After a keyframe") {
            pacer.onFrame(t0)
            pacer.onKeyframe(t0)
            should("hold off requests for the minimum wait, as of the most recent frame") {
                pacer.onFrame(t0.plusMillis(wait))
                pacer.mayRequest() shouldBe false
                pacer.onFrame(t0.plusMillis(wait + 1))
                pacer.mayRequest() shouldBe true
            }
            should("be out of the switching phase only after the minimum wait") {
                pacer.isOutOfSwitchingPhase(t0.plusMillis(wait)) shouldBe false
                pacer.isOutOfSwitchingPhase(t0.plusMillis(wait + 1)) shouldBe true
            }
            should("count keyframes within the wait as the same group, timed from its first") {
                pacer.onKeyframe(t0.plusMillis(wait))
                pacer.mostRecentKeyframeGroupArrivalTimeMs shouldBe t0.toEpochMilli()
                pacer.isOutOfSwitchingPhase(t0.plusMillis(wait + 1)) shouldBe true
            }
            should("start a new group with a keyframe after the wait") {
                pacer.onKeyframe(t0.plusMillis(wait + 1))
                pacer.mostRecentKeyframeGroupArrivalTimeMs shouldBe t0.toEpochMilli() + wait + 1
                pacer.isOutOfSwitchingPhase(t0.plusMillis(2 * wait)) shouldBe false
            }
            should("ignore a frame or keyframe with no time") {
                pacer.onFrame(null)
                pacer.onKeyframe(null)
                pacer.mostRecentKeyframeGroupArrivalTimeMs shouldBe t0.toEpochMilli()
                pacer.mayRequest() shouldBe false
            }
        }
    }
}
