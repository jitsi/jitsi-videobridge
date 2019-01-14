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

import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

internal class RtcpPacketTest : ShouldSpec() {
    init {
        "blah" {
            // Data from a real call, shouldn't throw any exceptions
            //TODO: turn this into more of a real test
            val buf = ByteBuffer.wrap(byteArrayOf(
                0x8F.toByte(), 0xCD.toByte(), 0x00.toByte(), 0x07.toByte(),
                0xC5.toByte(), 0xD3.toByte(), 0x3B.toByte(), 0x0D.toByte(),
                0x48.toByte(), 0xCA.toByte(), 0xF9.toByte(), 0xD1.toByte(),
                0x02.toByte(), 0x86.toByte(), 0x00.toByte(), 0x0B.toByte(),
                0x12.toByte(), 0x5A.toByte(), 0x3F.toByte(), 0x35.toByte(),
                0x8E.toByte(), 0xD8.toByte(), 0xDC.toByte(), 0x00.toByte(),
                0x14.toByte(), 0x5C.toByte(), 0x00.toByte(), 0x14.toByte(),
                0x18.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
            ))

            val packet = RtcpPacket.fromBuffer(buf)
            println(packet::class)
            println(packet.getBuffer().toHex())
            val iter = RtcpIterator(packet.getBuffer())
            iter.getAll()
        }
    }
}
