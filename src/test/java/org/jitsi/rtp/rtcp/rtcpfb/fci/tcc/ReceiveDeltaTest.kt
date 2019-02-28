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

package org.jitsi.rtp.rtcp.rtcpfb.fci.tcc

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.beInstanceOf
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.byteBufferOf
import java.nio.ByteBuffer

internal class ReceiveDeltaTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Creating a delta via the factory" {
            "from an eight-bit delta value" {
                should("create the right instance") {
                    ReceiveDelta.create(10.0) should beInstanceOf<EightBitReceiveDelta>()
                    ReceiveDelta.create(0.0) should beInstanceOf<EightBitReceiveDelta>()
                    ReceiveDelta.create(63.75) should beInstanceOf<EightBitReceiveDelta>()
                }
            }
            "from a sixteen-bit delta value" {
                should("create the right instance") {
                    ReceiveDelta.create(8191.75) should beInstanceOf<SixteenBitReceiveDelta>()
                    ReceiveDelta.create(63.76) should beInstanceOf<SixteenBitReceiveDelta>()
                    ReceiveDelta.create(-0.01) should beInstanceOf<SixteenBitReceiveDelta>()
                    ReceiveDelta.create(-8192.0) should beInstanceOf<SixteenBitReceiveDelta>()
                }
            }
            "from an invalid value" {
                should("throw an exception") {
                    shouldThrow<Exception> { ReceiveDelta.create(-8192.1) }
                    shouldThrow<Exception> { ReceiveDelta.create(8191.76) }
                }
            }
            "from a valid eight-bit buffer" {
                should("parse it correctly") {
                    ReceiveDelta.parse(
                        byteBufferOf(0x28),
                        EightBitReceiveDelta.SIZE_BYTES
                    ) should beInstanceOf<EightBitReceiveDelta>()
                }
            }
            "from a valid sixteen-bit buffer" {
                should("parse it correctly") {
                    ReceiveDelta.parse(
                        byteBufferOf(0x00, 0x28),
                        SixteenBitReceiveDelta.SIZE_BYTES
                    ) should beInstanceOf<SixteenBitReceiveDelta>()
                }
            }
            "from an invalid buffer" {
                should("throw an exception") {
                    shouldThrow<Exception> {
                        ReceiveDelta.parse(byteBufferOf(0x00, 0x28, 0x29), 3)
                    }
                }
            }
        }
        "EightBitReceiveDelta" {
            "when created from a value" {
                val eightBitReceiveDelta = EightBitReceiveDelta(10.0)
                should("set the value correctly") {
                    eightBitReceiveDelta.deltaMs shouldBe 10.0
                }
                "and its buffer is requested" {
                    should("serialize the data correctly") {
                        val buf = eightBitReceiveDelta.getBuffer()
                        buf.get() shouldBe 0x28.toByte()
                    }
                }
                "and serialized to an existing buffer" {
                    val existingBuf = ByteBuffer.allocate(10)
                    existingBuf.position(5)
                    eightBitReceiveDelta.serializeTo(existingBuf)
                    should("serialize it to the proper place") {
                        existingBuf.get(5) shouldBe 0x28.toByte()
                    }
                    should("leave the buffer's position after the field it just wrote") {
                        existingBuf.position() shouldBe 6
                    }
                }
            }
            "when created from edge case values" {
                "like 63.75" {
                    val eightBitReceiveDelta = EightBitReceiveDelta(63.75)
                    should("set the value correctly") {
                        eightBitReceiveDelta.deltaMs shouldBe 63.75
                    }
                    "and its buffer is requested" {
                        should("serialize the data correctly") {
                            val buf = eightBitReceiveDelta.getBuffer()
                            buf.get() shouldBe 0xFF.toByte()
                        }
                    }
                }
                "like 0" {
                    val eightBitReceiveDelta = EightBitReceiveDelta(0.0)
                    should("set the value correctly") {
                        eightBitReceiveDelta.deltaMs shouldBe 0.0
                    }
                    "and its buffer is requested" {
                        should("serialize the data correctly") {
                            val buf = eightBitReceiveDelta.getBuffer()
                            buf.get() shouldBe 0x00.toByte()
                        }
                    }
                }
            }
            "when created from a buffer" {
                val buf = byteBufferOf(0x28)
                val eightBitReceiveDelta = EightBitReceiveDelta(buf)
                should("parse the value correctly") {
                    eightBitReceiveDelta.deltaMs shouldBe 10.0
                }
                should("leave the buffer's position after the parsed data") {
                    buf.position() shouldBe buf.limit()
                }
            }
        }
        "SixteenBitReceiveDelta" {
            "when created from a value" {
                val sixteenBitReceiveDelta = SixteenBitReceiveDelta(10.0)
                should("set the value correctly") {
                    sixteenBitReceiveDelta.deltaMs shouldBe 10.0
                }
                "and its buffer is requested" {
                    should("serialize the data correctly") {
                        val buf = sixteenBitReceiveDelta.getBuffer()
                        buf.short shouldBe 0x0028.toShort()
                    }
                }
                "and serialized to an existing buffer" {
                    val existingBuf = ByteBuffer.allocate(10)
                    existingBuf.position(5)
                    sixteenBitReceiveDelta.serializeTo(existingBuf)
                    should("serialize it to the proper place") {
                        existingBuf.getShort(5) shouldBe 0x0028.toShort()
                    }
                    should("leave the buffer's position after the field it just wrote") {
                        existingBuf.position() shouldBe 7
                    }
                }
            }
            "when created from edge case values" {
                "like -8192.0" {
                    val sixteenBitReceiveDelta = SixteenBitReceiveDelta(-8192.0)
                    should("set the value correctly") {
                        sixteenBitReceiveDelta.deltaMs shouldBe -8192.0
                    }
                    "and its buffer is requested" {
                        should("serialize the data correctly") {
                            val buf = sixteenBitReceiveDelta.getBuffer()
                            buf.short shouldBe 0x8000.toShort()
                        }
                    }
                }
                "like 8191.75" {
                    val sixteenBitReceiveDelta = SixteenBitReceiveDelta(8191.75)
                    should("set the value correctly") {
                        sixteenBitReceiveDelta.deltaMs shouldBe 8191.75
                    }
                    "and its buffer is requested" {
                        should("serialize the data correctly") {
                            val buf = sixteenBitReceiveDelta.getBuffer()
                            buf.short shouldBe 0x7FFF.toShort()
                        }
                    }
                }
            }
            "when created from a buffer" {
                val buf = byteBufferOf(0x00, 0x28, 0xDE, 0xAD)
                val sixteenBitReceiveDelta = SixteenBitReceiveDelta(buf)
                should("parse the value correctly") {
                    sixteenBitReceiveDelta.deltaMs shouldBe 10.0
                }
                should("leave the buffer's position after its data") {
                    buf.position() shouldBe 2
                }
            }
        }
    }
}