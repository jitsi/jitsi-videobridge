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

package org.jitsi.nlj.rtp

import io.kotlintest.IsolationMode
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import java.nio.ByteBuffer
import org.jitsi.nlj.test_utils.matchers.haveSameContentAs
import org.jitsi.rtp.rtp.RtpPacket

class RtxPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    // OSN = 57005
    val osn = org.jitsi.rtp.extensions.bytearray.byteArrayOf(0xDE, 0xAD)

    val payload = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        0x01, 0x02, 0x03, 0x04,
        0x05, 0x06, 0x07, 0x08,
        0x09, 0x0A, 0x0B, 0x0C
    )

    val header = org.jitsi.rtp.extensions.bytearray.byteArrayOf(
        // Dummy header data
        // V=2,P=false,X=false,CC=0,M=false,PT=111,SeqNum=5807
        0x80, 0x6f, 0x16, 0xaf,
        // Timestamp: 1710483662
        0x65, 0xf3, 0xe8, 0xce,
        // SSRC: 839852602
        0x32, 0x0f, 0x22, 0x3a
    )

    val rtxPacket = RtpPacket(header + osn + payload)
    val rtpPacket = RtpPacket(header + payload)

    init {
        "Getting the original sequence number" {
            should("work correctly") {
                RtxPacket.getOriginalSequenceNumber(rtxPacket) shouldBe 57005
            }
        }
        "Removing the original sequence number" {
            should("work correctly") {
                RtxPacket.removeOriginalSequenceNumber(rtxPacket)
                val newPayload =
                    ByteBuffer.wrap(rtxPacket.buffer, rtxPacket.payloadOffset, rtxPacket.payloadLength).slice()
                newPayload should haveSameContentAs(ByteBuffer.wrap(payload))
            }
        }
        "Adding an original sequence number" {
            RtxPacket.addOriginalSequenceNumber(rtpPacket)
            should("work correctly") {
                RtxPacket.getOriginalSequenceNumber(rtpPacket) shouldBe 5807
            }
        }
    }
}
