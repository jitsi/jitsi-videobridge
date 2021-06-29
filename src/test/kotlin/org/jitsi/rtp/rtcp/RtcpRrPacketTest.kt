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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.haveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer

internal class RtcpRrPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    // Report blocks
    val reportBlocks = mutableListOf(
        RtcpReportBlock(
            12345,
            42,
            4242,
            1,
            42,
            4242,
            23456,
            34567
        ),
        RtcpReportBlock(
            23456,
            43,
            4343,
            2,
            43,
            4343,
            34567,
            45678
        )
    )

    val rrPacket = RtcpRrPacketBuilder(
        reportBlocks = reportBlocks
    ).build()

    val rrPacketBuffer = ByteBuffer.wrap(rrPacket.buffer, rrPacket.offset, rrPacket.length)

    // TODO: write expected buf (copy from below?) and verify serialization

    init {
//         Header + 2 ReportBlocks
//        val packetBuf = ByteBuffer.allocate(8 + 2 * 24)
//        with (BitBuffer(packetBuf)) {
//            // Header
//            putBits(0x2, 2) // Version
//            putBoolean(false) // Padding
//            putBits(0x2, 5) // Report count
//            packetBuf.put(RtcpRrPacket.PT.toByte()) // Payload type
//            packetBuf.putShort(0xFFFF.toShort()) // length
//            packetBuf.putInt(0xFFFFFFFF.toInt()) // sender ssrc
//
//            packetBuf.putInt(ssrc1.toInt())
//            packetBuf.put(fractionLost1.toByte()); packetBuf.put3Bytes(cumulativeLost1)
//            packetBuf.putShort(seqNumCycles1.toShort()); packetBuf.putShort(seqNum1.toShort())
//            packetBuf.putInt(interarrivalJitter1.toInt())
//            packetBuf.putInt(lastSrTimestamp1.toInt())
//            packetBuf.putInt(delaySinceLastSr1.toInt())
//
//            packetBuf.putInt(ssrc2.toInt())
//            packetBuf.put(fractionLost2.toByte()); packetBuf.put3Bytes(cumulativeLost2)
//            packetBuf.putShort(seqNumCycles2.toShort()); packetBuf.putShort(seqNum2.toShort())
//            packetBuf.putInt(interarrivalJitter2.toInt())
//            packetBuf.putInt(lastSrTimestamp2.toInt())
//            packetBuf.putInt(delaySinceLastSr2.toInt())
//            packetBuf.rewind() as ByteBuffer
//        }
        context("creation") {
            context("from a buffer") {
                val rrPacket =
                    RtcpRrPacket(rrPacketBuffer.array(), rrPacketBuffer.arrayOffset(), rrPacketBuffer.limit())
                should("read all values correctly") {
                    rrPacket.packetType shouldBe RtcpRrPacket.PT
                    rrPacket.reportBlocks should haveSize(2)
                }
            }
        }
    }
}
