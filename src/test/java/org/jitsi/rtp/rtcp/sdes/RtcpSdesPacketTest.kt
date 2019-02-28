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

package org.jitsi.rtp.rtcp.sdes

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.plus
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

internal class RtcpSdesPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val sampleCnameString = "Hello, world"
    val sampleCnameSdesItem = CnameSdesItem(sampleCnameString)
    val sampleSdesChunkBuf = ByteBuffer.allocate(1024).apply {
        putInt(12345L.toInt())
        put(sampleCnameSdesItem.getBuffer())
        put(EmptySdesItem.getBuffer())
        put(0x00)
    }.flip() as ByteBuffer
    val sampleSdesChunk = SdesChunk.fromBuffer(sampleSdesChunkBuf)

    val sampleSdesPacketBuf = byteBufferOf(
        0x81, 0xCA, 0x00, 0x05
    ) + sampleSdesChunkBuf

    init {
        "Creating an RTCP SDES packet" {
            "from a buffer" {
                val pkt = RtcpSdesPacket.fromBuffer(sampleSdesPacketBuf)
                should("parse the fields correctly") {
                    pkt.sdesChunks shouldHaveSize(1)
                }
                should("have left the buffer's position in the right place") {
                    sampleSdesPacketBuf.position() shouldBe sampleSdesPacketBuf.limit()
                }
                "and then serializing it" {
                    "by requesting a buffer" {
                        val output = pkt.getBuffer()
                        should("write the fields correctly") {
                            output should haveSameContentAs(sampleSdesPacketBuf)
                        }
                    }
                    "to an existing buffer" {
                        val existingBuf = ByteBuffer.allocate(8 + pkt.sizeBytes)
                        existingBuf.position(8)
                        pkt.serializeTo(existingBuf)
                        should("write the data to the right place") {
                            existingBuf.subBuffer(8) should haveSameContentAs(sampleSdesPacketBuf)
                        }
                        should("set the buffer's position to after the written data") {
                            existingBuf.position() shouldBe existingBuf.limit()
                        }
                    }
                }
            }
            "from values" {
                val pkt = RtcpSdesPacket(sdesChunks = listOf(sampleSdesChunk))
                should("set the values correctly") {
                    pkt.sdesChunks shouldHaveSize(1)
                }
            }
        }
    }
}
