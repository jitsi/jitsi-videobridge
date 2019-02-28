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

package org.jitsi.rtp.rtp

import io.kotlintest.IsolationMode
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs

internal class RtxPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val dummyRtpPayload = byteBufferOf(
        0x42, 0x42, 0x42, 0x42,
        0x42, 0x42, 0x42, 0x42
    )

    private val rtpPacket = RtpPacket(
        RtpHeader(
            payloadType = 100,
            ssrc = 12345L,
            sequenceNumber = 10
        ),
        _payload = dummyRtpPayload
    )

    private val rtxPacketBuf = RtxPacket.fromRtpPacket(rtpPacket).getBuffer()

    init {
        "an RTX packet" {
            "constructed from a buffer" {
                val rtxPacket = RtxPacket.fromBuffer(rtxPacketBuf)
                should("parse the original sequence number correctly") {
                    rtxPacket.originalSequenceNumber shouldBe 10
                }
                "and then converted to an rtp packet" {
                    val rtpPacket = rtxPacket as RtpPacket
                    rtpPacket.header.sequenceNumber = rtxPacket.originalSequenceNumber
                    rtpPacket.header.payloadType = 100
                    rtpPacket.header.ssrc = 123
                    should("serialize the new data correctly") {
                        val buf = rtpPacket.getBuffer()
                        val parsedRtpPacket = RtpPacket.fromBuffer(buf)
                        parsedRtpPacket.header.sequenceNumber shouldBe rtxPacket.originalSequenceNumber
                        parsedRtpPacket.header.payloadType shouldBe 100
                        parsedRtpPacket.header.ssrc shouldBe 123L
                    }
                }
            }
            "constructed from an existing RTP packet" {
                // Specifically create the RTP packet from a buffer so it has a backing
                // buffer
                val rtxPacket = RtxPacket.fromRtpPacket(rtpPacket)
                rtxPacket.header.payloadType = 96
                rtxPacket.header.sequenceNumber = 1
                rtxPacket.header.ssrc = 98765L
                should("not modify the original packet") {
                    rtpPacket.header.payloadType shouldBe 100
                    rtpPacket.header.ssrc shouldBe 12345L
                    rtpPacket.header.sequenceNumber shouldBe 10
                    rtpPacket.payload should haveSameContentAs(dummyRtpPayload)
                }
                should("set the original sequence number") {
                    rtxPacket.originalSequenceNumber shouldBe 10
                }
                should("serialize the original sequence number correctly") {
                    val buf = rtxPacket.getBuffer()
                    buf.getShort(rtxPacket.header.sizeBytes) shouldBe 10.toShort()
                }
            }
            "constructed from an RTP packet after we find out it's actually RTX" {
                val rtpPacket = RtpPacket.fromBuffer(rtxPacketBuf)
                val rtxPacket = RtxPacket.parseAsRtx(rtpPacket)
                should("parse the values correctly") {
                    rtxPacket.originalSequenceNumber shouldBe 10
                    rtxPacket.payload should haveSameContentAs(dummyRtpPayload)
                }
            }
        }
    }
}