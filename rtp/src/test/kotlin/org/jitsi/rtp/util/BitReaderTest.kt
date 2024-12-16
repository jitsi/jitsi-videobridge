/*
 * Copyright @ 2024 - present 8x8, Inc.
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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe

class BitReaderTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        val bitReader = BitReader(
            byteArrayOf(
                0b0101_1010.toByte(),
                0b0000_1111.toByte(),
                0b1010_1010.toByte(),
                0b1010_1010.toByte(),
                0b0111_1111.toByte()
            )
        )
        val bits = 40
        context("Reading single bits as boolean") {
            bitReader.bitAsBoolean() shouldBe false
            bitReader.bitAsBoolean() shouldBe true
            bitReader.bitAsBoolean() shouldBe false
            bitReader.bitAsBoolean() shouldBe true
            bitReader.bitAsBoolean() shouldBe true
            bitReader.remainingBits() shouldBe bits - 5
        }

        context("Reading single bits as Integer") {
            bitReader.bit() shouldBe 0
            bitReader.bit() shouldBe 1
            bitReader.bit() shouldBe 0
            bitReader.bit() shouldBe 1
            bitReader.bit() shouldBe 1
            bitReader.remainingBits() shouldBe bits - 5
        }

        context("Reading multiple bits as Integer") {
            bitReader.bits(4) shouldBe 0b0101
            bitReader.bits(4) shouldBe 0b1010
            bitReader.bits(6) shouldBe 0b000011
            bitReader.bits(2) shouldBe 0b11
            bitReader.remainingBits() shouldBe bits - 16
        }

        context("Reading multiple bits as Long") {
            bitReader.bitsLong(4) shouldBe 0b0101L
            bitReader.bitsLong(4) shouldBe 0b1010L
            bitReader.bits(6) shouldBe 0b000011L
            bitReader.bits(2) shouldBe 0b11L
            bitReader.remainingBits() shouldBe bits - 16
        }

        context("skip bits correctly") {
            bitReader.skipBits(4)
            bitReader.remainingBits() shouldBe bits - 4
            bitReader.bit() shouldBe 1
            bitReader.remainingBits() shouldBe bits - 5
        }

        context("Reading LEB128-encoded unsigned integers") {
            bitReader.leb128() shouldBe 0b0101_1010
            bitReader.remainingBits() shouldBe bits - 8
            bitReader.leb128() shouldBe 0b00001111
            bitReader.remainingBits() shouldBe bits - 16
            bitReader.leb128() shouldBe 0b111_1111__010_1010__010_1010
            bitReader.remainingBits() shouldBe 0
        }

        context("Cloning") {
            bitReader.skipBits(8)
            val clone = bitReader.clone(2)
            bitReader.remainingBits() shouldBe bits - 8
            clone.remainingBits() shouldBe 16

            bitReader.bits(8) shouldBe 0b0000_1111
            bitReader.remainingBits() shouldBe bits - 16
            clone.remainingBits() shouldBe 16

            clone.bits(8) shouldBe 0b0000_1111
            bitReader.remainingBits() shouldBe bits - 16
            clone.remainingBits() shouldBe 8

            clone.skipBits(8)
            bitReader.remainingBits() shouldBe bits - 16
            clone.remainingBits() shouldBe 0

            bitReader.bits(8) shouldBe 0b1010_1010
            shouldThrow<IllegalStateException> {
                clone.bits(8)
            }
        }

        context("Throwing exception when reading past the bounds") {
            bitReader.skipBits(bits)
            bitReader.remainingBits() shouldBe 0
            shouldThrow<IllegalStateException> {
                bitReader.bitAsBoolean()
            }
            shouldThrow<IllegalStateException> {
                bitReader.bit()
            }
            shouldThrow<IllegalStateException> {
                bitReader.bits(4)
            }
            shouldThrow<IllegalStateException> {
                bitReader.leb128()
            }
            shouldThrow<IllegalStateException> {
                BitReader(byteArrayOf(0b1111_0000.toByte())).leb128()
            }
        }
    }
}
