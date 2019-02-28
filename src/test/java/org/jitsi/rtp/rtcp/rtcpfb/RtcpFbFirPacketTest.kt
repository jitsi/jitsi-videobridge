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

package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.IsolationMode
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

internal class RtcpFbFirPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val sampleHeader =
        RtcpHeader(length = 4, packetType = PayloadSpecificFbPacket.PT, reportCount = RtcpFbFirPacket.FMT)
    val sampleRtcpFbFirPacketBuf = ByteBuffer.allocate(RtcpFbFirPacket.SIZE_BYTES).apply {
        put(sampleHeader.getBuffer())
        putInt(0) // Unused media source ssrc
        // FIR FCI
        // SSRC
        putInt(12345)
        // Seq num, reserved bytes
        put(1.toByte()); put(0x00); put(0x00); put(0x00)
    }.flip() as ByteBuffer

    init {
        "Creating an RtcpFbFirPacket" {
            "from a buffer" {
                val rtcpFbFirPacket = RtcpFbFirPacket.fromBuffer(sampleRtcpFbFirPacketBuf)
                should("parse the values correctly") {
                    rtcpFbFirPacket.seqNum shouldBe 1
                    rtcpFbFirPacket.firSsrc shouldBe 12345L
                }
                should("leave the buffer's position after the parsed data") {
                    sampleRtcpFbFirPacketBuf.position() shouldBe sampleRtcpFbFirPacketBuf.limit()
                }
            }
            "from values" {
                val rtcpFbFirPacket = RtcpFbFirPacket.fromValues(
                    header = RtcpHeader(length = 4),
                    firSsrc = 12345L, commandSeqNum = 1
                )
                should("set the values correctly") {
                    rtcpFbFirPacket.seqNum shouldBe 1
                    rtcpFbFirPacket.firSsrc shouldBe 12345L
                }
                "and then serializing it" {
                    "by requesting a buffer" {
                        val buf = rtcpFbFirPacket.getBuffer()
                        should("serialize the data correctly") {
                            buf should haveSameContentAs(sampleRtcpFbFirPacketBuf)
                        }
                    }
                    "to an existing buffer" {
                        val existingBuf = ByteBuffer.allocate(RtcpFbFirPacket.SIZE_BYTES + 8)
                        existingBuf.position(8)
                        rtcpFbFirPacket.serializeTo(existingBuf)
                        should("write the data to the correct place") {
                            existingBuf.subBuffer(8) should haveSameContentAs(sampleRtcpFbFirPacketBuf)
                        }
                        should("leave the buffer's position after the written data") {
                            existingBuf.position() shouldBe existingBuf.limit()
                        }
                    }
                }
            }
        }
    }
}