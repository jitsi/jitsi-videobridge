/*
 * Copyright @ 2026 - present 8x8, Inc.
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

package org.jitsi.rtp.rtp.header_extensions

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jitsi.rtp.extensions.bytearray.byteArrayOf
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.rtp.RtpPacket

class AudioLevelHeaderExtensionTest : ShouldSpec() {
    /** V=2, no extension, PT=111, and a 20-byte payload. */
    private fun packetWithoutExtensions(): RtpPacket {
        val header = byteArrayOf(
            0x80, 0x6f, 0x16, 0xaf,
            0x65, 0xf3, 0xe8, 0xce,
            0x48, 0x0f, 0x22, 0x3a,
        )
        val payload = ByteArray(20) { 0x42 }
        return RtpPacket(header + payload)
    }

    init {
        context("Adding an audio level extension to a packet that has none") {
            should("round-trip the level and VAD flag through encoding") {
                val packet = packetWithoutExtensions()
                val ext = packet.addHeaderExtension(1, AudioLevelHeaderExtension.DATA_SIZE_BYTES)
                AudioLevelHeaderExtension.setAudioLevel(ext, 23, true)
                packet.encodeHeaderExtensions()

                // Re-parse from the wire bytes to prove the encoding, not just the pending object.
                val reparsed = RtpPacket(packet.buffer, packet.offset, packet.length)
                val parsedExt = reparsed.getHeaderExtension(1)
                parsedExt shouldNotBe null
                AudioLevelHeaderExtension.getAudioLevel(parsedExt!!) shouldBe 23
                AudioLevelHeaderExtension.getVad(parsedExt) shouldBe true
                reparsed.payloadLength shouldBe 20
                reparsed.headerLength shouldBe RtpHeader.FIXED_HEADER_SIZE_BYTES + 4 + 4
            }
            should("encode the extremes without the VAD bit leaking into the level") {
                val packet = packetWithoutExtensions()
                val ext = packet.addHeaderExtension(3, AudioLevelHeaderExtension.DATA_SIZE_BYTES)
                AudioLevelHeaderExtension.setAudioLevel(ext, AudioLevelHeaderExtension.MUTED_LEVEL, false)
                packet.encodeHeaderExtensions()

                val parsedExt = RtpPacket(packet.buffer, packet.offset, packet.length).getHeaderExtension(3)!!
                AudioLevelHeaderExtension.getAudioLevel(parsedExt) shouldBe 127
                AudioLevelHeaderExtension.getVad(parsedExt) shouldBe false

                AudioLevelHeaderExtension.setAudioLevel(ext, 0, true)
                AudioLevelHeaderExtension.getAudioLevel(ext) shouldBe 0
                AudioLevelHeaderExtension.getVad(ext) shouldBe true
            }
            should("use the two-byte header form for an extension ID of 15 or more") {
                val packet = packetWithoutExtensions()
                val ext = packet.addHeaderExtension(15, AudioLevelHeaderExtension.DATA_SIZE_BYTES)
                AudioLevelHeaderExtension.setAudioLevel(ext, 42, true)
                packet.encodeHeaderExtensions()

                val reparsed = RtpPacket(packet.buffer, packet.offset, packet.length)
                val parsedExt = reparsed.getHeaderExtension(15)
                parsedExt shouldNotBe null
                AudioLevelHeaderExtension.getAudioLevel(parsedExt!!) shouldBe 42
                AudioLevelHeaderExtension.getVad(parsedExt) shouldBe true
                reparsed.payloadLength shouldBe 20
            }
            should("add, clamp and encode it in one step with addToPacket") {
                for ((given, expected) in listOf(23 to 23, 200 to 127, -5 to 0)) {
                    val packet = packetWithoutExtensions()
                    AudioLevelHeaderExtension.addToPacket(packet, 1, given, true)

                    // Already encoded into the bytes: a packet re-parsed from them (as a clone would be) carries it.
                    val parsedExt = RtpPacket(packet.buffer, packet.offset, packet.length).getHeaderExtension(1)
                    parsedExt shouldNotBe null
                    AudioLevelHeaderExtension.getAudioLevel(parsedExt!!) shouldBe expected
                    AudioLevelHeaderExtension.getVad(parsedExt) shouldBe true
                    packet.clone().getHeaderExtension(1)?.let { AudioLevelHeaderExtension.getAudioLevel(it) } shouldBe
                        expected
                }
            }
            should("reject an out-of-range level") {
                val packet = packetWithoutExtensions()
                val ext = packet.addHeaderExtension(1, AudioLevelHeaderExtension.DATA_SIZE_BYTES)
                shouldThrow<IllegalArgumentException> { AudioLevelHeaderExtension.setAudioLevel(ext, 128, false) }
                shouldThrow<IllegalArgumentException> { AudioLevelHeaderExtension.setAudioLevel(ext, -1, false) }
            }
        }
    }
}
