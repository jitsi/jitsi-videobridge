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
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

internal class RtpTwoByteHeaderExtensionTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
    init {
        //  0                   1                   2                   3
        //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |       0x10    |    0x00       |           length=3            |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |      ID       |     L=0       |     ID        |     L=1       |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |       data    |    0 (pad)    |       ID      |      L=4      |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |                          data                                 |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        "parsing" {
            "an extension with length 0" {
                val length0Extension = byteBufferOf(
                    0x01, 0x00
                )
                val ext = RtpTwoByteHeaderExtension.fromBuffer(length0Extension)
                should("have the right id, size and data") {
                    ext.id shouldBe 1
                    ext.data.limit() shouldBe 0
                }
                should("parse to the end of the extension") {
                    length0Extension.remaining() shouldBe 0
                }
                "and then serializing it" {
                    "by requesting a buffer" {
                        val buf = ext.getBuffer()
                        should("have written the correct amount of data") {
                            buf.limit() shouldBe 2
                        }
                        should("have written the right id, size, and data") {
                            buf.rewind()
                            // id
                            buf.get().toInt() shouldBe 1
                            // length
                            buf.get().toInt() shouldBe 0
                        }
                    }
                    "to an existing buffer" {
                        val existingBuf = ByteBuffer.allocate(8 + ext.sizeBytes)
                        existingBuf.position(8)
                        ext.serializeTo(existingBuf)
                        should("write the data to the correct place") {
                            // We can't compare the buffers directly, because the padding may
                            // be in a different place.  Just make sure the cookie is in the
                            // right spot.
                            existingBuf.subBuffer(8) should haveSameContentAs(length0Extension)
                        }
                        should("leave the buffer's position to after the written data") {
                            existingBuf.position() shouldBe existingBuf.limit()
                        }
                    }
                }
            }
            "an extension with padding" {
                val extensionWithPadding = byteBufferOf(
                    0x01, 0x03, 0x42, 0x42,
                    0x42, 0x00, 0x00, 0x00
                )
                val ext = RtpTwoByteHeaderExtension.fromBuffer(extensionWithPadding)
                should("have the right id, size and data") {
                    ext.id shouldBe 1
                    ext.data.limit() shouldBe 3
                    repeat(3) {
                        ext.data.get() shouldBe 0x42.toByte()
                    }
                }
                should("parse to the end of the extension") {
                    extensionWithPadding.position() shouldBe 5
                }
                "and then serializing it" {
                    val buf = ext.getBuffer()
                    should("have written the correct amount of data") {
                        buf.limit() shouldBe 5
                    }
                    should("have written the right id, size, and data") {
                        buf.rewind()
                        // id
                        buf.get().toInt() shouldBe 1
                        // length
                        buf.get().toInt() shouldBe 3
                        repeat(3) {
                            buf.get() shouldBe 0x42.toByte()
                        }
                    }
                }
            }
        }
    }
}
