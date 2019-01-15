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

package org.jitsi.rtp

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.collections.shouldContainInOrder
import io.kotlintest.matchers.haveSize
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.subBuffer
import java.nio.ByteBuffer

internal class RtpHeaderTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
    // v=2, p=1, x=0, cc=3 = 0xA3
    // m=1, pt=96 = 0xE0
    // seqnum 4224 = 0x10 0x80
    // timestamp 98765 = 0x00 0x01 0x81 0xCD
    // ssrc 1234567 = 0x00 0x12 0xD6 0x87
    // csrc 1 = 0x00 0x00 0x00 0x01
    // csrc 2 = 0x00 0x00 0x00 0x02
    // csrc 3 = 0x00 0x00 0x00 0x03
    private val headerNoExtensions = byteArrayOf(
        0xA3.toByte(),  0xE0.toByte(),  0x10,           0x80.toByte(),
        0x00,           0x01,           0x81.toByte(),  0xCD.toByte(),
        0x00,           0x12,           0xD6.toByte(),  0x87.toByte(),
        0x00,           0x00,           0x00,           0x01,
        0x00,           0x00,           0x00,           0x02,
        0x00,           0x00,           0x00,           0x03
    )
    private val headerNoExtensionsWithPayload = ByteBuffer.wrap(headerNoExtensions.plus(byteArrayOf(
        0x01,           0x02,           0x03,           0x04
    )))
    private fun idLengthByte(id: Int, length: Int): Byte {
        return ((id shl 4) or length).toByte()
    }
    // v=2, p=1, x=1, cc=3 = 0xB3
    // m=1, pt=96 = 0xE0
    // seqnum 4224 = 0x10 0x80
    // timestamp 98765 = 0x00 0x01 0x81 0xCD
    // ssrc 1234567 = 0x00 0x12 0xD6 0x87
    // csrc 1 = 0x00 0x00 0x00 0x01
    // csrc 2 = 0x00 0x00 0x00 0x02
    // csrc 3 = 0x00 0x00 0x00 0x03
    private val headerWithExtensionBit = byteArrayOf(
        0xB3.toByte(),  0xE0.toByte(),  0x10,           0x80.toByte(),
        0x00,           0x01,           0x81.toByte(),  0xCD.toByte(),
        0x00,           0x12,           0xD6.toByte(),  0x87.toByte(),
        0x00,           0x00,           0x00,           0x01,
        0x00,           0x00,           0x00,           0x02,
        0x00,           0x00,           0x00,           0x03
    )
    private val headerWithOneByteExtensions = ByteBuffer.wrap(headerWithExtensionBit.plus(byteArrayOf(
        // Extensions
        0xBE.toByte(),                   0xDE.toByte(),  0x00,                          0x03,
        idLengthByte(1, 0),   0x42,           idLengthByte(2, 1), 0x42,
        0x42,                            0x00,           0x00,                          idLengthByte(3, 3),
        0x42,                            0x42,           0x42,                          0x42
    )))
    private val headerWithTwoByteExtensions = ByteBuffer.wrap(headerWithExtensionBit.plus(byteArrayOf(
        0x10,           0x00,           0x00,           0x03,
        0x01,           0x00,           0x02,           0x01,
        0x42.toByte(),  0x00,           0x03,           0x04,
        0x42.toByte(),  0x42.toByte(),  0x42.toByte(),  0x42.toByte()
    )))

    init {
        "parsing" {
            "a header without extensions" {
                val header = RtpHeader(headerNoExtensionsWithPayload)
                should("parse correctly") {
                    header.version shouldBe 2
                    header.hasPadding shouldBe true
                    header.hasExtension shouldBe false
                    header.csrcCount shouldBe 3
                    header.marker shouldBe true
                    header.payloadType shouldBe 96
                    header.sequenceNumber shouldBe 4224
                    header.timestamp shouldBe 98765
                    header.ssrc shouldBe 1234567
                    header.csrcs should haveSize(3)
                    header.csrcs.shouldContainInOrder(listOf<Long>(1, 2, 3))
                    header.extensions.size shouldBe 0
                    // Size should match just the header parts, not the rest of the buffer
                    header.size shouldBe 24
                }
            }
            "a header with one byte extensions" {
                val header = RtpHeader(headerWithOneByteExtensions)
                should("parse correctly") {
                    for (i in 1..3) {
                        val ext = header.extensions.getExtension(i)
                        ext shouldNotBe null
                        ext.shouldBeTypeOf<RtpOneByteHeaderExtension>()
                    }
                }
            }
            "a header with two byte extensions" {
                val header = RtpHeader(headerWithTwoByteExtensions)
                should("parse correctly") {
                    for (i in 1..3) {
                        val ext = header.extensions.getExtension(i)
                        ext shouldNotBe null
                        ext.shouldBeTypeOf<RtpTwoByteHeaderExtension>()
                    }
                }
            }
        }
        "writing" {
            //TODO: do we still want to do a test like this?
            "should update the object's value without touching the buffer" {
                val header = RtpHeader(ByteBuffer.wrap(headerNoExtensions))
                header.version = 10
                header.version shouldBe 10
            }
        }
        "serializing" {
            "a header with no extensions" {
                val buf = ByteBuffer.wrap(headerNoExtensions)
                val header = RtpHeader(buf)
                val newBuf = header.getBuffer()
                newBuf.rewind()
                buf.rewind()
                should("match the original buffer") {
                    newBuf.compareTo(buf.subBuffer(0, 24)) shouldBe 0
                }
            }
            "a header with one byte extensions" {
                val header = RtpHeader(headerWithOneByteExtensions)
                val newBuf = header.getBuffer()

                newBuf.rewind()
                headerWithOneByteExtensions.rewind()
                should("match the original buffer") {
                    newBuf.compareTo(headerWithOneByteExtensions) shouldBe 0
                }

            }
        }
        //TODO: verify header extension header is written correctly
    }
}
