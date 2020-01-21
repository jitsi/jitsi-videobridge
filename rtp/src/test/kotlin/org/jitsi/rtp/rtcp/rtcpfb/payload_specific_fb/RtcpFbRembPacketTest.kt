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
package org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb

import io.kotlintest.IsolationMode
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.util.byteBufferOf

internal class RtcpFbRembPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    val rembPacket1Buf = byteBufferOf(
            0x8f, 0xce, 0x00, 0x05,
            0xfe, 0x93, 0x23, 0x03,
            0x00, 0x00, 0x00, 0x00,
            0x52, 0x45, 0x4d, 0x42,
            0x01, 0x0b, 0xa4, 0x5a,
            0x9e, 0x72, 0x01, 0xc1
    )

    val rembPacket2Buf = byteBufferOf(
            0x8f, 0xce, 0x00, 0x06,
            0xfe, 0x93, 0x23, 0x03,
            0x00, 0x00, 0x00, 0x00,
            0x52, 0x45, 0x4d, 0x42,
            0x02, 0x0b, 0xb4, 0x5a,
            0x9e, 0x72, 0x01, 0xc1,
            0x9e, 0x23, 0x54, 0xa3
    )

    val rembPacket3Buf = byteBufferOf(
            0x8f, 0xce, 0x00, 0x06,
            0xfe, 0x93, 0x23, 0x03,
            0x00, 0x00, 0x00, 0x00,
            0x52, 0x45, 0x4d, 0x42,
            0x02, 0xff, 0xff, 0xff,
            0x9e, 0x72, 0x01, 0xc1,
            0x9e, 0x23, 0x54, 0xa3
    )

    init {
        "Creating an RtcpFbRembPacket" {
            "from values" {
                val rembPacket = RtcpFbRembPacketBuilder(rtcpHeader = RtcpHeaderBuilder(
                        senderSsrc = 4567L
                ),
                        ssrcs = listOf(1234L),
                        brBps = 1_000_000L).build()

                should("set the values correctly") {
                    rembPacket.senderSsrc shouldBe 4567L
                    rembPacket.ssrcs shouldBe listOf(1234L)
                    rembPacket.bitrate shouldBe 1_000_000L
                }
            }
            "from values" {
                val rembPacket = RtcpFbRembPacketBuilder(rtcpHeader = RtcpHeaderBuilder(
                        senderSsrc = 4567L
                ),
                        ssrcs = listOf(1234L),
                        brBps = Long.MAX_VALUE).build()

                should("set the values correctly") {
                    rembPacket.senderSsrc shouldBe 4567L
                    rembPacket.ssrcs shouldBe listOf(1234L)
                    // the difference between Long.MAX_VALUE and 9223336852482686976L is expected and stems from the
                    // fact that we (only) have 18 bits of resolution for the mantissa.
                    rembPacket.bitrate shouldBe 9223336852482686976L
                }
            }
            "from values" {
                val rembPacket = RtcpFbRembPacketBuilder(rtcpHeader = RtcpHeaderBuilder(
                        senderSsrc = 4567L
                ),
                        ssrcs = listOf(1234L),
                        brBps = 50_000_000L).build()

                should("set the values correctly") {
                    rembPacket.senderSsrc shouldBe 4567L
                    rembPacket.ssrcs shouldBe listOf(1234L)
                    // the difference between 50_000_000L and 49999872L is expected and it stems from the fact that we
                    // (only) have 18 bits of resolution for the mantissa.
                    rembPacket.bitrate shouldBe 49999872L
                }
            }
            "creation" {
                "from a buffer of a simple REMB" {
                    val rembPacket = RtcpFbRembPacket(rembPacket1Buf.array(), rembPacket1Buf.arrayOffset(), rembPacket1Buf.limit())
                    should("read everything correctly") {
                        rembPacket.senderSsrc shouldBe 0xfe932303
                        rembPacket.numSsrc shouldBe 1
                        rembPacket.ssrcs shouldBe listOf(0x9e7201c1)
                        rembPacket.bitrate shouldBe 954728
                    }
                }
            }
            "creation" {
                "from a buffer of a REMB with two ssrcs" {
                    val rembPacket = RtcpFbRembPacket(rembPacket2Buf.array(), rembPacket2Buf.arrayOffset(), rembPacket2Buf.limit())
                    should("read everything correctly") {
                        rembPacket.senderSsrc shouldBe 0xfe932303
                        rembPacket.numSsrc shouldBe 2
                        rembPacket.ssrcs shouldBe listOf(0x9e7201c1, 0x9e2354a3)
                        rembPacket.bitrate shouldBe 971112
                    }
                }
            }
            "creation" {
                "from a buffer of a REMB signaling unbound bandwidth" {
                    val rembPacket = RtcpFbRembPacket(rembPacket3Buf.array(), rembPacket3Buf.arrayOffset(), rembPacket3Buf.limit())
                    should("read everything correctly") {
                        rembPacket.senderSsrc shouldBe 0xfe932303
                        rembPacket.numSsrc shouldBe 2
                        rembPacket.ssrcs shouldBe listOf(0x9e7201c1, 0x9e2354a3)
                        rembPacket.bitrate shouldBe Long.MAX_VALUE
                    }
                }
            }
        }
    }
}
