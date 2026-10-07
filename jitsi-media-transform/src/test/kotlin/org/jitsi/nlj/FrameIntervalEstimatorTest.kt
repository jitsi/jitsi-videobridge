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
package org.jitsi.nlj

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.FrameIntervalEstimator.Companion.MAX_STRAGGLER_SEQUENCE_DELTA
import org.jitsi.nlj.FrameIntervalEstimator.PacketKind

class FrameIntervalEstimatorTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    private val estimator = FrameIntervalEstimator()

    /** The longest interval to learn, as three camera timeouts. */
    private val maxIntervalMs = 3000L

    /**
     * Classifies a packet at [nowMs] with sequence number [seq] and timestamp [rtpTimestamp], starting a frame if it
     * begins one, and returns its kind.
     */
    private fun packet(nowMs: Long, seq: Int, rtpTimestamp: Long): PacketKind =
        estimator.classify(seq, rtpTimestamp).also {
            if (it == PacketKind.NEW_FRAME || it == PacketKind.RESTART) {
                estimator.startFrame(nowMs, seq, rtpTimestamp, it == PacketKind.RESTART, maxIntervalMs)
            }
        }

    /**
     * Frames starting at [startMs] and every [intervalMs] after it, [count] of them, one packet each, with sequence
     * numbers from [firstSeq] and timestamps 3000 apart from [firstTimestamp].
     */
    private fun frames(startMs: Long, intervalMs: Long, count: Int, firstSeq: Int = 0, firstTimestamp: Long = 0): Long {
        var t = startMs
        for (i in 0 until count) {
            packet(t, firstSeq + i, firstTimestamp + i * 3000L)
            if (i < count - 1) t += intervalMs
        }
        return t
    }

    init {
        context("Classifying packets") {
            should("take the first packet for a new frame") {
                estimator.classify(100, 1000) shouldBe PacketKind.NEW_FRAME
            }
            context("after a frame has started") {
                packet(10_000, 100, 90_000)
                should("tell another packet of the same frame by its timestamp, whatever its sequence number") {
                    estimator.classify(101, 90_000) shouldBe PacketKind.SAME_FRAME
                    estimator.classify(99, 90_000) shouldBe PacketKind.SAME_FRAME
                }
                should("tell a newer frame by its sequence number, whatever its timestamp") {
                    estimator.classify(101, 93_000) shouldBe PacketKind.NEW_FRAME
                    // A frame sent after the current frame but displayed before it, as with frame reordering.
                    estimator.classify(101, 87_000) shouldBe PacketKind.NEW_FRAME
                }
                should("tell a straggler, an older frame's packet by sequence number, however old its timestamp") {
                    estimator.classify(99, 87_000) shouldBe PacketKind.STRAGGLER
                    estimator.classify(99, 93_000) shouldBe PacketKind.STRAGGLER
                    estimator.classify(100 - MAX_STRAGGLER_SEQUENCE_DELTA, 87_000) shouldBe PacketKind.STRAGGLER
                }
                should("tell a restart, further behind than a straggler could be") {
                    estimator.classify(100 - MAX_STRAGGLER_SEQUENCE_DELTA - 1, 87_000) shouldBe PacketKind.RESTART
                }
                should("change nothing") {
                    estimator.classify(105, 105_000)
                    estimator.classify(99, 87_000)
                    estimator.classify(100, 90_000) shouldBe PacketKind.SAME_FRAME
                    estimator.msSinceFrameStart(10_500) shouldBe 500
                }
            }
            context("across the RTP sequence number wrap") {
                should("tell a straggler from before the wrap") {
                    packet(10_000, 10, 90_000)
                    estimator.classify(65_530, 87_000) shouldBe PacketKind.STRAGGLER
                }
                should("tell a newer frame after the wrap") {
                    packet(10_000, 65_530, 90_000)
                    estimator.classify(3, 93_000) shouldBe PacketKind.NEW_FRAME
                }
            }
        }

        context("Measuring the time since the frame started") {
            should("be 0 before the first frame") {
                estimator.msSinceFrameStart(5000) shouldBe 0
            }
            should("count from the first packet of the current frame") {
                packet(1000, 0, 0)
                packet(1010, 1, 0)
                estimator.msSinceFrameStart(1500) shouldBe 500
            }
        }

        context("Learning the frame interval") {
            should("know none before two frames have started") {
                packet(1000, 0, 0)
                estimator.frameIntervalMs shouldBe 0.0
            }
            should("learn the first interval as it is") {
                frames(1000, 1000, 2)
                estimator.frameIntervalMs shouldBe 1000.0
            }
            should("learn a steady rate") {
                frames(1000, 33, 30)
                estimator.frameIntervalMs shouldBe 33.0
            }
            should("learn the rate of reordered frames, by their arrival") {
                // Frames sent in decode order, displayed in the order 0, 2, 1, 3: timestamps out of order.
                var t = 1000L
                listOf(0L, 6000L, 3000L, 9000L, 12000L, 18000L, 15000L, 21000L).forEachIndexed { seq, ts ->
                    packet(t, seq, ts)
                    t += 33
                }
                estimator.frameIntervalMs shouldBe 33.0
            }
            context("at a steady 30 frames a second") {
                val end = frames(1000, 33, 30)
                should("learn an interval up to twice the estimate at once, smoothed") {
                    packet(end + 60, 30, 30 * 3000L)
                    estimator.frameIntervalMs shouldBe 33.0 + 0.25 * (60 - 33)
                }
                should("not learn a pause longer than the most it may learn") {
                    packet(end + 5000, 30, 30 * 3000L)
                    estimator.frameIntervalMs shouldBe 33.0
                }
                should("learn a longer interval only once the two before it were at least half as long") {
                    packet(end + 1100, 30, 30 * 3000L)
                    estimator.frameIntervalMs shouldBe 33.0
                    packet(end + 2200, 31, 31 * 3000L)
                    estimator.frameIntervalMs shouldBe 33.0
                    packet(end + 3300, 32, 32 * 3000L)
                    estimator.frameIntervalMs shouldBe 33.0 + 0.25 * (1100 - 33)
                }
                should("not learn one or two pauses") {
                    packet(end + 2500, 30, 30 * 3000L)
                    packet(end + 5000, 31, 31 * 3000L)
                    estimator.frameIntervalMs shouldBe 33.0
                    packet(end + 5033, 32, 32 * 3000L)
                    estimator.frameIntervalMs shouldBe 33.0
                }
                should("learn nothing across a restart, and go on from the frame it started") {
                    packet(end + 5, 29 - MAX_STRAGGLER_SEQUENCE_DELTA - 1, 30 * 3000L) shouldBe PacketKind.RESTART
                    estimator.frameIntervalMs shouldBe 33.0
                    packet(end + 38, 29 - MAX_STRAGGLER_SEQUENCE_DELTA, 31 * 3000L) shouldBe PacketKind.NEW_FRAME
                    estimator.frameIntervalMs shouldBe 33.0
                }
                should("carry its state over to a copy") {
                    val copy = estimator.copy()
                    copy.frameIntervalMs shouldBe 33.0
                    copy.classify(30, 29 * 3000L) shouldBe PacketKind.SAME_FRAME
                    copy.classify(28, 28 * 3000L) shouldBe PacketKind.STRAGGLER
                    copy.msSinceFrameStart(end + 10) shouldBe 10
                }
            }
            should("not learn an interval longer than the most it may learn, however gradually the rate slowed") {
                var t = 1000L
                var seq = 0
                packet(t, seq, 0)
                for (interval in listOf(500L, 500L, 500L, 900L, 1600L, 2800L, 2800L)) {
                    t += interval
                    seq++
                    packet(t, seq, seq * 3000L)
                }
                val before = estimator.frameIntervalMs
                packet(t + 3500, seq + 1, (seq + 1) * 3000L)
                estimator.frameIntervalMs shouldBe before
            }
        }
    }
}
