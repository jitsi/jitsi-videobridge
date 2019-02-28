/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
import java.nio.ByteBuffer

class ByteBufferExtensionsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "ByteBuffer" {
            "put3Bytes" {
                val buf = ByteBuffer.allocate(4)
                "for a 3 byte value" {
                    // 424242 = 0x067932
                    val num: Int = 424242
                    buf.put3Bytes(num)
                    buf.rewind()
                    should("write the bytes correctly") {
                        buf.get() shouldBe 0x06.toByte()
                        buf.get() shouldBe 0x79.toByte()
                        buf.get() shouldBe 0x32.toByte()
                    }
                }
                "for a 2 byte value" {
                    // 4242 = 0x1092
                    val num: Int = 4242
                    buf.put3Bytes(num)
                    buf.rewind()
                    should("write the bytes correctly") {
                        buf.get() shouldBe 0x00.toByte()
                        buf.get() shouldBe 0x10.toByte()
                        buf.get() shouldBe 0x92.toByte()
                    }
                }
                "for a 1 byte value" {
                    // 42 = 0x2A
                    val num: Int = 42
                    buf.put3Bytes(num)
                    buf.rewind()
                    should("write the bytes correctly") {
                        buf.get() shouldBe 0x00.toByte()
                        buf.get() shouldBe 0x00.toByte()
                        buf.get() shouldBe 0x2A.toByte()
                    }
                }
            }
            "get3Bytes" {
                val buf = ByteBuffer.allocate(4)
                "for a 3 byte value" {
                    val num: Int = 424242
                    buf.put3Bytes(num)
                    buf.rewind()
                    should("read them correctly") {
                        buf.get3Bytes() shouldBe 424242.toInt()
                    }
                }
                "for a 2 byte value" {
                    val num: Int = 4242
                    buf.put3Bytes(num)
                    buf.rewind()
                    should("read them correctly") {
                        buf.get3Bytes() shouldBe 4242.toInt()
                    }
                }
                "for a 1 byte value" {
                    val num: Int = 42
                    buf.put3Bytes(num)
                    buf.rewind()
                    should("read them correctly") {
                        buf.get3Bytes() shouldBe 42.toInt()
                    }
                }

            }
            "putBits" {
                should("write the bits into the buffer correctly") {
                    val buf = ByteBuffer.allocate(4)
                    val src: Byte = 0b00001111

                    buf.putBits(1, 0, src, 4)

                    buf.get(1) shouldBe 0b11110000.toByte()
                    // Nothing else should've changed
                    listOf(0, 2, 3).forEach {
                        buf.get(it) shouldBe 0b00000000.toByte()
                    }
                }
            }
            "subBuffer(startPosition, size)" {
                val originalBuf = ByteBuffer.allocate(100)
                "created from a default original buffer" {
                    val subBuf = originalBuf.subBuffer(10, 30)
                    should("have a correctly set position, limit and capacity") {
                        subBuf.position() shouldBe 0
                        subBuf.limit() shouldBe 30
                        subBuf.capacity() shouldBe 30
                    }
                    should("not affect the original buffer in any way") {
                        originalBuf.position() shouldBe 0
                        originalBuf.limit() shouldBe 100
                        originalBuf.capacity() shouldBe 100
                    }
                    should("represent the correct spot in the original buffer") {
                        repeat (subBuf.limit()) { subBuf.put(it, 0x42) }

                        (0..9).forEach { originalBuf.get(it) shouldBe 0x00.toByte() }
                        (10..39).forEach { originalBuf.get(it) shouldBe 0x42.toByte() }
                        (40..99).forEach { originalBuf.get(it) shouldBe 0x00.toByte() }
                    }
                }
                "created from a buffer whose current position isn't 0" {
                    originalBuf.position(10)
                    val subBuf = originalBuf.subBuffer(5, 10)
                    should("represent the correct spot in the original buffer") {
                        repeat (subBuf.limit()) { subBuf.put(it, 0x42) }

                        (0..4).forEach { originalBuf.get(it) shouldBe 0x00.toByte() }
                        (5..14).forEach { originalBuf.get(it) shouldBe 0x42.toByte() }
                        (15..99).forEach { originalBuf.get(it) shouldBe 0x00.toByte() }
                    }
                }
            }
            "subBuffer(startPosition)" {
                val originalBuf = ByteBuffer.allocate(100)
                "created from a default original buffer" {
                    val subBuf = originalBuf.subBuffer(10)
                    should("have a correctly set position, limit and capacity") {
                        subBuf.position() shouldBe 0
                        subBuf.limit() shouldBe 90
                        subBuf.capacity() shouldBe 90
                    }
                    should("not affect the original buffer in any way") {
                        originalBuf.position() shouldBe 0
                        originalBuf.limit() shouldBe 100
                        originalBuf.capacity() shouldBe 100
                    }
                    should("represent the correct spot in the original buffer") {
                        repeat (subBuf.limit()) { subBuf.put(it, 0x42) }

                        (0..9).forEach { originalBuf.get(it) shouldBe 0x00.toByte() }
                        (10..99).forEach { originalBuf.get(it) shouldBe 0x42.toByte() }
                    }
                }
                "created from a buffer whose current position isn't 0" {
                    originalBuf.position(10)
                    val subBuf = originalBuf.subBuffer(5)
                    should("represent the correct spot in the original buffer") {
                        repeat (subBuf.limit()) { subBuf.put(it, 0x42) }

                        (0..4).forEach { originalBuf.get(it) shouldBe 0x00.toByte() }
                        (5..99).forEach { originalBuf.get(it) shouldBe 0x42.toByte() }
                    }
                }
            }
        }
    }
}
