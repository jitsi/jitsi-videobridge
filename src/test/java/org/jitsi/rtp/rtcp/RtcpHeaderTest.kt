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

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.BitBuffer
import java.nio.ByteBuffer

internal class RtcpHeaderTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val headerBuf = with(ByteBuffer.allocate(8)) {
        val bitBuffer = BitBuffer(this)
        bitBuffer.putBits(2.toByte(), 2) // version
        bitBuffer.putBoolean(false) // padding
        bitBuffer.putBits(1.toByte(), 5) // report count
        put(200.toByte()) // packet type
        putShort(0xFFFF.toShort()) // length
        putInt(0xFFFFFFFF.toInt()) // sender ssrc
        this.rewind() as ByteBuffer
    }

    init {
        "creation" {
            "from a buffer" {
                val header = RtcpHeader(headerBuf)
                should("fromBuffer the values correctly") {
                    header.version shouldBe 2
                    header.hasPadding shouldBe false
                    header.reportCount shouldBe 1
                    header.packetType shouldBe 200
                    header.length shouldBe 0xFFFF
                    header.senderSsrc shouldBe 0xFFFFFFFF
                }
            }
            "from a complete set of values" {
                val header = RtcpHeader(
                    version = 2,
                    hasPadding = false,
                    reportCount = 1,
                    packetType = 200,
                    length = 0xFFFF,
                    senderSsrc = 0xFFFFFFFF
                )
                should("set everything correctly") {
                    header.version shouldBe 2
                    header.hasPadding shouldBe false
                    header.reportCount shouldBe 1
                    header.packetType shouldBe 200
                    header.length shouldBe 0xFFFF
                    header.senderSsrc shouldBe 0xFFFFFFFF
                }
            }
            "passing a subset of values in the constructor" {
                val header = RtcpHeader(senderSsrc = 12345L)
                should("set the passed values") {
                    header.senderSsrc shouldBe 12345L
                }

                should("set the default version") {
                    header.version shouldBe 2

                }
            }
        }
        "serialization" {
            val header = RtcpHeader(headerBuf)
            val newBuf = header.getBuffer()
            should("write the correct data to the buffer") {
                newBuf.rewind() shouldBe headerBuf.rewind()
            }
        }
    }
}
