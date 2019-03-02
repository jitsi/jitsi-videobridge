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

package org.jitsi.rtp.rtp.header_extensions

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.byteBufferOf
import java.nio.ByteBuffer

internal class RtpHeaderExtensionsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    companion object {
        private fun idLengthByte(id: Int, length: Int): Byte {
            return ((id shl 4) or length).toByte()
        }
        val oneByteHeaderExtBlockWithPaddingBetweenElements = byteBufferOf(
            0xBE,                            0xDE,           0x00,                          0x03,
            idLengthByte(1, 0),   0x42,           idLengthByte(2, 1), 0x42,
            0x42,                            0x00,           0x00,                          idLengthByte(3, 3),
            0x42,                            0x42,           0x42,                          0x42
        )
        val oneByteHeaderExtBlockWithPaddingAtTheEnd = byteBufferOf(
            0xBE,                            0xDE,                          0x00,                          0x03,
            idLengthByte(1, 0),   0x42,                          idLengthByte(2, 1), 0x42,
            0x42,                            idLengthByte(3, 3), 0x42,                          0x42,
            0x42,                            0x42,                          0x00,                          0x00
        )
        val twoByteHeaderExtBlock = byteBufferOf(
            0x10, 0x00, 0x00, 0x0A,
            0x01, 0x00, 0x02, 0x01,
            0x42, 0x00, 0x03, 0x20,
            0x42, 0x42, 0x42, 0x42,
            0x42, 0x42, 0x42, 0x42,
            0x42, 0x42, 0x42, 0x42,
            0x42, 0x42, 0x42, 0x42,
            0x42, 0x42, 0x42, 0x42,
            0x42, 0x42, 0x42, 0x42,
            0x42, 0x42, 0x42, 0x42,
            0x42, 0x42, 0x42, 0x42
        )
    }

    init {
        "parsing" {
            "a one byte header extension block" {
                val extensions = RtpHeaderExtensions.fromBuffer(oneByteHeaderExtBlockWithPaddingBetweenElements)
                should("parse all the extensions") {
                    for (i in 1..3) {
                        val ext = extensions.getExtension(i)
                        ext shouldNotBe null
                        ext as UnparsedHeaderExtension
                        val data = ext.data
                        while (data.remaining() > 0) {
                            data.get() shouldBe 0x42.toByte()
                        }
                    }
                }
                should("leave the buffer at the end of the data") {
                    oneByteHeaderExtBlockWithPaddingBetweenElements.position() shouldBe oneByteHeaderExtBlockWithPaddingBetweenElements.limit()
                }
                "and then serializing it" {
                    "by requesting a buffer" {
                        val buf = extensions.getBuffer()
                        should("have made sure it was word aligned") {
                            buf.limit() % 4 shouldBe 0
                        }
                        should("write it correctly") {
                            buf.rewind()
                            // Cookie
                            buf.getShort() shouldBe RtpHeaderExtensions.ONE_BYTE_COOKIE
                            // Length
                            buf.getShort().toInt() shouldBe 3
                        }
                    }
                    "to an existing buffer" {
                        val existingBuf = ByteBuffer.allocate(8 + oneByteHeaderExtBlockWithPaddingBetweenElements.limit())
                        existingBuf.position(8)
                        extensions.serializeTo(existingBuf)
                        should("write the data to the correct place") {
                            // We can't compare the buffers directly, because the padding may
                            // be in a different place.  Just make sure the cookie is in the
                            // right spot.
                            existingBuf.subBuffer(8, 2).getShort() shouldBe
                                    RtpHeaderExtensions.ONE_BYTE_COOKIE
                        }
                        should("leave the buffer's position to after the written data") {
                            existingBuf.position() shouldBe existingBuf.limit()
                        }
                    }
                }
            }
            "a two byte header extension block" {
                val extensions = RtpHeaderExtensions.fromBuffer(twoByteHeaderExtBlock)
                should("parse all the extensions") {
                    for (i in 1..3) {
                        val ext = extensions.getExtension(i)
                        ext shouldNotBe null
                        ext as UnparsedHeaderExtension
                        val data = ext.data
                        while (data.remaining() > 0) {
                            data.get() shouldBe 0x42.toByte()
                        }
                    }
                }
                "and then serializing it" {
                    "by requesting a buffer" {
                        val buf = extensions.getBuffer()
                        should("have made sure it was word aligned") {
                            buf.limit() % 4 shouldBe 0
                        }
                        should("write it correctly") {
                            // Cookie
                            buf.getShort() shouldBe RtpHeaderExtensions.TWO_BYTE_COOKIE
                            // Length
                            buf.getShort().toInt() shouldBe 10
                        }
                    }
                    "to an existing buffer" {
                        val existingBuf = ByteBuffer.allocate(8 + twoByteHeaderExtBlock.limit())
                        existingBuf.position(8)
                        extensions.serializeTo(existingBuf)
                        should("write the data to the correct place") {
                            // We can't compare the buffers directly, because the padding may
                            // be in a different place.  Just make sure the cookie is in the
                            // right spot.
                            existingBuf.subBuffer(8, 2).getShort() shouldBe
                                    RtpHeaderExtensions.TWO_BYTE_COOKIE
                        }
                        should("leave the buffer's position to after the written data") {
                            existingBuf.position() shouldBe existingBuf.limit()

                        }
                    }
                }
            }
        }
    }
}
