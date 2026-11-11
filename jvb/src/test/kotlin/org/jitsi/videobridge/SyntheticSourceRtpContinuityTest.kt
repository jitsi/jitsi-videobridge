/*
 * Copyright @ 2026 - Present, 8x8, Inc.
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
package org.jitsi.videobridge

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.time.FakeClock
import java.time.Duration

class SyntheticSourceRtpContinuityTest : ShouldSpec() {
    init {
        context("A peer that does not start again") {
            should("keep its numbers") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.sendingChangeTimestamp(SSRC, 0) shouldBe 0
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(0, 0)
                continuity.nextPacket(SSRC, 1, 960) shouldBe Numbers(1, 960)
                continuity.sendingChangeTimestamp(SSRC, 1920) shouldBe 1920
                // The next talk, after 5 s of silence.
                continuity.sendingChangeTimestamp(SSRC, 241_920) shouldBe 241_920
                continuity.nextPacket(SSRC, 2, 241_920) shouldBe Numbers(2, 241_920)
            }
            should("keep its numbers when a talk starts at the timestamp where the last one stopped") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(0, 0)
                continuity.nextPacket(SSRC, 1, 960) shouldBe Numbers(1, 960)
                continuity.sendingChangeTimestamp(SSRC, 1920) shouldBe 1920
                continuity.sendingChangeTimestamp(SSRC, 1920) shouldBe 1920
                continuity.nextPacket(SSRC, 2, 1920) shouldBe Numbers(2, 1920)
            }
            should("keep its numbers when the chunk numbers wrap at 16 bits") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.nextPacket(SSRC, 65_534, 0) shouldBe Numbers(65_534, 0)
                continuity.nextPacket(SSRC, 65_535, 960) shouldBe Numbers(65_535, 960)
                continuity.nextPacket(SSRC, 0, 1920) shouldBe Numbers(0, 1920)
                continuity.nextPacket(SSRC, 65_537, 2880) shouldBe Numbers(1, 2880)
            }
            should("wrap its timestamps at 32 bits") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.nextPacket(SSRC, 0, 0xFFFF_FFFFL) shouldBe Numbers(0, 0xFFFF_FFFFL)
                continuity.nextPacket(SSRC, 1, 0x1_0000_03BFL) shouldBe Numbers(1, 0x3BF)
                continuity.sendingChangeTimestamp(SSRC, 0x1_0000_077FL) shouldBe 0x77F
            }
        }
        context("A peer that starts again from zero") {
            should("continue the sequence numbers, and the timestamps by the time that passed") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock, LoggerImpl("test"))
                continuity.sendingChangeTimestamp(SSRC, 0)
                (0..3090).forEach { continuity.nextPacket(SSRC, it, it * 960L) }
                continuity.sendingChangeTimestamp(SSRC, 3091 * 960L) shouldBe 2_967_360

                clock.elapse(Duration.ofSeconds(30))
                // 30 s at 48 kHz after the last timestamp.
                continuity.sendingChangeTimestamp(SSRC, 0) shouldBe 4_407_360
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(3091, 4_407_360)
                continuity.nextPacket(SSRC, 1, 960) shouldBe Numbers(3092, 4_408_320)
                continuity.sendingChangeTimestamp(SSRC, 1920) shouldBe 4_409_280
            }
            should("continue when its media comes without a sending change first") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock, LoggerImpl("test"))
                (0..500).forEach { continuity.nextPacket(SSRC, it, it * 960L) }

                clock.elapse(Duration.ofSeconds(2))
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(501, 500 * 960L + 96_000)
                continuity.nextPacket(SSRC, 1, 960) shouldBe Numbers(502, 500 * 960L + 96_960)
            }
            should("continue again when it starts again a second time") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock, LoggerImpl("test"))
                (0..9).forEach { continuity.nextPacket(SSRC, it, it * 960L) }
                clock.elapse(Duration.ofSeconds(1))
                (0..4).forEach { continuity.nextPacket(SSRC, it, it * 960L) }
                clock.elapse(Duration.ofSeconds(1))
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(15, 9 * 960L + 48_000 + 4 * 960L + 48_000)
            }
            should("go on at least one frame after the last timestamp") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.nextPacket(SSRC, 7, 5000) shouldBe Numbers(7, 5000)
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(8, 5960)
            }
            should("wrap the sequence numbers and the timestamps that continue") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.nextPacket(SSRC, 65_535, 0xFFFF_FFFFL) shouldBe Numbers(65_535, 0xFFFF_FFFFL)
                continuity.nextPacket(SSRC, 100, 0) shouldBe Numbers(0, 959)
                continuity.nextPacket(SSRC, 101, 960) shouldBe Numbers(1, 1919)
            }
        }
        context("A peer that starts again with timestamps ahead of the last one") {
            should("continue the sequence numbers and keep the timestamps, so they match the sending change") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.sendingChangeTimestamp(SSRC, 0)
                (0..99).forEach { continuity.nextPacket(SSRC, it, it * 960L) }
                continuity.sendingChangeTimestamp(SSRC, 96_000)

                continuity.sendingChangeTimestamp(SSRC, 254_640) shouldBe 254_640
                continuity.nextPacket(SSRC, 0, 254_640) shouldBe Numbers(100, 254_640)
                continuity.nextPacket(SSRC, 1, 255_600) shouldBe Numbers(101, 255_600)
            }
        }
        context("A producer whose connection opens again") {
            should("continue the numbers when its timestamps are later and its chunk numbers are 2^15 or more behind") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock, LoggerImpl("test"))
                continuity.nextPacket(SSRC, 40_000, 38_400_000) shouldBe Numbers(40_000, 38_400_000)

                clock.elapse(Duration.ofSeconds(2))
                continuity.producerStarted(listOf(SSRC))
                continuity.nextPacket(SSRC, 0, 38_400_960) shouldBe Numbers(40_001, 38_496_000)
                continuity.nextPacket(SSRC, 1, 38_401_920) shouldBe Numbers(40_002, 38_496_960)
            }
            should("move a new start at the timestamp of the last packet to a later timestamp") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock, LoggerImpl("test"))
                continuity.sendingChangeTimestamp(SSRC, 0) shouldBe 0
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(0, 0)

                clock.elapse(Duration.ofSeconds(2))
                continuity.producerStarted(listOf(SSRC))
                // The sending change and the media of the new start stay on one timeline.
                continuity.sendingChangeTimestamp(SSRC, 0) shouldBe 96_000
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(1, 96_000)
            }
            should("keep the numbers of a source that sent nothing yet") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.producerStarted(listOf(SSRC))
                continuity.sendingChangeTimestamp(SSRC, 4800) shouldBe 4800
                continuity.nextPacket(SSRC, 5, 4800) shouldBe Numbers(5, 4800)
            }
            should("not change the numbers of a producer that kept its numbering") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock, LoggerImpl("test"))
                (0..2).forEach { continuity.nextPacket(SSRC, it, it * 960L) }

                clock.elapse(Duration.ofMillis(20))
                continuity.producerStarted(listOf(SSRC))
                continuity.nextPacket(SSRC, 3, 2880) shouldBe Numbers(3, 2880)
                continuity.nextPacket(SSRC, 4, 3840) shouldBe Numbers(4, 3840)
            }
            should("start again once, when the numbers that go back follow the opening") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock, LoggerImpl("test"))
                (0..9).forEach { continuity.nextPacket(SSRC, it, it * 960L) }

                clock.elapse(Duration.ofSeconds(1))
                continuity.producerStarted(listOf(SSRC))
                continuity.sendingChangeTimestamp(SSRC, 0) shouldBe 9 * 960L + 48_000
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(10, 9 * 960L + 48_000)
                continuity.nextPacket(SSRC, 1, 960) shouldBe Numbers(11, 9 * 960L + 48_960)
            }
        }
        context("Several sources") {
            should("number each SSRC on its own") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.nextPacket(SSRC, 100, 96_000) shouldBe Numbers(100, 96_000)
                continuity.nextPacket(OTHER_SSRC, 7, 0) shouldBe Numbers(7, 0)
                continuity.nextPacket(SSRC, 101, 96_960) shouldBe Numbers(101, 96_960)
                // Behind the numbers of the other source, and still not a new start of this one.
                continuity.nextPacket(OTHER_SSRC, 8, 960) shouldBe Numbers(8, 960)
                continuity.sendingChangeTimestamp(OTHER_SSRC, 1920) shouldBe 1920
            }
            should("start again only the sources of the producer that connected") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock, LoggerImpl("test"))
                (0..4).forEach {
                    continuity.nextPacket(SSRC, it, it * 960L)
                    continuity.nextPacket(OTHER_SSRC, it, it * 960L)
                }

                clock.elapse(Duration.ofSeconds(1))
                continuity.producerStarted(listOf(SSRC))
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(5, 4 * 960L + 48_000)
                continuity.nextPacket(OTHER_SSRC, 5, 4800) shouldBe Numbers(5, 4800)
            }
            should("ignore the opening of a connection for an SSRC it does not know") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.producerStarted(listOf(OTHER_SSRC))
                continuity.nextPacket(SSRC, 3, 2880) shouldBe Numbers(3, 2880)
            }
        }
        context("A producer that starts again on the same connection, which nothing reports") {
            // These pin the limits of finding a new start from the numbers alone (see the KDoc of the class).
            should("not be found when its timestamps are later and its chunk numbers are 2^15 or more behind") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock(), LoggerImpl("test"))
                continuity.nextPacket(SSRC, 40_000, 38_400_000) shouldBe Numbers(40_000, 38_400_000)
                continuity.nextPacket(SSRC, 0, 38_400_960) shouldBe Numbers(0, 38_400_960)
            }
            should("continue the sequence numbers but repeat the timestamp, when it starts at the last timestamp") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock, LoggerImpl("test"))
                continuity.sendingChangeTimestamp(SSRC, 0) shouldBe 0
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(0, 0)

                clock.elapse(Duration.ofSeconds(2))
                continuity.sendingChangeTimestamp(SSRC, 0) shouldBe 0
                continuity.nextPacket(SSRC, 0, 0) shouldBe Numbers(1, 0)
            }
        }
    }
}

private typealias Numbers = SyntheticSourceRtpContinuity.Numbers

private const val SSRC = 1234L
private const val OTHER_SSRC = 5678L
