/*
 * Copyright @ 2026 - present 8x8, Inc.
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

class BitWriterTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode = IsolationMode.InstancePerLeaf

    init {
        context("Writing bits") {
            val buf = ByteArray(8)
            val writer = BitWriter(buf)

            should("write unaligned fields which a BitReader reads back") {
                writer.writeBits(3, 0b101)
                writer.writeBits(13, 0x1234)
                writer.writeBit(true)
                val reader = BitReader(buf)
                reader.bits(3) shouldBe 0b101
                reader.bits(13) shouldBe 0x1234
                reader.bitAsBoolean() shouldBe true
            }
            should("write 32 bits, with or without the sign bit") {
                writer.writeBits(32, 0xAAAAAAAA.toInt())
                writer.writeBits(32, 0x55555555)
                val reader = BitReader(buf)
                reader.bits(32) shouldBe 0xAAAAAAAA.toInt()
                reader.bits(32) shouldBe 0x55555555
            }
            should("write 32 bits after an unaligned field") {
                writer.writeBit(true)
                writer.writeBits(32, -1)
                val reader = BitReader(buf)
                reader.bitAsBoolean() shouldBe true
                reader.bits(32) shouldBe -1
                reader.bits(7) shouldBe 0
            }
            should("reject a value which does not fit") {
                shouldThrow<IllegalStateException> { writer.writeBits(4, 16) }
                shouldThrow<IllegalStateException> { writer.writeBits(31, -1) }
            }
            should("reject more than 32 bits") {
                shouldThrow<IllegalArgumentException> { writer.writeBits(33, 0) }
                shouldThrow<IllegalArgumentException> { writer.writeBits(64, 0) }
            }
        }
    }
}
