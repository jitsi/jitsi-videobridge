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

package org.jitsi.rtp.rtcp.rtcpfb.fci

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.byteBufferOf
import java.nio.ByteBuffer

internal class FirTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Creating a FIR FCI" {
            "from values" {
                val fir = Fir(123, 220)
                should("set the values correctly") {
                    fir.ssrc shouldBe 123
                    fir.seqNum shouldBe 220
                }
                "and then getting its buffer" {
                    val buf = fir.getBuffer()
                    should("serialize the data correctly") {
                        buf.getInt() shouldBe 123
                        buf.get() shouldBe 220.toByte()
                    }
                }
                "and then serializing to an existing buffer" {
                    val existingBuf = ByteBuffer.allocate(20)
                    existingBuf.position(8)
                    fir.serializeTo(existingBuf)
                    should("serialize it to the proper place") {
                        existingBuf.getInt(8) shouldBe 123
                        existingBuf.get(12) shouldBe 220.toByte()
                    }
                    should("leave the buffer's position after the field it just wrote") {
                        existingBuf.position() shouldBe (8 + Fir.SIZE_BYTES)
                    }
                }
            }
            "from a buffer" {
                val buf = byteBufferOf(
                    0x00, 0x00, 0x00, 0x7b,
                    0xDC, 0x00, 0x00, 0x00,
                    0xDE, 0xAD, 0xBE, 0xEF
                )
                val fir = Fir.fromBuffer(buf)
                should("parse the values correctly") {
                    fir.ssrc shouldBe 123L
                    fir.seqNum shouldBe 220
                }
                should("leave the buffer's position after its data") {
                    buf.position() shouldBe Fir.SIZE_BYTES
                }
            }
        }
    }
}