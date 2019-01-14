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

package org.jitsi.rtp.rtcp

import io.kotlintest.matchers.types.shouldBeSameInstanceAs
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.rtcp.SdesItem.Companion.SDES_ITEM_HEADER_SIZE
import org.jitsi.rtp.util.ByteBufferUtils
import org.junit.jupiter.api.Assertions.*
import unsigned.toUByte
import java.nio.ByteBuffer

internal class SdesItemTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    init {
        "Creating an SDES item" {
            val type = SdesItemType.CNAME
            val length = 16
            val cname = ByteBuffer.wrap(byteArrayOf(
                0x42.toByte(), 0x43.toByte(), 0x44.toByte(), 0x45.toByte(),
                0x42.toByte(), 0x43.toByte(), 0x44.toByte(), 0x45.toByte(),
                0x42.toByte(), 0x43.toByte(), 0x44.toByte(), 0x45.toByte(),
                0x42.toByte(), 0x43.toByte(), 0x44.toByte(), 0x45.toByte()
            ))
            "from a buffer" {
                val sdesItemBuf = ByteBuffer.allocate(SDES_ITEM_HEADER_SIZE + length)
                sdesItemBuf.put(type.value.toUByte())
                sdesItemBuf.put(length.toUByte())
                sdesItemBuf.put(cname.clone())
                sdesItemBuf.rewind()

                val sdesItem = SdesItem.fromBuffer(sdesItemBuf.clone())
                should("parse all fields correctly") {
                    sdesItem.type shouldBe type
                    sdesItem.length shouldBe length
                    sdesItem.data.compareTo(cname) shouldBe 0
                    sdesItem.size shouldBe SDES_ITEM_HEADER_SIZE + length
                }
                "and then serializing it" {
                    val buf = sdesItem.getBuffer()
                    sdesItemBuf.rewind()
                    buf.compareTo(sdesItemBuf) shouldBe 0
                }
            }
        }
        "Creating an empty SDES item" {
            val type = SdesItemType.EMPTY
            "from a buffer" {
                val sdesItemBuf = ByteBuffer.allocate(1)
                sdesItemBuf.put(type.value.toUByte())
                sdesItemBuf.rewind()

                val sdesItem = SdesItem.fromBuffer(sdesItemBuf.clone())
                should("return the static EMPTY_ITEM instance") {
                    sdesItem.shouldBeSameInstanceAs(SdesItem.EMPTY_ITEM)
                }
                "with padding after" {
                    val sdesItemBufPadding = ByteBuffer.allocate(4)
                    sdesItemBuf.rewind()
                    sdesItemBufPadding.put(sdesItemBuf)
                    while (sdesItemBufPadding.remaining() > 0) {
                        sdesItemBufPadding.put(0x00)
                    }
                    sdesItemBufPadding.rewind()
                    SdesItem.fromBuffer(sdesItemBufPadding)
                    should("still parse correctly") {
                        sdesItem.shouldBeSameInstanceAs(SdesItem.EMPTY_ITEM)
                    }
                }
            }
        }
        "SdesItem.EMPTY_ITEM" {
            should("have fields set correctly") {
                SdesItem.EMPTY_ITEM.size shouldBe 1
                SdesItem.EMPTY_ITEM.length shouldBe 0
                SdesItem.EMPTY_ITEM.data.shouldBeSameInstanceAs(ByteBufferUtils.EMPTY_BUFFER)
            }
        }
    }
}
