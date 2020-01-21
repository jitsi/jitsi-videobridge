/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.rtp.extensions

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

class ByteExtensionsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Byte.getBit" {
            should("Correctly get the bit in each position") {
                for (onePosition in 0..7) {
                    val b: Byte = (0b1 shl (7 - onePosition)).toByte()
                    for (currPosition in 0..7) {
                        when (currPosition) {
                            onePosition -> b.getBit(currPosition) shouldBe 1
                            else -> b.getBit(currPosition) shouldBe 0
                        }
                    }
                }
            }
        }
        "Byte.putBit" {
            should("Set bits to true correctly") {
                val b: Byte = 0x00
                b.putBit(0, true) shouldBe 0b10000000.toByte()
                b.putBit(3, true) shouldBe 0b00010000.toByte()
                b.putBit(7, true) shouldBe 0b00000001.toByte()
            }
            should("Set bits to false correctly") {
                val b: Byte = 0xFF.toByte()
                b.putBit(1, false) shouldBe 0b10111111.toByte()
                b.putBit(2, false) shouldBe 0b11011111.toByte()
                b.putBit(4, false) shouldBe 0b11110111.toByte()
                b.putBit(7, false) shouldBe 0b11111110.toByte()
            }
            should("Support a mix of sets/unsets") {
                var b: Byte = 0x00
                (0..7).forEach {
                    b = b.putBit(it, true)
                }
                b shouldBe 0b11111111.toByte()
                (0..7).forEach {
                    b = b.putBit(it, false)
                }
                b shouldBe 0b00000000.toByte()
            }
        }
        "Byte.putBits" {
            should("correctly put bits") {
                val dest: Byte = 0b00000000
                val src: Byte = 0b00000101

                val result = dest.putBits(0, 3, src)
                result shouldBe 0b10100000.toByte()
            }
            should("overwrite existing values correctly") {
                val dest: Byte = 0b11111111.toByte()
                val src: Byte = 0b00000000

                val result = dest.putBits(0, 3, src)
                result shouldBe 0b00011111.toByte()
            }
            should("work correctly with different offsets") {
                val dest: Byte = 0b00000000
                val src: Byte = 0b11001100.toByte()

                val result = dest.putBits(2, 4, src)
                result shouldBe 0b00110000.toByte()
            }
        }
    }
}
