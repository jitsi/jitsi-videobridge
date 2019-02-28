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

package org.jitsi.rtp.rtcp.sdes

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.beInstanceOf
import io.kotlintest.matchers.types.shouldBeSameInstanceAs
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.ByteBufferUtils
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal class SdesItemTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val sampleCnameString = "Hello, world"
    private val sampleCnameData = ByteBuffer.wrap(sampleCnameString.toByteArray(StandardCharsets.US_ASCII))
    val sampleCnameSdesItem = CnameSdesItem(sampleCnameString)
    val sampleCnameSdesBuf = ByteBuffer.allocate(2 + sampleCnameData.limit()).apply {
        put(SdesItemType.CNAME.value.toByte())
        put(sampleCnameData.limit().toByte())
        put(sampleCnameData.duplicate())
    }.flip() as ByteBuffer

    private val sampleUnknownSdesData = byteBufferOf(0xDE, 0xAD, 0xBE, 0xEF)
    val sampleUnknownSdesBuf = ByteBuffer.allocate(2 + sampleUnknownSdesData.limit()).apply {
        put(0x07)
        put(sampleUnknownSdesData.limit().toByte())
        put(sampleUnknownSdesData.duplicate())
    }.flip() as ByteBuffer

    val sampleEmptySdesBuf = byteBufferOf(0x00)

    val sampleEmptySdesBufWithPadding = byteBufferOf(0x00, 0x00, 0x00, 0x00)

    init {
        "Creating an SDES item" {
            "from a buffer" {
                val sdesItem = SdesItem.fromBuffer(sampleCnameSdesBuf)
                should("parse the type correctly") {
                    sdesItem should beInstanceOf<CnameSdesItem>()
                }
                should("parse all fields correctly") {
                    sdesItem as CnameSdesItem
                    sdesItem.type shouldBe SdesItemType.CNAME
                    sdesItem.sizeBytes shouldBe sampleCnameSdesBuf.limit()
                    sdesItem.cname shouldBe sampleCnameString
                }
                should("leave the buffer's position after the parsed data") {
                    sampleCnameSdesBuf.position() shouldBe sampleCnameSdesBuf.limit()
                }
            }
            "from a buffer with an unknown sdes item type" {
                val sdesItem = SdesItem.fromBuffer(sampleUnknownSdesBuf)
                should("parse it as an unknown item") {
                    sdesItem should beInstanceOf<UnknownSdesItem>()
                    sdesItem.type shouldBe SdesItemType.UNKNOWN
                    sdesItem.data should haveSameContentAs(sampleUnknownSdesData)
                }
            }
            "from values" {
                should("set the fields correctly") {
                    sampleCnameSdesItem.type shouldBe SdesItemType.CNAME
                    sampleCnameSdesItem.sizeBytes shouldBe SdesItem.SDES_ITEM_HEADER_SIZE + sampleCnameString.length
                    sampleCnameSdesItem.cname shouldBe sampleCnameString
                }
                "and then serializing it" {
                    "by requesting a buffer" {
                        val buf = sampleCnameSdesItem.getBuffer()
                        should("serialize the data correctly") {
                            buf should haveSameContentAs(sampleCnameSdesBuf)
                        }
                    }
                    "to an existing buffer" {
                        val existingBuf = ByteBuffer.allocate(8 + sampleCnameSdesBuf.limit())
                        existingBuf.position(8)
                        sampleCnameSdesItem.serializeTo(existingBuf)
                        should("write the data to the correct place") {
                            existingBuf.subBuffer(8) should haveSameContentAs(sampleCnameSdesBuf)
                        }
                        should("leave the buffer's position after the written data") {
                            existingBuf.position() shouldBe existingBuf.limit()
                        }
                    }
                }
            }
        }
        "Creating an empty SDES item" {
            val type = SdesItemType.EMPTY
            "from a buffer" {
                val sdesItem = SdesItem.fromBuffer(sampleEmptySdesBuf)
                should("return the static EmptySdesItem instance") {
                    sdesItem.shouldBeSameInstanceAs(EmptySdesItem)
                }
            }
            "from a buffer with padding after" {
                val sdesItem = SdesItem.fromBuffer(sampleEmptySdesBufWithPadding)
                should("still parse correctly") {
                    sdesItem.shouldBeSameInstanceAs(EmptySdesItem)
                }
            }
        }
        "EmptySdesItem" {
            should("have fields set correctly") {
                EmptySdesItem.sizeBytes shouldBe 1
                EmptySdesItem.length shouldBe 0
                EmptySdesItem.data.shouldBeSameInstanceAs(ByteBufferUtils.EMPTY_BUFFER)
            }
            should("serialize correctly") {
                val buf = EmptySdesItem.getBuffer()
                buf.limit() shouldBe 1
            }
        }
    }
}
