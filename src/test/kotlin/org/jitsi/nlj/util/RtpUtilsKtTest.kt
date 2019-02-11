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

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.collections.shouldContainInOrder
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

internal class RtpUtilsKtTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "rolledOverTo" {
            should("return true when a rollover has taken place") {
                65535 rolledOverTo 1 shouldBe true
                65000 rolledOverTo 200 shouldBe true
            }
            should("return false when a rollover has not occurred") {
                0 rolledOverTo 65535 shouldBe false
            }
        }
        "isNewerThan" {
            should("return true for an immediately newer sequence number") {
                2 isNewerThan 1 shouldBe true
            }
            should("return true in the case of a rollover") {
                2 isNewerThan 65530 shouldBe true
            }
        }
        "isOlderThan" {
            should("return true for an older packet (no rollover)") {
                1 isOlderThan 2 shouldBe true
            }
            should("return true for an older packet (with rollover)") {
                65530 isOlderThan 2 shouldBe true
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
    }
}