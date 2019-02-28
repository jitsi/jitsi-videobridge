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
import io.kotlintest.matchers.collections.shouldContainExactly
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.RightToLeftBufferUtils
import org.jitsi.rtp.util.byteBufferOf
import java.nio.ByteBuffer

internal class GenericNackTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Creating a GenericNack" {
            "from values" {
                val missingSeqNums = listOf(10, 11, 13, 15, 17, 19, 21, 23, 26)
                val genericNack = GenericNack.fromValues(missingSeqNums)
                should("set the missing seq nums correctly") {
                    genericNack.missingSeqNums shouldContainExactly missingSeqNums
                }
                "and then serializing it" {
                    val buf = genericNack.getBuffer()
                    should("write the data correctly") {
                        // We test parsing from a buffer works correctly below, so here we can reparse the
                        // buffer and use that to verify the values
                        val parsedNack = GenericNack.fromBuffer(buf)
                        parsedNack.missingSeqNums shouldContainExactly missingSeqNums
                    }
                }
            }
            "from a buffer" {
                val missingSeqNums = listOf(10, 11, 13, 15, 17, 19, 21, 23, 26)
                val buf = byteBufferOf(0x00, 0x0A, 0x95, 0x55)
                val parsedGenericNack = GenericNack.fromBuffer(buf)
                should("parse the values correctly") {
                    parsedGenericNack.missingSeqNums shouldContainExactly missingSeqNums
                }
            }
        }
        "Creating a GenericNackBlp" {
            "from values" {
                val offsets = listOf(1, 3, 5, 7, 9, 11, 13, 15)
                val nackBlp = GenericNackBlp(offsets)
                "should set the offsets correctly" {
                    nackBlp.lostPacketOffsets shouldContainExactly offsets
                }
                "and then getting its buffer" {
                    val buf = nackBlp.getBuffer()
                    should("encode the offsets correctly") {
                        validateNackBlpBits(offsets, buf)
                    }
                }
                "and then serializing it to an existing buffer" {
                    val existingBuf = ByteBuffer.allocate(6)
                    existingBuf.position(2)
                    nackBlp.serializeTo(existingBuf)
                    should("serialize it to the proper place") {
                        existingBuf.getShort(2) shouldBe 0x5555.toShort()
                    }
                    should("leave the buffer's position after the field it just wrote") {
                        existingBuf.position() shouldBe 4
                    }
                }
            }
            "from a buffer" {
                val offsets = listOf(1, 3, 5, 7, 9, 11, 13, 15)
                val nackBlp = GenericNackBlp(offsets)
                val buf = nackBlp.getBuffer()
                val parsedNackBlp = GenericNackBlp.parse(buf)
                should("parse the offsets correctly") {
                    parsedNackBlp.lostPacketOffsets shouldContainExactly offsets
                }
                should("leave the buffer's position after the field it just wrote") {
                    buf.position() shouldBe 2
                }
            }
        }
    }

    private fun validateNackBlpBits(expectedOffsets: List<Int>, buf: ByteBuffer) {
        for (i in 0..15) {
            if ((i + 1) in expectedOffsets) {
                RightToLeftBufferUtils.getBitAsBool(buf, i) shouldBe true
            } else {
                RightToLeftBufferUtils.getBitAsBool(buf, i) shouldBe false
            }
        }
    }
}
