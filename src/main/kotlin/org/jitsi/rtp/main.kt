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
//package org.jitsi.rtp
//
//import java.lang.Thread.sleep
//import java.nio.ByteBuffer
//import kotlin.system.measureTimeMillis
//
//fun perfTest(packetData: ByteBuffer, packetCreator: (ByteBuffer) -> RtpPacket): Long {
//    return measureTimeMillis {
//        repeat(1_000_000) {
//            val packet = packetCreator(packetData)
//            packetData.rewind()
//        }
//    }
//
//}
//
//fun main(args: Array<String>) {
//    // v=2, p=1, x=1, cc=3 = 0xB3
//    // m=1, pt=96 = 0xE0
//    // seqnum 4224 = 0x10 0x80
//    // timestamp 98765 = 0x00 0x01 0x81 0xCD
//    // ssrc 1234567 = 0x00 0x12 0xD6 0x87
//    // csrc 1 = 0x00 0x00 0x00 0x01
//    // csrc 2 = 0x00 0x00 0x00 0x02
//    // csrc 3 = 0x00 0x00 0x00 0x03
//    val packetData = ByteBuffer.wrap(byteArrayOf(
//        // Header
//        0xB3.toByte(),  0xE0.toByte(),  0x10,           0x80.toByte(),
//        0x00,           0x01,           0x81.toByte(),  0xCD.toByte(),
//        0x00,           0x12,           0xD6.toByte(),  0x87.toByte(),
//        0x00,           0x00,           0x00,           0x01,
//        0x00,           0x00,           0x00,           0x02,
//        0x00,           0x00,           0x00,           0x03,
//        // Extensions (one byte header)
//        0xBE.toByte(),  0xDE.toByte(),  0x00,           0x03,
//        0x10.toByte(),  0x42,           0x21,           0x42,
//        0x42,           0x00,           0x00,           0x32,
//        0x42,           0x42,           0x42,           0x42,
//        // Payload
//        0x00,           0x00,           0x00,           0x00
//    ))
//
//    val bbPacket = BitBufferRtpPacket.fromBuffer(packetData.asReadOnlyBuffer())
//    println("BitBufferRtpPacket: \n$bbPacket")
//    packetData.rewind()
//
//    if (true) return
//
//    //val fp = FieldRtpPacket(packetData.asReadOnlyBuffer())
//    //println("FieldRtpPacket: \n$fp")
//    //packetData.rewind()
//
//    //val packet = AbsoluteIndexRtpPacket(packetData)
//    //println("RtpPacket: \n$packet")
//
//
//    val rtpPacketTimes = mutableListOf<Long>()
//    val bitBufferPacketTimes = mutableListOf<Long>()
//    val fieldPacketTimes = mutableListOf<Long>()
//    sleep(10000)
//    repeat (10) {
//        //fieldPacketTimes.add(perfTest(packetData.asReadOnlyBuffer()) { p -> FieldRtpPacket(p)})
//        bitBufferPacketTimes.add(perfTest(packetData.asReadOnlyBuffer()) { p -> BitBufferRtpPacket.fromBuffer(p)})
//        //rtpPacketTimes.add(perfTest(packetData.asReadOnlyBuffer()) { p -> AbsoluteIndexRtpPacket(p)})
//    }
//    println("RtpPacket took an average of ${rtpPacketTimes.average()}ms")
//    println("BitBufferRtpPacket took an average of ${bitBufferPacketTimes.average()}ms")
//    println("FieldRtpPacket took an average of ${fieldPacketTimes.average()}ms")
//}

