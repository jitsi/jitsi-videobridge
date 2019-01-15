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
import io.kotlintest.matchers.maps.shouldContainKeys
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer

internal class RtpHeaderExtensionsTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private fun idLengthByte(id: Int, length: Int): Byte {
        return ((id shl 4) or length).toByte()
    }
    init {
        val oneByteHeaderExtBlock = ByteBuffer.wrap(byteArrayOf(
            0xBE.toByte(),                   0xDE.toByte(),  0x00,                          0x03,
            idLengthByte(1, 0),   0x42,           idLengthByte(2, 1), 0x42,
            0x42,                            0x00,           0x00,                          idLengthByte(3, 3),
            0x42,                            0x42,           0x42,                          0x42,
            // dummy payload
            0x12,                            0x34,           0x56,                          0x78
        ))
        val twoByteHeaderExtBlock = ByteBuffer.wrap(byteArrayOf(
            0x10,           0x00,           0x00,           0x03,
            0x01,           0x00,           0x02,           0x01,
            0x42.toByte(),  0x00,           0x03,           0x04,
            0x42.toByte(),  0x42.toByte(),  0x42.toByte(),  0x42.toByte(),
            // dummy payload
            0x12,           0x34,           0x56,           0x78
        ))
        "parsing" {
            "a one byte header extension block" {
                val extensions = RtpHeaderExtensions(oneByteHeaderExtBlock)
                should("parse all the extensions") {
                    for (i in 1..3) {
                        val ext = extensions.getExtension(i)
                        ext shouldNotBe null
                        ext.shouldBeTypeOf<RtpOneByteHeaderExtension>()
                    }
                }
                "and then serializing it" {
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
            }
            "a two byte header extension block" {
                val extensions = RtpHeaderExtensions(twoByteHeaderExtBlock)
                should("parse all the extensions") {
                    for (i in 1..3) {
                        val ext = extensions.getExtension(i)
                        ext shouldNotBe null
                        ext.shouldBeTypeOf<RtpTwoByteHeaderExtension>()
                    }
                }
                "and then serializing it" {
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
            }
        }
    }
}
