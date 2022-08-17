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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class DataSizeTest : ShouldSpec() {

    init {
        context("equivalent data sizes") {
            should("match even if created differently") {
                1000.bytes shouldBe 1.kilobytes
                1.megabytes shouldBe 8_000_000.bits
            }
        }
        context("arithmetic operations") {
            should("work correctly") {
                1.kilobytes + 1.kilobytes shouldBe 2.kilobytes
                1.megabytes - 500.kilobytes shouldBe 500.kilobytes
                1.bytes * 10 shouldBe 10.bytes
            }
        }
        context("printing data sizes") {
            should("print as the most appropriate unit") {
                8_000_000.bits.toString() shouldBe "1 MB"
                1000.bytes.toString() shouldBe "1 KB"
                1551.bytes.toString() shouldBe "1.55 KB"
                1551.kilobytes.toString() shouldBe "1.55 MB"
                32.bits.toString() shouldBe "4 B"
                36.bits.toString() shouldBe "4.5 B"
                4.bits.toString() shouldBe "4 bits"
            }
        }
        context("comparing data sizes") {
            should("work correctly") {
                2.megabytes shouldBeGreaterThan 1.megabytes
                1000.bits shouldBeGreaterThan 999.bits
            }
        }
    }
}
