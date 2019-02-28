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
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.plus
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal class RtcpByePacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    val rtcpByeNoReason = RtcpHeader(
        packetType = RtcpByePacket.PT,
        reportCount = 1,
        senderSsrc = 12345L,
        length = 1
    )

    private val byeReason = "Connection terminated"
    private val byeReasonData = byeReason.toByteArray(StandardCharsets.US_ASCII)
    private val rtcpByeReasonData = byteBufferOf(
        byeReasonData.size.toByte(), *byeReasonData
    )
    private val reasonSize = 1 + byeReasonData.size
    val padding = byteBufferOf(0x00, 0x00)

    val rtcpByeWithReason = RtcpHeader(
        packetType = RtcpByePacket.PT,
        hasPadding = true,
        reportCount = 1,
        senderSsrc = 12345L,
        length = 1 + ((1 + reasonSize + 3) / 4)
    ).getBuffer() + rtcpByeReasonData + padding

    init {
        "Creating an RtcpByePacket" {
            "from a buffer" {
                "of a packet without a reason" {
                    val buf = rtcpByeNoReason.getBuffer()
                    val packet = RtcpByePacket.create(buf)
                    should("parse the values correctly") {
                        packet.ssrcs shouldHaveSize 1
                        packet.ssrcs shouldContain 12345L
                        packet.reason shouldBe null
                    }
                    should("leave the buffer's position at the end of the parsed data") {
                        buf.position() shouldBe buf.limit()
                    }
                }
                "of a packet with a reason" {
                    val packet = RtcpByePacket.create(rtcpByeWithReason)
                    should("parse the values correctly") {
                        packet.ssrcs shouldHaveSize 1
                        packet.ssrcs shouldContain 12345L
                        packet.reason shouldBe byeReason
                    }
                    should("leave the buffer's position at the end of the parsed data") {
                        rtcpByeWithReason.position() shouldBe rtcpByeWithReason.limit()
                    }
                    "and then serializing it" {
                        "via requesting a new buffer" {
                            val serializedBuf = packet.getBuffer()
                            should("write all values correctly") {
                                serializedBuf should haveSameContentAs(rtcpByeWithReason)
                            }
                        }
                        "to an existing buffer" {
                            val existingBuf = ByteBuffer.allocate(8 + rtcpByeWithReason.limit())
                            existingBuf.position(8)
                            packet.serializeTo(existingBuf)
                            should("write the data to the proper place") {
                                val subBuf = existingBuf.subBuffer(8, rtcpByeWithReason.limit())
                                subBuf should haveSameContentAs(rtcpByeWithReason)
                            }
                            should("leave the buffer's position after the field it just wrote") {
                                existingBuf.position() shouldBe (8 + rtcpByeWithReason.limit())
                            }
                        }
                    }
                }
            }
        }
    }
}
