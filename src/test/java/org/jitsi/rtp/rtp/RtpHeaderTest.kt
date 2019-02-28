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

package org.jitsi.rtp.rtp

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.collections.shouldContainInOrder
import io.kotlintest.matchers.haveSize
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtp.header_extensions.RtpHeaderExtension
import org.jitsi.rtp.rtp.header_extensions.RtpOneByteHeaderExtension
import org.jitsi.rtp.rtp.header_extensions.RtpTwoByteHeaderExtension
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs
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
    //Note that other methods of padding are possible (in between the fields themselves).  The example
    // uses this method of padding (at the end) because that's how we serialize, so we can use it to
    // verify serialization works correctly
    private val headerWithOneByteExtensions = ByteBuffer.wrap(headerWithExtensionBit.plus(byteArrayOf(
        // Extensions
        0xBE.toByte(),                   0xDE.toByte(),                 0x00,                          0x03,
        idLengthByte(1, 0),   0x42,                          idLengthByte(2, 1), 0x42,
        0x42,                            idLengthByte(3, 3), 0x42,                          0x42,
        0x42,                            0x42,                          0x00,                          0x00

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
                val header = RtpHeader.fromBuffer(headerNoExtensionsWithPayload)
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
                    // Size should match just the header parts, not the rest of the buffer
                    header.sizeBytes shouldBe 24
                }
            }
            "a header with one byte extensions" {
                val header = RtpHeader.fromBuffer(headerWithOneByteExtensions)
                should("parse correctly") {
                    for (i in 1..3) {
                        val ext = header.getExtension(i)
                        ext shouldNotBe null
                        ext as RtpHeaderExtension
                        ext.shouldBeTypeOf<RtpOneByteHeaderExtension>()
                    }
                    val ext1 = header.getExtension(1)
                    ext1 as RtpHeaderExtension
                    ext1.id shouldBe 1
                    ext1.data should haveSameContentAs(byteBufferOf(0x42))

                    val ext2 = header.getExtension(2)
                    ext2 as RtpHeaderExtension
                    ext2.id shouldBe 2
                    ext2.data should haveSameContentAs(byteBufferOf(0x42, 0x42))

                    val ext3 = header.getExtension(3)
                    ext3 as RtpHeaderExtension
                    ext3.id shouldBe 3
                    ext3.data should haveSameContentAs(byteBufferOf(0x42, 0x42, 0x42, 0x42))
                }
            }
            "a header with two byte extensions" {
                val header = RtpHeader.fromBuffer(headerWithTwoByteExtensions)
                should("parse correctly") {
                    for (i in 1..3) {
                        val ext = header.getExtension(i)
                        ext shouldNotBe null
                        ext.shouldBeTypeOf<RtpTwoByteHeaderExtension>()
                    }
                }
            }
        }
        "serializing" {
            "a header with no extensions" {
                val buf = ByteBuffer.wrap(headerNoExtensions)
                val header = RtpHeader.fromBuffer(buf)
                val newBuf = header.getBuffer()
                newBuf.rewind()
                buf.rewind()
                should("match the original buffer") {
                    newBuf.compareTo(buf.subBuffer(0, 24)) shouldBe 0
                }
            }
            "a header with one byte extensions" {
                val header = RtpHeader.fromBuffer(headerWithOneByteExtensions)
                "by requesting a buffer" {
                    val newBuf = header.getBuffer()
                    should("match the original buffer") {
                        newBuf should haveSameContentAs(headerWithOneByteExtensions)
                    }
                }
            }
        }
        //TODO: verify header extension header is written correctly
    }
}
