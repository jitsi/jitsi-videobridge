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
import io.kotlintest.matchers.beInstanceOf
import io.kotlintest.matchers.beOfType
import io.kotlintest.matchers.haveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.util.byteBufferOf
import java.nio.ByteBuffer

internal class RtcpIteratorTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val compoundPacketBuf = ByteBuffer.wrap(byteArrayOf(
        0x81.toByte(), 0xC9.toByte(), 0x00.toByte(), 0x07.toByte(),
        0x50.toByte(), 0x63.toByte(), 0x13.toByte(), 0xDE.toByte(),
        0x48.toByte(), 0xCA.toByte(), 0xF9.toByte(), 0xD1.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x10.toByte(), 0x8C.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x68.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x81.toByte(), 0xCD.toByte(), 0x00.toByte(), 0x03.toByte(),
        0x50.toByte(), 0x63.toByte(), 0x13.toByte(), 0xDE.toByte(),
        0x48.toByte(), 0xCA.toByte(), 0xF9.toByte(), 0xD1.toByte(),
        0x10.toByte(), 0x8D.toByte(), 0x00.toByte(), 0x00.toByte()
    ))

    // Compound packet which has an RTCPFB packet with multiple
    // FCI blocks
    private val compoundPacketBuf2 = byteBufferOf(
        0x81, 0xC9, 0x00, 0x07,
        0xA8, 0x9C, 0x2A, 0xC5,
        0x8E, 0xE5, 0x0F, 0xAE,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x03, 0x17,
        0x00, 0x00, 0x00, 0xAC,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x81, 0xCD, 0x00, 0x04,
        0xA8, 0x9C, 0x2A, 0xC5,
        0x8E, 0xE5, 0x0F, 0xAE,
        0x03, 0x02, 0xFF, 0xFF,
        0x03, 0x13, 0x00, 0x0F
    )

    private val singlePacketBuf = ByteBuffer.wrap(byteArrayOf(
        0x80.toByte(), 0xC8.toByte(), 0x00.toByte(), 0x06.toByte(),
        0x36.toByte(), 0x13.toByte(), 0xF6.toByte(), 0xF6.toByte(),
        0xDF.toByte(), 0x21.toByte(), 0x7D.toByte(), 0xF0.toByte(),
        0x7F.toByte(), 0x5E.toByte(), 0x41.toByte(), 0xD5.toByte(),
        0x90.toByte(), 0x31.toByte(), 0x59.toByte(), 0xF3.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x08.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x01.toByte(), 0x14.toByte()
    ))

    init {
        "when parsing a compound packet" {
            val iter = RtcpIterator(compoundPacketBuf)
            "next" {
                should("get the next packet") {
                    iter.next() should beInstanceOf<RtcpRrPacket>()
                    iter.next() should beInstanceOf<RtcpFbPacket>()
                    shouldThrow<Exception> {
                        iter.next()
                    }
                }
            }
            "hasNext" {
                should("correctly report if there is another packet") {
                    iter.hasNext() shouldBe true
                    iter.next()
                    iter.hasNext() shouldBe true
                    iter.next()
                    iter.hasNext() shouldBe false
                }
            }
            "getAll" {
                should("return all packets") {
                    val pkts = iter.getAll()
                    pkts should haveSize(2)
                    iter.hasNext() shouldBe false
                }

            }
        }
        //TODO: this current fails because we don't currently parse
        // multiple FCI blocks and it throws for not parsing to
        // the end of the packet
        "!compound packet 2" {
            val iter = RtcpIterator(compoundPacketBuf2)

            val packets = iter.getAll()
            println(packets)
        }
        "when parsing a packet with only a single RTCP packet" {
            val iter = RtcpIterator(singlePacketBuf)
            val pkt = iter.next()
            pkt should beOfType<RtcpSrPacket>()
            iter.hasNext() shouldBe false
        }
    }
}
