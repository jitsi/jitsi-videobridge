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
import org.jitsi.utils.time.FakeClock
import java.time.Duration

class SyntheticSourceRtpContinuityTest : ShouldSpec() {
    init {
        context("A peer that does not start again") {
            should("keep its numbers") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock())
                continuity.timestamp(0) shouldBe 0
                continuity.packet(0, 0) shouldBe Numbers(0, 0)
                continuity.packet(1, 960) shouldBe Numbers(1, 960)
                continuity.timestamp(1920) shouldBe 1920
                // The next talk, after 5 s of silence.
                continuity.timestamp(241_920) shouldBe 241_920
                continuity.packet(2, 241_920) shouldBe Numbers(2, 241_920)
            }
            should("keep its numbers when a talk starts at the timestamp where the last one stopped") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock())
                continuity.packet(0, 0) shouldBe Numbers(0, 0)
                continuity.packet(1, 960) shouldBe Numbers(1, 960)
                continuity.timestamp(1920) shouldBe 1920
                continuity.timestamp(1920) shouldBe 1920
                continuity.packet(2, 1920) shouldBe Numbers(2, 1920)
            }
            should("keep its numbers when the chunk numbers wrap at 16 bits") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock())
                continuity.packet(65_534, 0) shouldBe Numbers(65_534, 0)
                continuity.packet(65_535, 960) shouldBe Numbers(65_535, 960)
                continuity.packet(0, 1920) shouldBe Numbers(0, 1920)
                continuity.packet(65_537, 2880) shouldBe Numbers(1, 2880)
            }
            should("wrap its timestamps at 32 bits") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock())
                continuity.packet(0, 0xFFFF_FFFFL) shouldBe Numbers(0, 0xFFFF_FFFFL)
                continuity.packet(1, 0x1_0000_03BFL) shouldBe Numbers(1, 0x3BF)
                continuity.timestamp(0x1_0000_077FL) shouldBe 0x77F
            }
        }
        context("A peer that starts again from zero") {
            should("continue the sequence numbers, and the timestamps by the time that passed") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock)
                continuity.timestamp(0)
                (0..3090).forEach { continuity.packet(it, it * 960L) }
                continuity.timestamp(3091 * 960L) shouldBe 2_967_360

                clock.elapse(Duration.ofSeconds(30))
                // 30 s at 48 kHz after the last timestamp.
                continuity.timestamp(0) shouldBe 4_407_360
                continuity.packet(0, 0) shouldBe Numbers(3091, 4_407_360)
                continuity.packet(1, 960) shouldBe Numbers(3092, 4_408_320)
                continuity.timestamp(1920) shouldBe 4_409_280
            }
            should("continue when its media comes without a sending change first") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock)
                (0..500).forEach { continuity.packet(it, it * 960L) }

                clock.elapse(Duration.ofSeconds(2))
                continuity.packet(0, 0) shouldBe Numbers(501, 500 * 960L + 96_000)
                continuity.packet(1, 960) shouldBe Numbers(502, 500 * 960L + 96_960)
            }
            should("continue again when it starts again a second time") {
                val clock = FakeClock()
                val continuity = SyntheticSourceRtpContinuity(clock)
                (0..9).forEach { continuity.packet(it, it * 960L) }
                clock.elapse(Duration.ofSeconds(1))
                (0..4).forEach { continuity.packet(it, it * 960L) }
                clock.elapse(Duration.ofSeconds(1))
                continuity.packet(0, 0) shouldBe Numbers(15, 9 * 960L + 48_000 + 4 * 960L + 48_000)
            }
            should("go on at least one frame after the last timestamp") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock())
                continuity.packet(7, 5000) shouldBe Numbers(7, 5000)
                continuity.packet(0, 0) shouldBe Numbers(8, 5960)
            }
            should("wrap the sequence numbers and the timestamps that continue") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock())
                continuity.packet(65_535, 0xFFFF_FFFFL) shouldBe Numbers(65_535, 0xFFFF_FFFFL)
                continuity.packet(100, 0) shouldBe Numbers(0, 959)
                continuity.packet(101, 960) shouldBe Numbers(1, 1919)
            }
        }
        context("A peer that starts again with timestamps ahead of the last one") {
            should("continue the sequence numbers and keep the timestamps, so they match the sending change") {
                val continuity = SyntheticSourceRtpContinuity(FakeClock())
                continuity.timestamp(0)
                (0..99).forEach { continuity.packet(it, it * 960L) }
                continuity.timestamp(96_000)

                continuity.timestamp(254_640) shouldBe 254_640
                continuity.packet(0, 254_640) shouldBe Numbers(100, 254_640)
                continuity.packet(1, 255_600) shouldBe Numbers(101, 255_600)
            }
        }
    }
}

private typealias Numbers = SyntheticSourceRtpContinuity.Numbers
