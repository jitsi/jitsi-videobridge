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
            var num = RtpSequenceNumber(65535)
            "via add-and-assign" {
                should("work correctly") {
                    num += 1
                    num shouldBe RtpSequenceNumber(0)
                }
            }
            "via plus" {
                should("work correctly") {
                    (num + 1) shouldBe RtpSequenceNumber(0)
                }
            }
        }
        "reverse rollover" {
            var num = RtpSequenceNumber(0)
            "via subtract-and-assign" {
                should("work correctly") {
                    num -= 1
                    num shouldBe RtpSequenceNumber(65535)
                }
            }
            "via minus" {
                should("work correctly") {
                    (num - 1) shouldBe RtpSequenceNumber(65535)
                }
            }
        }
        "comparison" {
            should("work correctly") {
                (RtpSequenceNumber(1) < RtpSequenceNumber(2)) shouldBe true
                (RtpSequenceNumber(1) <= RtpSequenceNumber(2)) shouldBe true
                (RtpSequenceNumber(1) <= RtpSequenceNumber(1)) shouldBe true
                (RtpSequenceNumber(1) == RtpSequenceNumber(1)) shouldBe true
                (RtpSequenceNumber(1) < RtpSequenceNumber(0)) shouldBe false
                (RtpSequenceNumber(65534) < RtpSequenceNumber(0)) shouldBe true
                (RtpSequenceNumber(32768) < RtpSequenceNumber(0)) shouldBe false
                (RtpSequenceNumber(32769) < RtpSequenceNumber(0)) shouldBe true
            }
        }
        "rangeTo" {
            should("work correctly") {
                (RtpSequenceNumber(65533)..RtpSequenceNumber(2)).asSequence().shouldContainInOrder(
                    RtpSequenceNumber(65533),
                    RtpSequenceNumber(65534),
                    RtpSequenceNumber(65535),
                    RtpSequenceNumber(0),
                    RtpSequenceNumber(1),
                    RtpSequenceNumber(2)
                )

                (RtpSequenceNumber(2) downTo RtpSequenceNumber(65533)).asSequence().shouldContainInOrder(
                    RtpSequenceNumber(2),
                    RtpSequenceNumber(1),
                    RtpSequenceNumber(0),
                    RtpSequenceNumber(65535),
                    RtpSequenceNumber(65534),
                    RtpSequenceNumber(65533)
                )
            }
        }
    }
}
