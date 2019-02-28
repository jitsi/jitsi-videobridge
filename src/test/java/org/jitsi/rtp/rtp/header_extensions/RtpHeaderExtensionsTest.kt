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
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.byteBufferOf
import java.nio.ByteBuffer

internal class RtpHeaderExtensionsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private fun idLengthByte(id: Int, length: Int): Byte {
        return ((id shl 4) or length).toByte()
    }
    init {
        val oneByteHeaderExtBlock = byteBufferOf(
            0xBE,                            0xDE,           0x00,                          0x03,
            idLengthByte(1, 0),   0x42,           idLengthByte(2, 1), 0x42,
            0x42,                            0x00,           0x00,                          idLengthByte(3, 3),
            0x42,                            0x42,           0x42,                          0x42
        )
        val oneByteHeaderExtBlockWithPadding = byteBufferOf(
            0xBE,                            0xDE,           0x00,                          0x03,
            idLengthByte(1, 0),   0x42,           idLengthByte(2, 1), 0x42,
            0x42,                            0x00,           0x00,                          idLengthByte(3, 1),
            0x42,                            0x42,           0x00,                          0x00
        )
        val twoByteHeaderExtBlock = byteBufferOf(
            0x10, 0x00, 0x00, 0x03,
            0x01, 0x00, 0x02, 0x01,
            0x42, 0x00, 0x03, 0x04,
            0x42, 0x42, 0x42, 0x42
        )
        "parsing" {
            "a one byte header extension block" {
                val extensions = RtpHeaderExtensions.fromBuffer(oneByteHeaderExtBlock)
                should("parse all the extensions") {
                    for (i in 1..3) {
                        val ext = extensions.getExtension(i)
                        ext shouldNotBe null
                        ext.shouldBeTypeOf<RtpOneByteHeaderExtension>()
                    }
                }
                should("leave the buffer at the end of the data") {
                    oneByteHeaderExtBlock.position() shouldBe oneByteHeaderExtBlock.limit()
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
                            buf.getShort() shouldBe RtpOneByteHeaderExtension.COOKIE
                            // Length
                            buf.getShort().toInt() shouldBe 3
                        }
                    }
                    "to an existing buffer" {
                        val existingBuf = ByteBuffer.allocate(8 + oneByteHeaderExtBlock.limit())
                        existingBuf.position(8)
                        extensions.serializeTo(existingBuf)
                        should("write the data to the correct place") {
                            // We can't compare the buffers directly, because the padding may
                            // be in a different place.  Just make sure the cookie is in the
                            // right spot.
                            existingBuf.subBuffer(8, 2).getShort() shouldBe
                                    RtpOneByteHeaderExtension.COOKIE
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
                        ext.shouldBeTypeOf<RtpTwoByteHeaderExtension>()
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
                            buf.getShort() shouldBe RtpTwoByteHeaderExtension.COOKIE
                            // Length
                            buf.getShort().toInt() shouldBe 3
                        }
                    }
                    "to an existing buffer" {
                        val existingBuf = ByteBuffer.allocate(8 + oneByteHeaderExtBlock.limit())
                        existingBuf.position(8)
                        extensions.serializeTo(existingBuf)
                        should("write the data to the correct place") {
                            // We can't compare the buffers directly, because the padding may
                            // be in a different place.  Just make sure the cookie is in the
                            // right spot.
                            existingBuf.subBuffer(8, 2).getShort() shouldBe
                                    RtpTwoByteHeaderExtension.COOKIE
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
