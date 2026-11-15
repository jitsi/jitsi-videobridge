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

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jitsi.nlj.util.BufferPool
import org.jitsi.rtp.Packet
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket

class PaddingVideoPacketTest : ShouldSpec() {

    init {
        context("Creating a padding packet") {
            context("from a buffer that previously held other data") {
                BufferPool.getBuffer = {
                    // A buffer with bogus CSRC count and header ext length values
                    org.jitsi.rtp.extensions.bytearray.byteArrayOf(
                        0xF7, 0xF8, 0x04, 0x54,
                        0x47, 0x35, 0x08, 0x10,
                        0x25, 0xA2, 0x71, 0x9C,
                        0x23, 0x9F, 0xCA, 0x3D
                    ) + ByteArray(1484) { 0 }
                }
                val paddingPacket = PaddingVideoPacket.create(267)
                should("have the right header length") {
                    paddingPacket.headerLength shouldBe 12
                }
                should("have the right payload length") {
                    paddingPacket.payloadLength shouldBe (267 - paddingPacket.headerLength)
                }
                should("have the padding bit set") {
                    paddingPacket.hasPadding shouldBe true
                }
                should("have the padding value set") {
                    paddingPacket.paddingSize shouldBe paddingPacket.payloadLength
                }
                should("have the version set") {
                    paddingPacket.version shouldBe RtpHeader.VERSION
                }
            }
            context("from a pool which gives buffers of exactly the size asked for") {
                BufferPool.getBuffer = { size -> ByteArray(size) }
                for (length in listOf(205, 220, 267)) {
                    val paddingPacket = PaddingVideoPacket.create(length)
                    should("leave room before and after the $length bytes of the packet") {
                        paddingPacket.length shouldBe length
                        paddingPacket.paddingSize shouldBe length - 12
                        paddingPacket.offset shouldBe RtpPacket.BYTES_TO_LEAVE_AT_START_OF_PACKET
                        val room = paddingPacket.buffer.size - paddingPacket.offset - paddingPacket.length
                        room shouldBeGreaterThanOrEqual Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
                    }
                }
                should("take a header extension without a new buffer, and without moving the padding") {
                    val paddingPacket = PaddingVideoPacket.create(267)
                    val buffer = paddingPacket.buffer
                    val payloadOffset = paddingPacket.payloadOffset

                    // The size of the transport-cc header extension.
                    paddingPacket.addHeaderExtension(5, 2)
                    paddingPacket.encodeHeaderExtensions()

                    paddingPacket.buffer shouldBeSameInstanceAs buffer
                    paddingPacket.payloadOffset shouldBe payloadOffset
                    paddingPacket.paddingSize shouldBe 267 - 12
                    val room = paddingPacket.buffer.size - paddingPacket.offset - paddingPacket.length
                    room shouldBeGreaterThanOrEqual Packet.BYTES_TO_LEAVE_AT_END_OF_PACKET
                }
            }
        }
    }
}
