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

package org.jitsi.rtp.rtp

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.sequences.shouldContainInOrder
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

class RtpSequenceNumberTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "rollover" {
            var num = 65535.toRtpSequenceNumber()
            "via add-and-assign" {
                should("work correctly") {
                    num += 1
                    num shouldBe 0.toRtpSequenceNumber()
                }
            }
            "via plus" {
                should("work correctly") {
                    (num + 1) shouldBe 0.toRtpSequenceNumber()
                }
            }
        }
        "reverse rollover" {
            var num = 0.toRtpSequenceNumber()
            "via subtract-and-assign" {
                should("work correctly") {
                    num -= 1
                    num shouldBe 65535.toRtpSequenceNumber()
                }
            }
            "via minus" {
                should("work correctly") {
                    (num - 1) shouldBe 65535.toRtpSequenceNumber()
                }
            }
        }
        "comparison" {
            should("work correctly") {
                (1.toRtpSequenceNumber() < 2.toRtpSequenceNumber()) shouldBe true
                (1.toRtpSequenceNumber() <= 2.toRtpSequenceNumber()) shouldBe true
                (1.toRtpSequenceNumber() <= 1.toRtpSequenceNumber()) shouldBe true
                (1.toRtpSequenceNumber() == 1.toRtpSequenceNumber()) shouldBe true
                (1.toRtpSequenceNumber() < 0.toRtpSequenceNumber()) shouldBe false
                (65534.toRtpSequenceNumber() < 0.toRtpSequenceNumber()) shouldBe true
                (32767.toRtpSequenceNumber() < 0.toRtpSequenceNumber()) shouldBe false
                (32768.toRtpSequenceNumber() < 0.toRtpSequenceNumber()) shouldBe true
            }
        }
        "rangeTo" {
            should("work correctly") {
                (65533.toRtpSequenceNumber()..2.toRtpSequenceNumber()).asSequence().shouldContainInOrder(
                    65533.toRtpSequenceNumber(),
                    65534.toRtpSequenceNumber(),
                    65535.toRtpSequenceNumber(),
                    0.toRtpSequenceNumber(),
                    1.toRtpSequenceNumber(),
                    2.toRtpSequenceNumber()
                )

                (2.toRtpSequenceNumber() downTo 65533.toRtpSequenceNumber()).asSequence().shouldContainInOrder(
                    2.toRtpSequenceNumber(),
                    1.toRtpSequenceNumber(),
                    0.toRtpSequenceNumber(),
                    65535.toRtpSequenceNumber(),
                    65534.toRtpSequenceNumber(),
                    65533.toRtpSequenceNumber()
                )
            }
        }
    }
}
