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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

class EncodingLivenessTrackerTest : ShouldSpec() {
    /** The camera timeout, as a source sets it. */
    private val timeoutMs = 1000L

    init {
        context("Signaled by the sender") {
            // Each test makes its own tracker: tests in a context share the context's objects, in order.
            fun tracker() = EncodingLivenessTracker(timeoutMs)
            should("be live once signaled as sent, as if a packet had arrived") {
                val tracker = tracker()
                tracker.onSignaled(true, 1000)
                tracker.isLive(1000 + timeoutMs) shouldBe true
                tracker.isLive(1000 + timeoutMs + 1) shouldBe false
            }
            should("not be live once signaled as not sent, whatever arrived before") {
                val tracker = tracker()
                repeat(30) { tracker.onPacketReceived(1000 + it * 33L, it, it * 3000L) }
                tracker.isLive(2000) shouldBe true
                tracker.onSignaled(false, 2000)
                tracker.isLive(2000) shouldBe false
                tracker.signaledOff shouldBe true
            }
            should("be live again on a signal that it is sent, or on a packet") {
                val tracker = tracker()
                tracker.onSignaled(false, 1000)
                tracker.onSignaled(true, 2000)
                tracker.isLive(2000) shouldBe true
                tracker.onSignaled(false, 3000)
                tracker.onPacketReceived(4000, 1, 90_000L)
                tracker.isLive(4000) shouldBe true
                tracker.signaledOff shouldBe false
            }
            should("be outlasted by any live encoding while signaled as not sent") {
                val tracker = tracker()
                val other = EncodingLivenessTracker(timeoutMs)
                tracker.onPacketReceived(1000, 0, 0L)
                // The other's run began after this tracker's last packet, and within its allowance: not outlasted.
                other.onPacketReceived(1500, 0, 0L)
                other.hasOutlasted(tracker, 1500) shouldBe false
                tracker.onSignaled(false, 1500)
                other.hasOutlasted(tracker, 1500) shouldBe true
            }
            should("carry the signal over to a copy") {
                val tracker = tracker()
                tracker.onSignaled(false, 1000)
                tracker.copy().isLive(1000) shouldBe false
                tracker.onSignaled(true, 2000)
                tracker.copy().isLive(2000) shouldBe true
            }
        }
        context("A tracker which has seen no packet") {
            val tracker = EncodingLivenessTracker(timeoutMs)
            should("not consider its encoding live") {
                tracker.lastPacketReceivedMs shouldBe EncodingLivenessTracker.NEVER_RECEIVED
                tracker.isLive(0) shouldBe false
            }
        }
        context("An encoding sending one frame a second") {
            val slow = EncodingLivenessTracker(timeoutMs)
            // Three packets per frame, frames a second apart, within the camera timeout.
            for (frame in 0..3) {
                repeat(3) { slow.onPacketReceived(10000 + frame * 1000L, frame * 3 + it, frame * 90000L) }
            }
            should("measure the frame interval") {
                slow.frameIntervalMs shouldBe 1000.0
            }
            should("stay live for a few frame intervals rather than the camera timeout") {
                slow.isLive(13000 + 3999) shouldBe true
                slow.isLive(13000 + 4001) shouldBe false
            }
            should("stay live for no more than a few timeouts, however long its frame interval") {
                // The slowest interval still learned, three timeouts: allowed two of them, not four.
                val slowest = EncodingLivenessTracker(timeoutMs)
                repeat(4) { frame -> slowest.onPacketReceived(10000 + frame * 3000L, frame, frame * 270_000L) }
                slowest.frameIntervalMs shouldBe 3000.0
                slowest.isLive(19000 + 5999) shouldBe true
                slowest.isLive(19000 + 6001) shouldBe false
            }
            should("carry the frame interval over to a copy") {
                slow.copy().frameIntervalMs shouldBe 1000.0
            }
            should("report its state for debugging") {
                val state = slow.debugState(13000 + 3999)
                state["live"].asBoolean() shouldBe true
                state["timeout_ms"].asLong() shouldBe timeoutMs
                state["last_packet_received_ms"].asLong() shouldBe 13000
                state["flowing_since_ms"].asLong() shouldBe 10000
                state["frame_interval_ms"].asDouble() shouldBe 1000.0
                state["frame_rate"].asDouble() shouldBe 1.0
                slow.debugState(13000 + 4001)["live"].asBoolean() shouldBe false
            }
        }
        context("An encoding at 30 frames a second") {
            val fast = EncodingLivenessTracker(timeoutMs)
            for (frame in 0..29) {
                fast.onPacketReceived(20000 + frame * 33L, frame, frame * 3000L)
            }
            should("measure the frame interval") {
                fast.frameIntervalMs shouldBe 33.0
            }
            should("not learn from a gap across a pause, since the sender may have turned it off") {
                fast.onPacketReceived(20000 + 29 * 33L + 30000, 30, 30 * 3000L)
                fast.frameIntervalMs shouldBe 33.0
            }
            should("tell a packet of an older frame from one of the current frame, for nodes reading its extensions") {
                // The current frame is 30, from the test above.
                fast.isOfOlderFrame(30, 30 * 3000L) shouldBe false
                fast.isOfOlderFrame(29, 30 * 3000L) shouldBe false
                fast.isOfOlderFrame(29, 29 * 3000L) shouldBe true
                fast.isOfOlderFrame(31, 31 * 3000L) shouldBe false
            }
            should("not count a reordered or retransmitted packet of an older frame for anything") {
                val lastPacket = fast.lastPacketReceivedMs
                fast.onPacketReceived(20000 + 30 * 33L, 15, 15 * 3000L)
                fast.frameIntervalMs shouldBe 33.0
                fast.lastPacketReceivedMs shouldBe lastPacket
            }
            // The tests in this context run in order and share the encoding's state. This test comes last, and starts
            // after every time the tests above used.
            should("learn an abrupt drop to a frame rate below the timeout's within a few frames") {
                var t = 60000L
                for (frame in 50..60) {
                    t += 1100
                    fast.onPacketReceived(t, frame, frame * 3000L)
                }
                fast.frameIntervalMs shouldBeGreaterThan 1000.0
                fast.isLive(t + 1100) shouldBe true
            }
            should("not learn gaps beyond a few timeouts, however slow it has become") {
                var t = 100000L
                for (frame in 70..80) {
                    t += 3500
                    fast.onPacketReceived(t, frame, frame * 3000L)
                }
                fast.frameIntervalMs shouldBeLessThan 1100.0
            }
            should("not learn from a pause or two of a few timeouts") {
                // Back to a steady 30 fps first.
                var t = 200000L
                for (frame in 90..150) {
                    t += 33
                    fast.onPacketReceived(t, frame, frame * 3000L)
                }
                val before = fast.frameIntervalMs
                t += 2500
                fast.onPacketReceived(t, 151, 151 * 3000L)
                fast.frameIntervalMs shouldBe before
                t += 2500
                fast.onPacketReceived(t, 152, 152 * 3000L)
                fast.frameIntervalMs shouldBe before
                t += 33
                fast.onPacketReceived(t, 153, 153 * 3000L)
                fast.frameIntervalMs shouldBeLessThan 100.0
            }
            should("not be thrown by a packet with a corrupt timestamp") {
                var t = 300000L
                fast.onPacketReceived(t, 160, 160 * 3000L)
                // One packet with a corrupt timestamp, half the timestamp space ahead. Frames are told by sequence
                // number, so it is one frame, and the stream's own packets continue as newer frames.
                fast.onPacketReceived(t + 33, 161, 160 * 3000L + 0x7000_0000L)
                for (frame in 162..190) {
                    t += 33
                    fast.onPacketReceived(t, frame, frame * 3000L)
                    fast.isLive(t) shouldBe true
                }
                fast.lastPacketReceivedMs shouldBe t
                fast.frameIntervalMs shouldBeLessThan 40.0
            }
            should("not be revived by a late retransmission of a frame from before it stopped") {
                var t = 400000L
                fast.onPacketReceived(t, 200, 200 * 3000L)
                fast.onPacketReceived(t + 5, 201, 200 * 3000L)
                t += 1500
                fast.isLive(t) shouldBe false
                // An older sequence number: a retransmission, whatever its timestamp.
                fast.onPacketReceived(t, 199, 199 * 3000L)
                fast.isLive(t) shouldBe false
                // Even a packet of the last frame itself: a lost tail of it, recovered late.
                fast.onPacketReceived(t, 200, 200 * 3000L)
                fast.isLive(t) shouldBe false
                t += 33
                fast.onPacketReceived(t, 202, 202 * 3000L)
                fast.isLive(t) shouldBe true
                fast.lastPacketReceivedMs shouldBe t
            }
            should("tell an encoding which outlasted another from the whole source resuming after a stall") {
                val other = EncodingLivenessTracker(timeoutMs)
                // Both flowing at 30 fps.
                var t = 500000L
                other.hasOutlasted(fast, t) shouldBe false
                for (frame in 0..29) {
                    t += 33
                    fast.onPacketReceived(t, 300 + frame, 300 * 3000L + frame * 3000L)
                    other.onPacketReceived(t, frame, 1_000_000L + frame * 3000L)
                }
                // The sender turns the fast encoding off; the other keeps flowing.
                for (frame in 30..75) {
                    t += 33
                    other.onPacketReceived(t, frame, 1_000_000L + frame * 3000L)
                }
                fast.isLive(t) shouldBe false
                other.hasOutlasted(fast, t) shouldBe true
                // The whole source stalls, then the other resumes first, staggered by several frames.
                t = 600000L
                for (frame in 80..109) {
                    t += 33
                    fast.onPacketReceived(t, 300 + frame, 300 * 3000L + frame * 3000L)
                    other.onPacketReceived(t, frame, 1_000_000L + frame * 3000L)
                }
                t += 2000
                for (frame in 110..113) {
                    t += 33
                    other.onPacketReceived(t, frame, 1_000_000L + frame * 3000L)
                    fast.isLive(t) shouldBe false
                    other.hasOutlasted(fast, t) shouldBe false
                }
                // Until the fast encoding has been given as long to resume as it may go without a packet.
                for (frame in 114..146) {
                    t += 33
                    other.onPacketReceived(t, frame, 1_000_000L + frame * 3000L)
                }
                other.hasOutlasted(fast, t) shouldBe true
            }
            should("count frames by sequence number, so that reordered frames are frames in the order sent") {
                var t = 700000L
                for (frame in 500..529) {
                    t += 33
                    fast.onPacketReceived(t, frame, frame * 3000L)
                }
                // Frames sent in decode order but displayed in another: their timestamps go back and forth.
                for ((i, displayOrder) in listOf(0, 2, 1, 3, 4, 6, 5, 7).withIndex()) {
                    t += 33
                    fast.onPacketReceived(t, 530 + i, (530 + displayOrder) * 3000L)
                    fast.lastPacketReceivedMs shouldBe t
                    fast.isLive(t) shouldBe true
                }
                fast.frameIntervalMs shouldBeLessThan 40.0
            }
            should("pick the stream up again at once after its sequence numbers restart far behind") {
                var t = 750000L
                for (frame in 600..629) {
                    t += 33
                    fast.onPacketReceived(t, frame, frame * 3000L)
                }
                val interval = fast.frameIntervalMs
                // The sender restarts its sequence numbers further behind than a straggler could be.
                val seq = (629 - FrameIntervalEstimator.MAX_STRAGGLER_SEQUENCE_DELTA - 1) and 0xFFFF
                t += 33
                fast.onPacketReceived(t, seq, 630 * 3000L)
                fast.lastPacketReceivedMs shouldBe t
                fast.isLive(t) shouldBe true
                // The interval across the restart is not learned.
                fast.frameIntervalMs shouldBe interval
            }
            should("pick the stream up again at once after its sequence numbers restart a little behind, once quiet") {
                var t = 740000L
                for (frame in 640..649) {
                    t += 33
                    fast.onPacketReceived(t, frame, frame * 3000L)
                }
                // The encoding stops, then its sequence numbers restart just behind the old sequence numbers, with
                // newer timestamps: not retransmissions, which would carry older timestamps.
                t += 1500
                fast.isLive(t) shouldBe false
                fast.onPacketReceived(t, 649 - 20, 700 * 3000L)
                fast.isLive(t) shouldBe true
                fast.lastPacketReceivedMs shouldBe t
                // A retransmission of an old frame is still ignored.
                val last = fast.lastPacketReceivedMs
                fast.onPacketReceived(t + 34, 649 - 21, 640 * 3000L)
                fast.lastPacketReceivedMs shouldBe last
            }
            should("not mistake a late retransmission of a reordered frame for a restart") {
                var t = 745000L
                // Frames sent in decode order, with timestamps in display order 0, 2, 3, 1: the frame sent last is
                // displayed before the two sent just before it, so the current frame's timestamp is older than theirs.
                for ((i, displayOrder) in listOf(0, 2, 3, 1).withIndex()) {
                    t += 33
                    fast.onPacketReceived(t, 660 + i, (800 + displayOrder) * 3000L)
                }
                // The encoding stops, and a retransmission of the frame sent third arrives. Its timestamp is newer
                // than the current frame's, but by two frames, less than the liveness allowance: a retransmission,
                // not a restart, and it revives nothing.
                t += 1500
                fast.isLive(t) shouldBe false
                fast.onPacketReceived(t, 662, 803 * 3000L)
                fast.isLive(t) shouldBe false
                // A packet whose sequence number is just behind the old ones but whose timestamp is ahead by more
                // than the allowance is a restart, and the encoding is live from it.
                t += 33
                fast.onPacketReceived(t, 640, (801 + 45) * 3000L)
                fast.isLive(t) shouldBe true
            }
            should("resume from any packet after a long outage, even if it appears older") {
                var t = 760000L
                for (i in 0 until 4) {
                    t += 33
                    fast.onPacketReceived(t, 700 + i, (900 + i) * 3000L)
                }
                // After a long outage the sender's sequence numbers and timestamps may have wrapped to appear older.
                // Nothing can be a retransmission that late, so the packet resumes the encoding.
                t += EncodingLivenessTracker.RESTART_QUIET_MS + 1
                fast.isLive(t) shouldBe false
                fast.onPacketReceived(t, 703 - 100, (903 - 100) * 3000L)
                fast.isLive(t) shouldBe true
            }
            should("pick the stream up again once its sequence numbers pass the one seen, after going back a little") {
                var t = 760000L
                for (frame in 650..679) {
                    t += 33
                    fast.onPacketReceived(t, frame, frame * 3000L)
                }
                // The sender restarts its sequence numbers a little behind: what a straggler could be.
                val base = 679 - 28
                for (i in 0..27) {
                    t += 33
                    fast.onPacketReceived(t, base + i, (700 + i) * 3000L)
                }
                // Ignored as stragglers until they pass the highest sequence number seen, which at 30 fps takes just
                // the timeout...
                fast.lastPacketReceivedMs shouldBe t - 28 * 33
                fast.isLive(t) shouldBe true
                for (i in 28..32) {
                    t += 33
                    fast.onPacketReceived(t, base + i, (700 + i) * 3000L)
                }
                // ...after which the stream is picked up.
                fast.lastPacketReceivedMs shouldBe t
                fast.isLive(t) shouldBe true
            }
            should("tell a slow encoding which skipped frames in a stall from one which flowed on") {
                val slow = EncodingLivenessTracker(timeoutMs)
                var t = 800000L
                // The slow encoding at one frame a second, allowed a few seconds without a frame; the fast encoding at
                // 30 fps.
                for (frame in 0..299) {
                    t += 33
                    fast.onPacketReceived(t, 600 + frame, 600 * 3000L + frame * 3000L)
                    if (frame % 30 == 0) {
                        slow.onPacketReceived(t, frame, 2_000_000L + frame * 3000L)
                    }
                }
                slow.frameIntervalMs shouldBeGreaterThan 500.0
                // The whole source stalls for less than the slow encoding's allowance, and the slow encoding resumes
                // first.
                t += 2500
                slow.onPacketReceived(t, 300, 2_000_000L + 300 * 3000L)
                fast.isLive(t) shouldBe false
                slow.hasOutlasted(fast, t) shouldBe false
                t += 1000
                slow.onPacketReceived(t, 330, 2_000_000L + 330 * 3000L)
                slow.hasOutlasted(fast, t) shouldBe false
                // Until the fast encoding has been given as long to resume as it may go without a packet.
                t += 1000
                slow.onPacketReceived(t, 360, 2_000_000L + 360 * 3000L)
                slow.hasOutlasted(fast, t) shouldBe true
            }
            should("not end a run of frames at a few frames dropped by the encoder") {
                var t = 900000L
                for (frame in 1000..1029) {
                    t += 33
                    fast.onPacketReceived(t, frame, frame * 3000L)
                }
                val since = fast.flowingSinceMs
                t += 300
                fast.onPacketReceived(t, 1030, 1030 * 3000L)
                fast.flowingSinceMs shouldBe since
                t += 700
                fast.onPacketReceived(t, 1031, 1031 * 3000L)
                fast.flowingSinceMs shouldBe t
            }
            should("not learn an interval beyond a few timeouts however gradually the rate slowed to it") {
                var t = 1000000L
                var frame = 1100
                // Steady at first, then each interval a little under twice the interval known.
                for (interval in listOf(500L, 500L, 500L, 900L, 1600L, 2800L)) {
                    t += interval
                    frame++
                    fast.onPacketReceived(t, frame, frame * 3000L)
                }
                val before = fast.frameIntervalMs
                before shouldBeLessThan 3000.0
                t += 3500
                frame++
                fast.onPacketReceived(t, frame, frame * 3000L)
                fast.frameIntervalMs shouldBe before
            }
        }
    }
}
