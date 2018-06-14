/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.rtp

import java.nio.ByteBuffer

fun main(args: Array<String>) {
//    val b: Byte = 0b01001111
    // << 1 = 0b11111110
    // >> 4 = 0b00001111
//    for (i in 0..7) {
//        println(b.getBit(i))
//    }

//    println(b.getBits(4, 6))

    // v=2, p=1, x=1, cc=3 = 0xB3
    // m=1, pt=96 = 0xE0
    // seqnum 4224 = 0x10 0x80
    // timestamp 98765 = 0x00 0x01 0x81 0xCD
    // ssrc 1234567 = 0x00 0x12 0xD6 0x87
    // csrc 1 = 0x00 0x00 0x00 0x01
    // csrc 2 = 0x00 0x00 0x00 0x02
    // csrc 3 = 0x00 0x00 0x00 0x03


    val packetData = ByteBuffer.wrap(byteArrayOf(
        0xB3.toByte(),  0xE0.toByte(),  0x10,           0x80.toByte(),
        0x00,           0x01,           0x81.toByte(),  0xCD.toByte(),
        0x00,           0x12,           0xD6.toByte(),  0x87.toByte(),
        0x00,           0x00,           0x00,           0x01,
        0x00,           0x00,           0x00,           0x02,
        0x00,           0x00,           0x00,           0x03
    ))

    val packet = RtpPacket(packetData)

    println("version: ${packet.version}")
    println("has padding: ${packet.hasPadding}")
    println("has extension: ${packet.hasExtension}")
    println("csrc count: ${packet.csrcCount}")
    println("marker: ${packet.marker}")
    println("pt: ${packet.payloadType}")
    println("seqnum: ${packet.sequenceNumber}")
    println("timestamp: ${packet.timestamp}")
    println("ssrc: ${packet.ssrc}")
    println("csrcs: ${packet.csrcs}")


}
