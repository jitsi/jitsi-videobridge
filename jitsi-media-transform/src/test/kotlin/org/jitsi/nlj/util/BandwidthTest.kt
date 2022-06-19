/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.util

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.utils.secs

class BandwidthTest : ShouldSpec() {

    init {
        context("equivalent bandwidths") {
            should("match even if created differently") {
                2.5.mbps shouldBe 2_500_000.bps
                2500.kbps shouldBe 2.5.mbps
            }
        }
        context("arithmetic operations on bandwidths") {
            should("work correctly") {
                1.kbps * 10 shouldBe 10.kbps
                1.mbps + 500.kbps shouldBe 1.5.mbps
                1.mbps * .95 shouldBe 950.kbps
                1.mbps / 4 shouldBe 250.kbps
                1.mbps / 4.mbps shouldBe 0.25
            }
        }
        context("operation-assign operations on bandwidths") {
            should("work correctly") {
                var b = 1.mbps
                b += 0.5.mbps
                b shouldBe 1.5.mbps
                b.kbps shouldBe 1500.0
                b.bps shouldBe 1_500_000.0

                b = 1.mbps
                b -= 0.5.mbps
                b shouldBe 0.5.mbps
                b.kbps shouldBe 500.0
                b.bps shouldBe 500_000.0

                b = 1.mbps
                b *= 2.0
                b shouldBe 2.0.mbps
                b.kbps shouldBe 2_000.0
                b.bps shouldBe 2_000_000.0

                b = 1.mbps
                b /= 2.0
                b shouldBe 0.5.mbps
                b.kbps shouldBe 500.0
                b.bps shouldBe 500_000.0
            }
        }
        context("printing bandwidths") {
            should("print as the most appropriate unit") {
                2_500_000.bps.toString() shouldBe "2.5 mbps"
                500_000.bps.toString() shouldBe "500 kbps"
                .001.mbps.toString() shouldBe "1 kbps"
            }
        }
        context("comparing bandwidths") {
            context("with integral bps") {
                should("work correctly") {
                    2.mbps shouldBeGreaterThan 1.mbps
                    1000.bps shouldBeGreaterThan 999.bps
                }
            }
            context("with fractional bps") {
                should("work correctly") {
                    0.2.bps shouldNotBe 0.bps
                    0.2.bps shouldBeGreaterThan 0.1.bps
                }
            }
        }
        context("creation from 'per'") {
            should("work correctly") {
                1.megabytes.per(1.secs) shouldBe 8.mbps
                2.bits.per(5.secs) shouldBe 0.4.bps
            }
        }
        context("creation from a string") {
            should("work correctly") {
                Bandwidth.fromString("10mbps") shouldBe 10.mbps
                Bandwidth.fromString("10bps") shouldBe 10.bps
                Bandwidth.fromString("10kbps") shouldBe 10.kbps
                Bandwidth.fromString("10 kbps") shouldBe 10.kbps
                Bandwidth.fromString("10 KbPs") shouldBe 10.kbps
            }
            should("throw on an invalid unit") {
                shouldThrow<IllegalArgumentException> { Bandwidth.fromString("10foos") }
            }
        }
    }
}
