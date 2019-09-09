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

package org.jitsi.nlj.util

import io.kotlintest.IsolationMode
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer
import org.jitsi.nlj.test_utils.matchers.haveSameContentAs
import org.jitsi.rtp.extensions.plus
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.byteBufferOf

class RtpPacketExtensionsKtTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    val payload = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        0x01, 0x02, 0x03, 0x04,
        0x05, 0x06, 0x07, 0x08,
        0x09, 0x0A, 0x0B, 0x0C
    )

    val buf = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // Dummy header data
        // V=2,P=false,X=false,CC=0,M=false,PT=111,SeqNum=5807
        0x80, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 839852602
        0x32, 0x0f, 0x22, 0x3a
    ) + payload
    val rtpPacket = RtpPacket(buf)

    init {
        "shiftPayloadRight" {
            "when the buffer doesn't have any room" {
                rtpPacket.shiftPayloadRight(2)
                should("shift things correctly") {
                    // Header should be the same
                    rtpPacket.version shouldBe 2
                    rtpPacket.hasPadding shouldBe false
                    rtpPacket.hasExtensions shouldBe false
                    rtpPacket.csrcCount shouldBe 0
                    rtpPacket.isMarked shouldBe false
                    rtpPacket.payloadType shouldBe 111
                    rtpPacket.sequenceNumber shouldBe 5807
                    rtpPacket.timestamp shouldBe 1710483662L

                    val newPayload = ByteBuffer.wrap(rtpPacket.buffer, rtpPacket.payloadOffset, rtpPacket.payloadLength).slice()
                    newPayload should haveSameContentAs(byteBufferOf(0x01, 0x02) + ByteBuffer.wrap(payload))
                }
            }
        }
    }
}
