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

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.collections.shouldContain
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.compareToFromBeginning
import org.jitsi.rtp.extensions.toHex
import java.nio.ByteBuffer


internal class RtcpSdesPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    init {
        "Creating an RTCP SDES packet" {
            "from a buffer" {
                val sdesPacketBuf = ByteBuffer.wrap(byteArrayOf(
                    0x81.toByte(), 0xCA.toByte(), 0x00.toByte(), 0x06.toByte(),
                    // ssrc 3828749302
                    0xE4.toByte(), 0x36.toByte(), 0x13.toByte(), 0xF6.toByte(),
                    // cname, length 16
                    0x01.toByte(), 0x10.toByte(), 0x52.toByte(), 0x2B.toByte(),
                    0x2F.toByte(), 0x7A.toByte(), 0x76.toByte(), 0x38.toByte(),
                    0x4A.toByte(), 0x54.toByte(), 0x4C.toByte(), 0x32.toByte(),
                    0x59.toByte(), 0x51.toByte(), 0x51.toByte(), 0x6C.toByte(),
                    0x47.toByte(), 0x78.toByte(), 0x00.toByte(), 0x00.toByte()
                ))
                val origBuf = sdesPacketBuf.clone()
                val pkt = RtcpSdesPacket(sdesPacketBuf)
                should("parse the fields correctly") {
                    pkt.chunks.size shouldBe 1
                }
                should("have left the buffer's position in the right place") {
                    sdesPacketBuf.position() shouldBe 0
                }
                "and then serializing it" {
                    val output = pkt.getBuffer()
                    should("write the fields correctly") {
                        origBuf.compareToFromBeginning(output) shouldBe 0
                    }
                }
                "and then adding another chunk" {
                    val newChunk = SdesChunk(12345L, mutableListOf(
                        CnameSdesItem("otheruser@domain.com")
                    ))
                    pkt.chunks.add(newChunk)
                    "and then serializing it" {
                        val out = pkt.getBuffer()
                        should("write the correct data") {
                            // We already know parsing works, so start by parsing the new
                            // buffer and make sure that succeeds, then we can test the fields
                            // in the parsed packet
                            val newPacket = RtcpSdesPacket(out)
                            newPacket.chunks.size shouldBe 2
                            newPacket.chunks shouldContain newChunk
                        }
                    }
                }
            }
        }
    }
}
