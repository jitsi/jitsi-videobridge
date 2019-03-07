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
import io.kotlintest.shouldBe
import io.kotlintest.specs.BehaviorSpec
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.rtp.header_extensions.UnparsedHeaderExtension
import org.jitsi.rtp.util.byteBufferOf
import java.nio.ByteBuffer

internal class RtpPacketTest : BehaviorSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    fun createRtpPacket(
        header: RtpHeader = RtpHeader(),
        payloadLength: Int = 0): RtpPacket {
        val backingBuffer = ByteBuffer.allocate(1500)
        backingBuffer.position(header.sizeBytes)
        repeat (payloadLength) {
            backingBuffer.put(0x42)
        }
        backingBuffer.flip()

        return RtpPacket(header, backingBuffer)
    }

    init {
        given("an RTP packet") {
            val rtpPacket = createRtpPacket(payloadLength = 100)
            `when`("we get its buffer") {
                val buf = rtpPacket.getBuffer()
                and("then modify its header") {
                    rtpPacket.header.ssrc = 123
                    and("get its buffer again") {
                        val newBuf = rtpPacket.getBuffer()
                        //TODO: verify all fields and payload
                        then("the change should be reflected") {
                            RtpHeader.getSsrc(newBuf) shouldBe 123
                        }
                    }
                }
                and("then modify its header in a way that increases its size") {
                    rtpPacket.header.addExtension(1, UnparsedHeaderExtension(1, byteBufferOf(0x01, 0x02, 0x03)))
                    and("get its buffer again") {
                        val newBuf = rtpPacket.getBuffer()
                        then("the changes should be reflected") {
                            rtpPacket.payload.limit() shouldBe 100
                        }
                    }
                }
            }
        }
    }
}