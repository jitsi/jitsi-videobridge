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

package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

internal class RtcpFbPliPacketTest : ShouldSpec() {

    init {
        "Parsing a PLI packet from a buffer" {
            val buf = ByteBuffer.wrap(byteArrayOf(
                0x81.toByte(), 0xCE.toByte(), 0x00.toByte(), 0x02.toByte(),
                0x70.toByte(), 0x2D.toByte(), 0x93.toByte(), 0xE4.toByte(),
                0xC1.toByte(), 0x2D.toByte(), 0xDB.toByte(), 0x96.toByte()
            ))

            val packet = RtcpFbPacket.fromBuffer(buf)
            println(packet.getBuffer().toHex())
        }
    }
}
