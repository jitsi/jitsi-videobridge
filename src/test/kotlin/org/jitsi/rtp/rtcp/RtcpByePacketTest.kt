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

import io.kotest.core.spec.IsolationMode
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import java.nio.charset.StandardCharsets
import org.jitsi.test_helpers.matchers.haveSameContentAs

internal class RtcpByePacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val rtcpByeNoReason = RtcpHeaderBuilder(
        packetType = RtcpByePacket.PT,
        reportCount = 1,
        senderSsrc = 12345L,
        length = 1
    ).build()

    private val byeReason = "Connection terminated"
    private val byeReasonData = byeReason.toByteArray(StandardCharsets.US_ASCII)
    private val rtcpByeReasonData = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        byeReasonData.size.toByte(), *byeReasonData.toTypedArray()
    )
    private val reasonSize = 1 + byeReasonData.size
    private val padding = byteArrayOf(0x00, 0x00)

    private val rtcpByeWithReason = RtcpHeaderBuilder(
        packetType = RtcpByePacket.PT,
        hasPadding = true,
        reportCount = 1,
        senderSsrc = 12345L,
        length = 1 + ((1 + reasonSize + 3) / 4)
    ).build() + rtcpByeReasonData + padding

    init {
        context("Creating an RtcpByePacket") {
            context("from a buffer") {
                context("of a packet without a reason") {
                    val packet = RtcpByePacket(rtcpByeNoReason, 0, rtcpByeNoReason.size)
                    should("parse the values correctly") {
                        packet.ssrcs shouldHaveSize 1
                        packet.ssrcs shouldContain 12345L
                        packet.reason shouldBe null
                    }
                }
                context("of a packet with a reason") {
                    val packet = RtcpByePacket(rtcpByeWithReason, 0, rtcpByeWithReason.size)
                    should("parse the values correctly") {
                        packet.ssrcs shouldHaveSize 1
                        packet.ssrcs shouldContain 12345L
                        packet.reason shouldBe byeReason
                    }
                    context("and then serializing it") {
                        context("via requesting a new buffer") {
                            val serializedBuf = packet.getBuffer()
                            should("write all values correctly") {
                                serializedBuf should haveSameContentAs(rtcpByeWithReason)
                            }
                        }
//                        context("to an existing buffer") {
//                            val existingBuf = ByteBuffer.allocate(8 + rtcpByeWithReason.limit())
//                            existingBuf.position(8)
//                            packet.serializeTo(existingBuf)
//                            should("write the data to the proper place") {
//                                val subBuf = existingBuf.subBuffer(8, rtcpByeWithReason.limit())
//                                subBuf should haveSameContentAs(rtcpByeWithReason)
//                            }
//                            should("leave the buffer's position after the field it just wrote") {
//                                existingBuf.position() shouldBe (8 + rtcpByeWithReason.limit())
//                            }
//                        }
                    }
                }
            }
        }
    }
}
