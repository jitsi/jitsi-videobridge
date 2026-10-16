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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.RtpLayerDesc.Companion.SUSPENDED_ENCODING_ID
import org.jitsi.videobridge.cc.EncodingSwitchPolicy.ALL_LIVE
import org.jitsi.videobridge.cc.EncodingSwitchPolicy.acceptKeyframe
import org.jitsi.videobridge.cc.EncodingSwitchPolicy.effectiveTarget
import org.jitsi.videobridge.cc.EncodingSwitchPolicy.needsKeyframeAfter
import org.jitsi.videobridge.cc.EncodingSwitchPolicy.switchPossible

class EncodingSwitchPolicyTest : ShouldSpec() {
    private fun live(vararg eids: Int) = liveEncodings { it in eids }

    /** Encodings [eids] are live, but only just resumed, as when the whole source has. */
    private fun resumed(vararg eids: Int) = object : EncodingLiveness {
        override fun isLive(eid: Int) = eid in eids
        override fun hasOutlasted(eid: Int, otherEid: Int) = false
    }

    init {
        context("accepting a keyframe") {
            context("while nothing is forwarded") {
                should("take any keyframe at or below the target") {
                    acceptKeyframe(SUSPENDED_ENCODING_ID, 0, 2, ALL_LIVE) shouldBe true
                    acceptKeyframe(SUSPENDED_ENCODING_ID, 2, 2, ALL_LIVE) shouldBe true
                    acceptKeyframe(SUSPENDED_ENCODING_ID, 1, 0, ALL_LIVE) shouldBe false
                }
            }
            context("on the current encoding") {
                should("take it whatever the target") {
                    acceptKeyframe(1, 1, 1, ALL_LIVE) shouldBe true
                    acceptKeyframe(1, 1, 2, ALL_LIVE) shouldBe true
                    acceptKeyframe(2, 2, 0, ALL_LIVE) shouldBe true
                    acceptKeyframe(2, 2, 0, live(0)) shouldBe true
                }
            }
            context("on a higher encoding") {
                should("take it only if it is at or below the target") {
                    acceptKeyframe(0, 1, 2, ALL_LIVE) shouldBe true
                    acceptKeyframe(0, 2, 2, ALL_LIVE) shouldBe true
                    acceptKeyframe(0, 2, 1, ALL_LIVE) shouldBe false
                    acceptKeyframe(0, 1, 0, ALL_LIVE) shouldBe false
                }
            }
            context("on a lower encoding") {
                should("take it as the step down to a lower target") {
                    acceptKeyframe(2, 0, 0, ALL_LIVE) shouldBe true
                    acceptKeyframe(2, 1, 1, ALL_LIVE) shouldBe true
                }
                should("not take one below the effective target or above the target, either a second switch") {
                    acceptKeyframe(2, 0, 1, ALL_LIVE) shouldBe false
                    acceptKeyframe(2, 0, 1, live(0, 2)) shouldBe true
                    acceptKeyframe(2, 1, 0, ALL_LIVE) shouldBe false
                }
                should("not take it while the current encoding is live and the target is not lower") {
                    acceptKeyframe(1, 0, 1, ALL_LIVE) shouldBe false
                    acceptKeyframe(1, 0, 2, ALL_LIVE) shouldBe false
                    acceptKeyframe(2, 0, 2, live(0, 1, 2)) shouldBe false
                }
                should("take it when the current encoding has stopped being sent, unless it is above the target") {
                    acceptKeyframe(1, 0, 1, live(0)) shouldBe true
                    acceptKeyframe(2, 1, 2, live(0, 1)) shouldBe true
                    acceptKeyframe(2, 1, 0, live(0, 1)) shouldBe false
                }
                should(
                    "not take a keyframe below the effective target when the current encoding stopped, a second switch"
                ) {
                    acceptKeyframe(2, 0, 2, live(0, 1)) shouldBe false
                    acceptKeyframe(2, 0, 2, live(0)) shouldBe true
                }
                should("not take it when the whole source has just resumed, since the current encoding is about to") {
                    val resumed = object : EncodingLiveness {
                        override fun isLive(eid: Int) = eid == 0
                        override fun hasOutlasted(eid: Int, otherEid: Int) = false
                    }
                    acceptKeyframe(2, 0, 2, resumed) shouldBe false
                }
            }
        }
        context("the effective target") {
            should("be the target when it is live") {
                effectiveTarget(2, ALL_LIVE) shouldBe 2
                effectiveTarget(0, live(0)) shouldBe 0
            }
            should("be the highest live encoding below the target otherwise") {
                effectiveTarget(2, live(0, 1)) shouldBe 1
                effectiveTarget(2, live(0)) shouldBe 0
                effectiveTarget(1, live(0, 2)) shouldBe 0
            }
            should("be the target itself when nothing at or below it is live") {
                effectiveTarget(1, live(2)) shouldBe 1
                effectiveTarget(2, live()) shouldBe 2
            }
            should("be suspended for a suspended target") {
                effectiveTarget(SUSPENDED_ENCODING_ID, ALL_LIVE) shouldBe SUSPENDED_ENCODING_ID
            }
        }
        context("needing a keyframe after taking one") {
            should("not when the effective target has been reached") {
                needsKeyframeAfter(2, 2, ALL_LIVE) shouldBe false
                needsKeyframeAfter(0, 2, live(0)) shouldBe false
                needsKeyframeAfter(1, 2, live(0, 1)) shouldBe false
            }
            should("when a higher live encoding at or below the target exists") {
                needsKeyframeAfter(0, 2, ALL_LIVE) shouldBe true
                needsKeyframeAfter(0, 1, live(0, 1)) shouldBe true
            }
            should("when forwarding above the target") {
                needsKeyframeAfter(2, 0, ALL_LIVE) shouldBe true
                needsKeyframeAfter(1, 0, live(1)) shouldBe true
            }
        }
        context("whether a switch is possible") {
            should("when frames arrive from an encoding in the target's direction") {
                switchPossible(0, 2, 2, ALL_LIVE) shouldBe true
                switchPossible(0, 1, 2, ALL_LIVE) shouldBe true
                switchPossible(2, 0, 0, ALL_LIVE) shouldBe true
                switchPossible(2, 1, 0, ALL_LIVE) shouldBe true
            }
            should("not when frames arrive from an encoding away from the target") {
                switchPossible(1, 0, 1, ALL_LIVE) shouldBe false
                switchPossible(1, 0, 2, ALL_LIVE) shouldBe false
                switchPossible(1, 2, 1, ALL_LIVE) shouldBe false
                switchPossible(1, 2, 0, ALL_LIVE) shouldBe false
                switchPossible(0, 2, 1, ALL_LIVE) shouldBe false
                switchPossible(1, 1, 1, ALL_LIVE) shouldBe false
            }
            should("when the current encoding has stopped being sent while a lower one kept flowing") {
                switchPossible(1, 0, 1, live(0)) shouldBe true
                switchPossible(1, 0, 2, live(0)) shouldBe true
            }
            should("not when only encodings above the target are flowing, since nothing reachable can be requested") {
                switchPossible(1, 2, 1, live(0, 2)) shouldBe false
                switchPossible(0, 2, 0, live(2)) shouldBe false
            }
            should("not on the frames after the whole source resumes, until the current encoding has had time to") {
                switchPossible(1, 0, 1, resumed(0)) shouldBe false
                switchPossible(2, 0, 2, resumed(0, 1)) shouldBe false
            }
            should("not for a frame whose encoding is unknown") {
                switchPossible(1, SUSPENDED_ENCODING_ID, 2, live(0)) shouldBe false
            }
        }
    }
}
