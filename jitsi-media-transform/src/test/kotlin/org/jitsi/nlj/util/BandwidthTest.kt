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

import io.kotlintest.matchers.beGreaterThan
import io.kotlintest.seconds
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

class BandwidthTest : ShouldSpec() {

    init {
        "equivalent bandwidths" {
            should("match even if created differently") {
                2.5.mbps shouldBe 2_500_000.bps
                2500.kbps shouldBe 2.5.mbps
            }
        }
        "arithmetic operations on bandwidths" {
            should("work correctly") {
                1.kbps * 10 shouldBe 10.kbps
                1.mbps + 500.kbps shouldBe 1.5.mbps
                1.mbps * .95 shouldBe 950.kbps
                1.mbps / 4 shouldBe 250.kbps
                1.mbps / 4.mbps shouldBe 0.25
            }
        }
        "printing bandwidths" {
            should("print as the most appropriate unit") {
                2_500_000.bps.toString() shouldBe "2.5 mbps"
                500_000.bps.toString() shouldBe "500 kbps"
                .001.mbps.toString() shouldBe "1 kbps"
            }
        }
        "comparing bandwidths" {
            should("work correctly") {
                2.mbps should beGreaterThan(1.mbps)
                1000.bps should beGreaterThan(999.bps)
            }
        }
        "creation from 'per'" {
            should("work correctly") {
                1.megabytes.per(1.seconds) shouldBe 8.mbps
            }
        }
    }
}
