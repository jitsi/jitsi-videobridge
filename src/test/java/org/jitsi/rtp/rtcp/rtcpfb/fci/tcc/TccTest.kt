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

package org.jitsi.rtp.rtcp.rtcpfb.fci.tcc

import io.kotlintest.IsolationMode
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.util.byteBufferOf
import org.jitsi.test_helpers.matchers.haveSameContentAs
import java.nio.ByteBuffer

internal class TccTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val fci = byteBufferOf(
        // base=4, pkt status count=0x1729=5929
        0x00, 0x04, 0x17, 0x29,
        // ref time=0x298710 (174179328L ms), fbPktCount=1
        0x29, 0x87, 0x10, 0x01,

        // Chunks:
        // vector, 1-bit symbols, 1xR + 13xNR, 14 pkts (1 received)
        0xa0, 0x00,
        // vector, 1-bit symbols, 1xR + 13xNR, 14 pkts (1 received)
        0xa0, 0x00,
        // RLE, not received: 5886
        0x16, 0xfe,
        // vector, 2-bit symbols, 1x large delta + 6x small delta, 7 packets
        // (7 received)
        0xe5, 0x55,
        // vector, 1-bit symbols, 3xR + 2NR + 1R + 1NR + 1R [packets over, 6 remaining 0 bits]
        // (5 received)
        0xb9, 0x40,

        // deltas: Sx2, L, Sx11 (15 bytes)
        // 2 small
        0x2c, 0x78,
        // 1 large
        0xff, 0x64,
        // 11 small
        0x04, 0x04, 0x00, 0x00,
        0x04, 0x00, 0x04, 0x04,
        0x00, 0x1c, 0x34
    )

    private val fciAll2BitVectorChunks = ByteBuffer.wrap(byteArrayOf(
        // base=4, pkt status count=0x1E=30
        0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x1E.toByte(),
        // ref time=0x298710, fbPktCount=1
        0x29.toByte(), 0x87.toByte(), 0x10.toByte(), 0x01.toByte(),

        // Chunks:
        // vector, 2-bit symbols, 1x large delta + 6x small delta, 7 packets
        // (7 received)
        0xe5.toByte(), 0x55.toByte(),
        // vector, 2-bit symbols, 1x large delta + 6x small delta, 7 packets
        // (7 received)
        0xe5.toByte(), 0x55.toByte(),
        // vector, 2-bit symbols, 7x not received
        // (0 received)
        0xc0.toByte(), 0x00.toByte(),
        // vector, 2-bit symbols, 7x not received
        // (0 received)
        0xc0.toByte(), 0x00.toByte(),
        // vector, 2-bit symbols, 1x large delta + 1x small delta, 2 packets
        // (2 received)
        0xe4.toByte(), 0x00.toByte(),

        // Deltas
        // 4: large (8000 ms)
        0x7d.toByte(), 0x00.toByte(),
        // 6x small
        // 5: 1, 6: 1, 7: 0, 8: 0, 9: 1, 10: 0
        0x04.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),

        // 11: large (8000 ms)
        0x7d.toByte(), 0x00.toByte(),
        // 6x small
        // 12: 1, 13: 1, 14: 0, 15: 0, 16: 1, 17: 0
        0x04.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),
        // 18-31 not received
        // 32: large (8000 ms)
        0x7d.toByte(), 0x00.toByte(),
        // 1x small
        // 33: 1
        0x04.toByte(),
        // Padding
        0x00.toByte(), 0x00.toByte(), 0x00.toByte()
    ))

    private val pktFromCall = ByteBuffer.wrap(byteArrayOf(
        0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x7A.toByte(),
        0x9D.toByte(), 0xFB.toByte(), 0xF0.toByte(), 0x00.toByte(),
        0x20.toByte(), 0x7A.toByte(), 0x70.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x08.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
    ))

    val getNumDeltasInTcc: (Tcc) -> Int = { tcc ->
        var numDeltas = 0
        tcc.forEach { _, timestamp ->
            if (timestamp != NOT_RECEIVED_TS) {
                numDeltas++
            }
        }
        numDeltas
    }

    init {
        "Parsing a TCC FCI from a buffer" {
            "with one bit and two bit symbols" {
                val tcc = Tcc.fromBuffer(fci)
                should("parse the values correctly") {
                    // Based on the values in the packet above
                    tcc.referenceTimeMs shouldBe 174179328L
                    tcc.feedbackPacketCount shouldBe 1
                    // 5929 total packet statuses
                    tcc.numPackets shouldBe 5929
                    // We should have 14 deltas
                    getNumDeltasInTcc(tcc) shouldBe 14
                }
                should("leave the buffer's position after the parsed data") {
                    fci.position() shouldBe fci.limit()
                }
            }
            "with all 2 bit symbols" {
                val tcc = Tcc.fromBuffer(fciAll2BitVectorChunks)
                val buf = tcc.getBuffer()
                should("write the data to the buffer correctly") {
                    buf should haveSameContentAs(fciAll2BitVectorChunks)
                }
            }
        }
        "Creating a TCC FCI from values" {
            "which include a delta value on the border of the symbol size (64ms)" {
                val tcc = Tcc(feedbackPacketCount = 136)
                val seqNumsAndTimestamps = mapOf(
                    2585 to 1537916094447,
                    2586 to 1537916094452,
                    2587 to 1537916094475,
                    2588 to 1537916094475,
                    2589 to 1537916094481,
                    2590 to 1537916094481,
                    2591 to 1537916094486,
                    2592 to 1537916094504,
                    2593 to 1537916094504,
                    2594 to 1537916094509,
                    2595 to 1537916094509,
                    2596 to 1537916094515,
                    2597 to 1537916094536,
                    2598 to 1537916094536,
                    2599 to 1537916094542,
                    2600 to 1537916094543,
                    2601 to 1537916094607,
                    2602 to 1537916094607,
                    2603 to 1537916094613,
                    2604 to 1537916094614
                )
                seqNumsAndTimestamps.forEach { seqNum, ts ->
                    tcc.addPacket(seqNum, ts)
                }
                "and then serializing it" {
                    "by asking it for a buffer" {
                        val buf = tcc.getBuffer()
                        should("serialize the data correctly") {
                            //TODO: hard to check
                        }
                    }
                    "to an existing buffer" {
                        val existingBuf = ByteBuffer.allocate(1024)
                        existingBuf.position(8)
                        tcc.serializeTo(existingBuf)
                        should("write the data to the correct place") {
                            //TODO: again, hard to verify, but we can at least make sure
                            // it didn't write to the first 8 bytes
                            for (i in 0..7) { existingBuf.get(i) shouldBe 0x00.toByte() }
                        }
                        should("leave the buffer's position at the end of the written data") {
                            existingBuf.position() shouldBe (8 + tcc.sizeBytes)
                        }
                    }
                }
            }
            "bit by bit" {
                val tcc = Tcc()
                should("update the size with each change") {
                    val size1 = tcc.sizeBytes

                    tcc.addPacket(10, 100L)
                    val size2 = tcc.sizeBytes
                    size2 shouldBeGreaterThan size1

                    tcc.addPacket(11, 200L)
                    val size3 = tcc.sizeBytes
                    size3 shouldBeGreaterThan size2
                }
            }
        }
    }
}