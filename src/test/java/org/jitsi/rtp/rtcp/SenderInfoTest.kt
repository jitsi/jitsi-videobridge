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
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import toUInt
import java.nio.ByteBuffer

internal class SenderInfoTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val expectedNtpTimestamp: Long = 0x0123456789abcdef
    private val expectedRtpTimestamp: Long = 0xFFFFFFFF
    private val expectedSendersPacketCount: Long = 0xFFFFFFFF
    private val expectedSendersOctetCount: Long = 0xFFFFFFFF
    private val senderInfoBuf = with (ByteBuffer.allocate(20)) {
        putLong(expectedNtpTimestamp)
        putInt(expectedRtpTimestamp.toUInt())
        putInt(expectedSendersPacketCount.toUInt())
        putInt(expectedSendersOctetCount.toUInt())
        this.rewind() as ByteBuffer
    }

    init {
        "creation" {
            "from a buffer" {
                val senderInfo = SenderInfo(senderInfoBuf)
                should("read all values correctly") {
                    senderInfo.ntpTimestamp shouldBe expectedNtpTimestamp
                    senderInfo.rtpTimestamp shouldBe expectedRtpTimestamp
                    senderInfo.sendersPacketCount shouldBe expectedSendersPacketCount
                    senderInfo.sendersOctetCount shouldBe expectedSendersOctetCount
                }
            }
            "from values" {
                val senderInfo = SenderInfo(
                    ntpTimestamp = expectedNtpTimestamp,
                    rtpTimestamp = expectedRtpTimestamp,
                    sendersPacketCount = expectedSendersPacketCount,
                    sendersOctetCount = expectedSendersOctetCount
                )
                should("save all values correctly") {
                    senderInfo.ntpTimestamp shouldBe expectedNtpTimestamp
                    senderInfo.compactedNtpTimestamp shouldBe 0x456789ab
                    senderInfo.rtpTimestamp shouldBe expectedRtpTimestamp
                    senderInfo.sendersPacketCount shouldBe expectedSendersPacketCount
                    senderInfo.sendersOctetCount shouldBe expectedSendersOctetCount
                }
            }
        }
        "serialization" {
            val senderInfo = SenderInfo(
                ntpTimestamp = expectedNtpTimestamp,
                rtpTimestamp = expectedRtpTimestamp,
                sendersPacketCount = expectedSendersPacketCount,
                sendersOctetCount = expectedSendersOctetCount
            )
            val newBuf = senderInfo.getBuffer()
            should("write the data correctly") {
                newBuf.rewind() shouldBe senderInfoBuf.rewind()
            }
        }
    }
}
