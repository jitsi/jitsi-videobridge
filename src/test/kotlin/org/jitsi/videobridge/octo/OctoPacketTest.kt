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
package org.jitsi.videobridge.octo

import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.ShouldSpec
import org.jitsi.utils.MediaType
import org.jitsi.rtp.extensions.bytearray.byteArrayOf
import java.lang.IllegalArgumentException

class OctoPacketTest : ShouldSpec() {
    init {
        "parsing an OctoPacket" {
            should("parse a valid packet correctly") {
                OctoPacket.readConferenceId(octoHeader, 0, octoHeader.size) shouldBe 1234
                OctoPacket.readEndpointId(octoHeader, 0, octoHeader.size) shouldBe "abcdabcd"
                OctoPacket.readMediaType(octoHeader, 0, octoHeader.size) shouldBe MediaType.VIDEO
            }
            should("fail when the length is insufficient") {
                shouldThrow<IllegalArgumentException> {
                    OctoPacket.readEndpointId(byteArrayOf(0, 0, 0, 0, 0), 0, 0)
                }
            }
        }
        "creating Octo headers" {
            should("create a valid packet correctly") {
                val octoHeader = ByteArray(12)
                OctoPacket.writeHeaders(octoHeader, 0, MediaType.AUDIO, 111222, "1234abcd")
                OctoPacket.readConferenceId(octoHeader, 0, octoHeader.size) shouldBe 111222
                OctoPacket.readEndpointId(octoHeader, 0, octoHeader.size) shouldBe "1234abcd"
                OctoPacket.readMediaType(octoHeader, 0, octoHeader.size) shouldBe MediaType.AUDIO
            }
            should("fail when the length is insufficient") {
                val octoHeader = ByteArray(11)
                shouldThrow<IllegalArgumentException> {
                    OctoPacket.writeHeaders(octoHeader, 0, MediaType.AUDIO, 111222, "1234abcd")
                }
            }
        }
    }

    companion object {
        val octoHeader: ByteArray = byteArrayOf(
            // Conference ID = 1234 (0x4d2)
            0x00, 0x00, 0x04, 0xd2,
            // Endpoint ID = "abcdabcd"
            0xab, 0xcd, 0xab, 0xcd,
            // MediaType = Video (1)
            0x40, 0x00, 0x00, 0x00)
    }
}
