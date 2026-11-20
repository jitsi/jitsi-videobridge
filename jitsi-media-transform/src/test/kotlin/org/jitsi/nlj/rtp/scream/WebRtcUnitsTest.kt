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
import io.kotest.matchers.shouldBe
import org.jitsi.nlj.util.bps
import org.jitsi.nlj.util.bytes
import org.jitsi.utils.MAX_DURATION
import org.jitsi.utils.MIN_DURATION
import org.jitsi.utils.NEVER
import org.jitsi.utils.micros
import org.jitsi.utils.ms
import org.jitsi.utils.secs
import java.time.Instant

/** Jitsi local: unit tests for the arithmetic which rounds as the unit types of WebRTC do. */
class WebRtcUnitsTest : FreeSpec() {
    init {
        "llround rounds a half away from zero" {
            llround(0.0) shouldBe 0
            llround(0.49999999999999994) shouldBe 0
            llround(0.5) shouldBe 1
            llround(1.5) shouldBe 2
            llround(2.5) shouldBe 3
            llround(2.4999) shouldBe 2
            llround(-0.5) shouldBe -1
            llround(-1.5) shouldBe -2
            llround(-2.5) shouldBe -3
            llround(-2.4999) shouldBe -2
            llround(1e15 + 0.5) shouldBe 1_000_000_000_000_001
        }

        "a duration scales to the nearest microsecond" {
            3.micros.scaledBy(0.5) shouldBe 2.micros
            (-3).micros.scaledBy(0.5) shouldBe (-2).micros
            25.ms.scaledBy(1.0 / 8.0) shouldBe 3125.micros
            10.ms.scaledBy(1.0 / 3.0) shouldBe 3333.micros
            (-10).ms.scaledBy(1.0 / 3.0) shouldBe (-3333).micros
            microsToDuration(-1) shouldBe (-1).micros
            microsToDuration(1_500_000) shouldBe 1500.ms
        }

        "the ratio of durations" {
            10.ms.ratioTo(25.ms) shouldBe 0.4
            MAX_DURATION.ratioTo(25.ms) shouldBe Double.POSITIVE_INFINITY
            MIN_DURATION.ratioTo(25.ms) shouldBe Double.NEGATIVE_INFINITY
        }

        "the time between timestamps which can be infinite" {
            timeBetween(Instant.ofEpochMilli(100), Instant.ofEpochMilli(250)) shouldBe 150.ms
            timeBetween(Instant.ofEpochMilli(250), Instant.ofEpochMilli(100)) shouldBe (-150).ms
            timeBetween(NEVER, Instant.ofEpochMilli(100)) shouldBe MAX_DURATION
            timeBetween(Instant.ofEpochMilli(100), NEVER) shouldBe MIN_DURATION
            timeBetween(Instant.ofEpochMilli(100), Instant.MAX) shouldBe MAX_DURATION
            NEVER.plusFinite(1.secs) shouldBe NEVER
            Instant.ofEpochMilli(100).plusFinite(1.secs) shouldBe Instant.ofEpochMilli(1100)
        }

        "a data size scales to the nearest byte" {
            1001.bytes.scaledBy(0.5) shouldBe 501.bytes
            1000.bytes.scaledBy(1.1) shouldBe 1100.bytes
            3.bytes.scaledBy(0.5) shouldBe 2.bytes
            1000.bytes.ratioTo(1280.bytes) shouldBe 0.78125
        }

        "a rate scales to the nearest bit per second" {
            3.bps.scaledBy(0.5) shouldBe 2.bps
            300_000.bps.scaledBy(1.1) shouldBe 330_000.bps
        }

        "data sent at a rate rounds to the nearest byte" {
            // 12500 bps for 40 ms is 62.5 bytes.
            dataSentAt(12_500.bps, 40.ms) shouldBe 63.bytes
            dataSentAt(300_000.bps, 25.ms) shouldBe 938.bytes
            dataSentAt(1_000_000.bps, 1.secs) shouldBe 125_000.bytes
        }

        "the rate of data over a time truncates" {
            rateOf(1000.bytes, 25.ms) shouldBe 320_000.bps
            rateOf(1000.bytes, 30.ms) shouldBe 266_666.bps
        }

        "the time to send data at a rate truncates to a microsecond" {
            timeToSend(1000.bytes, 320_000.bps) shouldBe 25.ms
            timeToSend(1000.bytes, 300_000.bps) shouldBe 26_666.micros
        }
    }
}
