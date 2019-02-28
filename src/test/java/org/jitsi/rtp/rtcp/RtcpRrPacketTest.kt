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

import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.spy
import io.kotlintest.IsolationMode
import io.kotlintest.matchers.containAll
import io.kotlintest.matchers.haveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.put3Bytes
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.BitBuffer
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

internal class RtcpRrPacketTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    // Report blocks
    private val ssrc1: Long = 12345
    private val fractionLost1: Int = 42
    private val cumulativeLost1: Int = 4242
    private val seqNumCycles1: Int = 1
    private val seqNum1: Int = 42
    private val interarrivalJitter1: Long = 4242
    private val lastSrTimestamp1: Long = 23456
    private val delaySinceLastSr1: Long = 34567

    private val ssrc2: Long = 23456
    private val fractionLost2: Int = 43
    private val cumulativeLost2: Int = 4343
    private val seqNumCycles2: Int = 2
    private val seqNum2: Int = 43
    private val interarrivalJitter2: Long = 4343
    private val lastSrTimestamp2: Long = 34567
    private val delaySinceLastSr2: Long = 45678

    init {
        // Header + 2 ReportBlocks
        val packetBuf = ByteBuffer.allocate(8 + 2 * 24)
        with (BitBuffer(packetBuf)) {
            // Header
            putBits(0x2, 2) // Version
            putBoolean(false) // Padding
            putBits(0x2, 5) // Report count
            packetBuf.put(RtcpRrPacket.PT.toByte()) // Payload type
            packetBuf.putShort(0xFFFF.toShort()) // length
            packetBuf.putInt(0xFFFFFFFF.toInt()) // sender ssrc

            packetBuf.putInt(ssrc1.toInt())
            packetBuf.put(fractionLost1.toByte()); packetBuf.put3Bytes(cumulativeLost1)
            packetBuf.putShort(seqNumCycles1.toShort()); packetBuf.putShort(seqNum1.toShort())
            packetBuf.putInt(interarrivalJitter1.toInt())
            packetBuf.putInt(lastSrTimestamp1.toInt())
            packetBuf.putInt(delaySinceLastSr1.toInt())

            packetBuf.putInt(ssrc2.toInt())
            packetBuf.put(fractionLost2.toByte()); packetBuf.put3Bytes(cumulativeLost2)
            packetBuf.putShort(seqNumCycles2.toShort()); packetBuf.putShort(seqNum2.toShort())
            packetBuf.putInt(interarrivalJitter2.toInt())
            packetBuf.putInt(lastSrTimestamp2.toInt())
            packetBuf.putInt(delaySinceLastSr2.toInt())
            packetBuf.rewind() as ByteBuffer
        }
        "creation" {
            "from a buffer" {
                val rrPacket = RtcpRrPacket.fromBuffer(packetBuf)
                should("read all values correctly") {
                    rrPacket.reportBlocks should haveSize(2)
                }
                should("leave the buffer's position at the end of the parsed data") {
                    packetBuf.position() shouldBe packetBuf.limit()
                }
            }
            "from values" {
                val header: RtcpHeader = spy()
                val reportBlocks: MutableList<RtcpReportBlock> = mutableListOf(mock(), mock())
                val rrPacket = RtcpRrPacket(header, reportBlocks)
                should("set all values correctly") {
                    rrPacket.header shouldBe header
                    rrPacket.reportBlocks should containAll(reportBlocks)
                }
            }
        }
        "serialization" {
            val rrPacket = RtcpRrPacket.fromBuffer(packetBuf)
            "via requesting a new buffer" {
                val newBuf = rrPacket.getBuffer()
                should("write everything correctly") {
                    newBuf should haveSameContentAs(packetBuf)
                }
            }
            "to an existing buffer" {
                val existingBuf = ByteBuffer.allocate(10 + rrPacket.sizeBytes)
                existingBuf.position(10)
                rrPacket.serializeTo(existingBuf)
                should("write the data to the proper place") {
                    val subBuf = existingBuf.subBuffer(10)
                    subBuf should haveSameContentAs(packetBuf)
                }
                should("leave the buffer's position after the field it just wrote") {
                    existingBuf.position() shouldBe existingBuf.limit()
                }
            }
        }
    }
}
