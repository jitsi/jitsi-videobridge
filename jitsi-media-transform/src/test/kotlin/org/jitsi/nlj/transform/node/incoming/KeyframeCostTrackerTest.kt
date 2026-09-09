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
package org.jitsi.nlj.transform.node.incoming

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.bits

/**
 * Frames are 3000 ticks (33ms at 90 kHz) apart. A frame is folded in once the grace period (250ms) has passed since
 * its most recent packet, and a stream is warm 1s after it became active, so tests advance the clock explicitly.
 */
class KeyframeCostTrackerTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    private val tracker = KeyframeCostTracker()

    init {
        context("a keyframe marked only on its first packet, as with VP8 and AV1") {
            tracker.observe(1000, 1200, true, nowMs = 0)
            repeat(4) { tracker.observe(1000, 1200, false, nowMs = 5) }
            tracker.observe(4000, 300, false, nowMs = 33)
            should("not be folded in until the grace period has passed") {
                tracker.numKeyframes shouldBe 0
            }
            context("once the grace period has passed") {
                tracker.observe(7000, 300, false, nowMs = 300)
                should("be measured as the size of all of its packets") {
                    tracker.numKeyframes shouldBe 1
                    tracker.getMeanKeyframeSize(300) shouldBe (5 * 1200 * 8L).bits
                    tracker.lastKeyframeSize shouldBe (5 * 1200 * 8L).bits
                }
            }
        }

        context("a keyframe marked only on spatial layer 0, as with VP9 K-SVC") {
            repeat(2) { tracker.observe(1000, 1000, true, nowMs = 0) }
            repeat(3) { tracker.observe(1000, 1000, false, nowMs = 2) }
            repeat(5) { tracker.observe(1000, 1000, false, nowMs = 4) }
            tracker.observe(4000, 300, false, nowMs = 300)
            should("include the upper spatial layers") {
                tracker.numKeyframes shouldBe 1
                tracker.getMeanKeyframeSize(300) shouldBe (10 * 1000 * 8L).bits
            }
        }

        context("a keyframe with no frame after it, as on a static source") {
            tracker.observe(1000, 1200, true, nowMs = 0)
            should("not be counted before the grace period") {
                tracker.getMeanKeyframeSize(100).shouldBeNull()
            }
            should("be counted once the grace period has passed, without another packet arriving") {
                tracker.getMeanKeyframeSize(300) shouldBe (1200 * 8L).bits
                tracker.numKeyframes shouldBe 1
            }
        }

        context("a large keyframe paced out over longer than the grace period") {
            tracker.observe(1000, 1200, true, nowMs = 0)
            tracker.observe(1000, 1200, false, nowMs = 200)
            tracker.observe(1000, 1200, false, nowMs = 400)
            tracker.observe(1000, 1200, false, nowMs = 600)
            tracker.observe(4000, 300, false, nowMs = 700)
            should("be measured whole") {
                tracker.getMeanKeyframeSize(1000) shouldBe (4 * 1200 * 8L).bits
                tracker.numKeyframes shouldBe 1
            }
        }

        context("delta frames") {
            tracker.observe(1000, 1200, false, nowMs = 0)
            tracker.observe(4000, 1200, false, nowMs = 300)
            tracker.observe(7000, 1200, false, nowMs = 600)
            should("not be counted as keyframes") {
                tracker.numKeyframes shouldBe 0
                tracker.getMeanKeyframeSize(1000).shouldBeNull()
            }
            should("count towards the stream bitrate") {
                // 3600 bytes over exactly 1s since the stream became active.
                tracker.getStreamBitrate(1000).bps shouldBe 3600 * 8L
            }
        }

        context("a packet of a frame arriving after the next frame has started") {
            tracker.observe(1000, 1200, true, nowMs = 0)
            tracker.observe(4000, 300, false, nowMs = 33)
            tracker.observe(1000, 1200, false, nowMs = 80)
            tracker.observe(7000, 300, false, nowMs = 400)
            should("count towards its frame") {
                tracker.numKeyframes shouldBe 1
                tracker.getMeanKeyframeSize(400) shouldBe (2 * 1200 * 8L).bits
            }
        }

        context("a keyframe's marked packet arriving late, as when it is retransmitted") {
            repeat(4) { tracker.observe(1000, 1200, false, nowMs = 5) }
            tracker.observe(4000, 300, false, nowMs = 33)
            tracker.observe(1000, 1200, true, nowMs = 80)
            tracker.observe(7000, 300, false, nowMs = 400)
            should("still make the frame a keyframe") {
                tracker.numKeyframes shouldBe 1
                tracker.getMeanKeyframeSize(400) shouldBe (5 * 1200 * 8L).bits
            }
        }

        context("a frame whose first packet arrives after a newer frame's first packet") {
            tracker.observe(4000, 300, false, nowMs = 0)
            tracker.observe(1000, 1200, true, nowMs = 5)
            tracker.observe(7000, 300, false, nowMs = 300)
            should("still be counted") {
                tracker.numKeyframes shouldBe 1
                tracker.getMeanKeyframeSize(300) shouldBe (1200 * 8L).bits
            }
        }

        context("a packet arriving after its frame has been folded in") {
            tracker.observe(1000, 1200, true, nowMs = 0)
            tracker.observe(4000, 300, false, nowMs = 300)
            tracker.observe(1000, 1200, true, nowMs = 310)
            tracker.observe(7000, 300, false, nowMs = 600)
            should("be ignored") {
                tracker.numKeyframes shouldBe 1
                tracker.getMeanKeyframeSize(600) shouldBe (1200 * 8L).bits
            }
        }

        context("a backwards jump in timestamp of more than a second") {
            tracker.observe(1_000_000, 1200, true, nowMs = 0)
            // A restarted timeline, 3s behind: fold what we have and start over, instead of treating everything
            // on the new timeline as late.
            tracker.observe(730_000, 1000, true, nowMs = 10)
            tracker.observe(733_000, 300, false, nowMs = 300)
            should("be treated as a new timeline rather than reordering") {
                tracker.numKeyframes shouldBe 2
                tracker.lastKeyframeSize shouldBe (1000 * 8L).bits
            }
        }

        context("the mean") {
            tracker.observe(1000, 1000, true, nowMs = 0)
            tracker.observe(4000, 100, false, nowMs = 300)
            tracker.observe(7000, 2000, true, nowMs = 600)
            tracker.observe(10000, 100, false, nowMs = 900)
            should("weight the latest keyframe by alpha") {
                tracker.numKeyframes shouldBe 2
                tracker.getMeanKeyframeSize(900) shouldBe (8000 * 0.75 + 16000 * 0.25).toLong().bits
            }
        }

        context("the keyframe bitrate") {
            tracker.observe(1000, 1000, true, nowMs = 0)
            tracker.observe(4000, 100, false, nowMs = 300)
            tracker.observe(90_000, 1000, true, nowMs = 1000)
            tracker.observe(93_000, 100, false, nowMs = 1300)
            should("measure keyframe bytes over the same window as the stream bitrate") {
                // 2000 keyframe bytes and 2200 bytes in all, both over the 1.3s the stream has been active.
                tracker.getKeyframeBitrate(1300).bps shouldBe (2000 * 8 * 1000L) / 1300
                tracker.getStreamBitrate(1300).bps shouldBe (2200 * 8 * 1000L) / 1300
                tracker.debugState(1300)["keyframe_bitrate_bps"].asLong() shouldBeGreaterThan 0L
            }
            should("decay once no keyframes have arrived for the window") {
                tracker.getKeyframeBitrate(1300 + 60_000).bps shouldBe 0L
            }
        }

        context("warming up") {
            tracker.observe(1000, 1200, true, nowMs = 0)
            should("take a second of activity") {
                tracker.isWarm(999) shouldBe false
                tracker.isWarm(1000) shouldBe true
            }
            should("not read the first packets as a sustained rate") {
                // 1200 bytes in 100ms is not reported as 96 kbps; the window is at least the warm-up period.
                tracker.getStreamBitrate(100).bps shouldBe 1200 * 8L
            }
        }

        context("a stream resuming after being idle for a whole window") {
            tracker.observe(1000, 1000, false, nowMs = 0)
            tracker.observe(4000, 1000, false, nowMs = 1000)
            tracker.observe(1_000_000, 2000, true, nowMs = 20_000)
            should("no longer be warm") {
                tracker.isWarm(20_100) shouldBe false
            }
            should("not report its first frame as an inflated bitrate") {
                tracker.getStreamBitrate(20_100).bps shouldBe 2000 * 8L
            }
            context("once it has been active again for a second") {
                tracker.observe(1_003_000, 500, false, nowMs = 21_000)
                should("be warm, with a bitrate over the time since it resumed") {
                    tracker.isWarm(21_000) shouldBe true
                    tracker.getStreamBitrate(21_000).bps shouldBe 2500 * 8L
                    tracker.getMeanKeyframeSize(21_000) shouldBe (2000 * 8L).bits
                }
            }
        }

        context("an RTP timestamp wrapping around") {
            tracker.observe(0xffff_ff00L, 1200, true, nowMs = 0)
            tracker.observe(100L, 300, false, nowMs = 300)
            should("fold in the frame before the wrap") {
                tracker.numKeyframes shouldBe 1
                tracker.getMeanKeyframeSize(300) shouldBe (1200 * 8L).bits
            }
        }
    }
}
