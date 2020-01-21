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

package org.jitsi.rtp.util

import io.kotlintest.data.forall
import io.kotlintest.matchers.collections.shouldContainInOrder
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import io.kotlintest.tables.row

class RtpUtilsTest : ShouldSpec() {

    init {
        "getSequenceNumberDelta" {
            should("work correctly") {
                forall(
                    row(1, 10, -9),
                    row(10, 1, 9),
                    row(1, 65530, 7),
                    row(65530, 1, -7),
                    row(1234, 1234, 0)
                ) { a, b, expected ->
                    RtpUtils.getSequenceNumberDelta(a, b) shouldBe expected
                }
            }
        }
        "applySequenceNumberDelta" {
            should("work correctly") {
                forall(
                        row(10, -9, 1),
                        row(1, 9, 10),
                        row(65530, 7, 1),
                        row(1, -7, 65530),
                        row(1234, 0, 1234)
                ) { start, delta, expected ->
                    RtpUtils.applySequenceNumberDelta(start, delta) shouldBe expected
                }
            }
        }
        "isNewerThan" {
            should("work correctly") {
                forall(
                    row(2, 1, true),
                    row(2, 65530, true),
                    row(2, 4, false)
                ) { a, b, expected ->
                    a isNewerThan b shouldBe expected
                }
            }
        }
        "applyTimestampDelta" {
            should("work correctly") {
                forall(
                    row(10L, -9L, 1L),
                    row(1L, 9L, 10L),
                    row(0xffff_fff0L, 0x11L, 1L),
                    row(1L, -0x11L, 0xffff_fff0L),
                    row(1234L, 0L, 1234L)
                ) { start, delta, expected ->
                    RtpUtils.applyTimestampDelta(start, delta) shouldBe expected
                }
            }
        }
        "isOlderThan" {
            should("work correctly") {
                forall(
                    row(2, 1, false),
                    row(2, 65530, false),
                    row(2, 4, true)
                ) { a, b, expected ->
                    a isOlderThan b shouldBe expected
                }
            }
        }
        "rolledOverTo" {
            should("return true when a rollover has taken place") {
                65535 rolledOverTo 1 shouldBe true
                65000 rolledOverTo 200 shouldBe true
            }
            should("return false when a rollover has not occurred") {
                0 rolledOverTo 65535 shouldBe false
            }
        }
        "isNextAfter" {
            should("return true for sequential packets") {
                2 isNextAfter 1 shouldBe true
                0 isNextAfter 65535 shouldBe true
            }
            should("return false for non-sequential packets") {
                2 isNextAfter 0 shouldBe false
                1 isNextAfter 2 shouldBe false
                1 isNextAfter 65535 shouldBe false
            }
        }
        "sequenceNumbersBetween" {
            should("contain all sequence numbers between 2 values") {
                RtpUtils.sequenceNumbersBetween(1, 10).toList() shouldContainInOrder
                        listOf(2, 3, 4, 5, 6, 7, 8, 9)
            }
            should("contain all sequence numbers between 2 values, even with rollover") {
                RtpUtils.sequenceNumbersBetween(65530, 5).toList() shouldContainInOrder
                        listOf(65531, 65532, 65533, 65535, 0, 1, 2, 3, 4)
            }
        }
    }
}
