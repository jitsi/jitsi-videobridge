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

import io.kotest.matchers.shouldBe
import io.kotest.core.spec.style.ShouldSpec
import org.jitsi.nlj.util.BufferPool
import org.jitsi.rtp.rtp.RtpHeader

class PaddingVideoPacketTest : ShouldSpec() {

    init {
        context("Creating a padding packet") {
            context("from a buffer that previously held other data") {
                BufferPool.getBuffer = { size ->
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
        }
    }
}
