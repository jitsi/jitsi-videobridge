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

package org.jitsi.rtp.srtcp

import io.kotlintest.IsolationMode
import io.kotlintest.should
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs

internal class SrtcpPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf
    // NOTE(brian): this ended up being a compound RTCP packet,
    // so there are multiple in here
    private val incomingEncryptedRtcpData = byteBufferOf(
        0x80, 0xC8, 0x00, 0x06, 0x75, 0x6D, 0x56, 0x40,
        0x0C, 0x24, 0x4E, 0x44, 0xF9, 0xE1, 0x4A, 0x5E,
        0x1C, 0xD1, 0xEA, 0x0B, 0xB3, 0xC5, 0x34, 0xD1,
        0xF7, 0x3D, 0x5F, 0x44, 0x2B, 0x9A, 0xD9, 0x04,
        0xB0, 0xC1, 0x48, 0xF6, 0x22, 0x5F, 0x2E, 0xFE,
        0x3A, 0xD9, 0x5B, 0xA5, 0x77, 0x52, 0xE0, 0xFD,
        0x8F, 0x46, 0x9C, 0x96, 0x29, 0xE9, 0x64, 0x1A,
        0x80, 0x00, 0x00, 0x01, 0x3C, 0xB4, 0xC8, 0xE6,
        0xB8, 0x19, 0xFB, 0xEE, 0xCE, 0xA2
    )
    init {
        "Parsing an SRTCP packet" {
            "whose length would normally require padding" {
                val unalignedSrtcpPacket = SrtcpPacket.create(incomingEncryptedRtcpData)
                should("not alter the header or payload") {
                    unalignedSrtcpPacket.getBuffer() should haveSameContentAs(incomingEncryptedRtcpData)
                }
            }
        }
    }
}