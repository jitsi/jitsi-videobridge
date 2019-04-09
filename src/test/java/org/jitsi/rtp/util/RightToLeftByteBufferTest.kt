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

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

internal class RightToLeftByteBufferTest : ShouldSpec() {
    init {
        val data = byteBufferOf(
            0x0F.toByte(), 0x0F
        )
        "Getting a bit" {
            should("work correctly using right-to-left indicies") {
                RightToLeftBufferUtils.getBitAsBool(data, 0) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 1) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 2) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 3) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 4) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 5) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 6) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 7) shouldBe false

                RightToLeftBufferUtils.getBitAsBool(data, 8) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 9) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 10) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 11) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 12) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 13) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 14) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 15) shouldBe false
            }
        }
        "Setting a bit" {
            RightToLeftBufferUtils.putBit(data, 0, false)
            RightToLeftBufferUtils.putBit(data, 1, false)
            RightToLeftBufferUtils.putBit(data, 2, false)
            RightToLeftBufferUtils.putBit(data, 3, false)
            RightToLeftBufferUtils.putBit(data, 4, true)
            RightToLeftBufferUtils.putBit(data, 5, true)
            RightToLeftBufferUtils.putBit(data, 6, true)
            RightToLeftBufferUtils.putBit(data, 7, true)
            RightToLeftBufferUtils.putBit(data, 8, false)
            RightToLeftBufferUtils.putBit(data, 9, false)
            RightToLeftBufferUtils.putBit(data, 10, false)
            RightToLeftBufferUtils.putBit(data, 11, false)
            RightToLeftBufferUtils.putBit(data, 12, true)
            RightToLeftBufferUtils.putBit(data, 13, true)
            RightToLeftBufferUtils.putBit(data, 14, true)
            RightToLeftBufferUtils.putBit(data, 15, true)
            should("work correctly using right-to-left indicies") {
                RightToLeftBufferUtils.getBitAsBool(data, 0) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 1) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 2) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 3) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 4) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 5) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 6) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 7) shouldBe true

                RightToLeftBufferUtils.getBitAsBool(data, 8) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 9) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 10) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 11) shouldBe false
                RightToLeftBufferUtils.getBitAsBool(data, 12) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 13) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 14) shouldBe true
                RightToLeftBufferUtils.getBitAsBool(data, 15) shouldBe true
            }
        }
    }
}
