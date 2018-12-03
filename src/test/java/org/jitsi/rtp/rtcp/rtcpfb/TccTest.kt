package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.matchers.maps.shouldContain
import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

internal class TccTest : ShouldSpec() {
    override fun isInstancePerTest(): Boolean = true

    private val fci = ByteBuffer.wrap(byteArrayOf(
        // base=4, pkt status count=0x1729=5929
        0x00.toByte(), 0x04.toByte(), 0x17.toByte(), 0x29.toByte(),
        // ref time=0x298710 (174179328L ms), fbPktCount=1
        0x29.toByte(), 0x87.toByte(), 0x10.toByte(), 0x01.toByte(),

        // Chunks:
        // vector, 1-bit symbols, 1xR + 13xNR, 14 pkts (1 received)
        0xa0.toByte(), 0x00.toByte(),
        // vector, 1-bit symbols, 1xR + 13xNR, 14 pkts (1 received)
        0xa0.toByte(), 0x00.toByte(),
        // RLE, not received: 5886
        0x16.toByte(), 0xfe.toByte(),
        // vector, 2-bit symbols, 1x large delta + 6x small delta, 7 packets
        // (7 received)
        0xe5.toByte(), 0x55.toByte(),
        // vector, 1-bit symbols, 3xR + 2NR + 1R + 1NR + 1R [packets over, 6 remaining 0 bits]
        // (5 received)
        0xb9.toByte(), 0x40.toByte(),

        // deltas: Sx2, L, Sx11 (15 bytes)
        0x2c.toByte(), 0x78.toByte(),
        // the large one
        0xff.toByte(), 0x64.toByte(),
        0x04.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(),
        0x04.toByte(), 0x00.toByte(), 0x04.toByte(), 0x04.toByte(),
        0x00.toByte(), 0x1c.toByte(), 0x34.toByte()
    ))

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
        "TCC FCI" {
            "Parsing a TCC FCI from a buffer" {
                "with one bit and two bit symbols" {
                    should("parse the values correctly") {
                        val tcc = Tcc(fci)
                        // Based on the values in the packet above
                        tcc.referenceTimeMs shouldBe 174179328L
                        tcc.feedbackPacketCount shouldBe 1
                        // 5929 total packet statuses
                        tcc.numPackets() shouldBe 5929
                        // We should have 14 deltas
                        getNumDeltasInTcc(tcc) shouldBe 14
                    }
                }
                "with all 2 bit symbols" {
                    val tcc = Tcc(fciAll2BitVectorChunks)
                    val buf = tcc.getBuffer()
                    should("write the data to the buffer correctly") {
                        buf.compareTo(fciAll2BitVectorChunks) shouldBe 0
                    }
                }
                "with a negative delta" { // has a negative delta
                    val b = ByteBuffer.wrap(byteArrayOf(
                        0x00.toByte(), 0x0C.toByte(), 0x00.toByte(), 0xEC.toByte(),
                        0x15.toByte(), 0xF8.toByte(), 0xF7.toByte(), 0x00.toByte(),
                        0xBF.toByte(), 0xFE.toByte(), 0xA5.toByte(), 0x29.toByte(),
                        0x92.toByte(), 0x4A.toByte(), 0x94.toByte(), 0xF5.toByte(),
                        0xAC.toByte(), 0xCE.toByte(), 0xB3.toByte(), 0x33.toByte(),
                        0xAC.toByte(), 0xDA.toByte(), 0xB6.toByte(), 0x9B.toByte(),
                        0xAD.toByte(), 0x73.toByte(), 0xA7.toByte(), 0x66.toByte(),
                        0xB5.toByte(), 0xCE.toByte(), 0x9D.toByte(), 0xCE.toByte(),
                        0xB6.toByte(), 0xED.toByte(), 0x8D.toByte(), 0xCF.toByte(),
                        0x9C.toByte(), 0xED.toByte(), 0xAE.toByte(), 0x73.toByte(),
                        0xD1.toByte(), 0x19.toByte(), 0xD4.toByte(), 0x50.toByte(),
                        0xCC.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
                        0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(),
                        0xFF.toByte(), 0xFC.toByte(), 0x2C.toByte(), 0x04.toByte(),
                        0x00.toByte(), 0x18.toByte(), 0x00.toByte(), 0x00.toByte()
                    ))
                    //TODO (and probably create a simpler buffer to check this)
                    val t = Tcc(b)
                    t.getBuffer()
                }
            }
            "Creating a TCC packet from values" {
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
                    should("serialize correctly") {
                        // We know parsing from a buffer works, so we test serialization by parsing it again and
                        //  comparing.
                        val buf = tcc.getBuffer()
                        val parsedTcc = Tcc(buf)
                        parsedTcc.numPackets() shouldBe seqNumsAndTimestamps.size
                        parsedTcc.forEach { seqNum, ts ->
                            //TODO: it isn't this easy, as the timestamps are masked and shifted so they won't
                            // match the originals, but the deltas should (roughly) match?
//                            seqNumsAndTimestamps.shouldContain(seqNum, ts)
                        }
                    }
                }
            }
        }
        "PacketStatusChunk" {
            "parsing a vector chunk with 1 bit symbols" {
                // vector, 1-bit symbols, 1xR + 13xNR, 14 pkts (1 received)
                val chunkData = ByteBuffer.wrap(byteArrayOf(0xa0.toByte(), 0x00.toByte()))
                val chunk = PacketStatusChunk.parse(chunkData)
                should("parse the values correctly") {
                    chunk.getChunkType() shouldBe PacketStatusChunkType.STATUS_VECTOR_CHUNK
                    chunk.numPacketStatuses() shouldBe 14
                    chunk.forEachIndexed { index, status ->
                        when (index) {
                            0 -> status shouldBe OneBitPacketStatusSymbol.RECEIVED
                            in 1..13 -> status shouldBe OneBitPacketStatusSymbol.NOT_RECEIVED
                            else -> fail("Unexpected packet status, index: $index")
                        }
                    }
                }
            }
            "parsing a vector chunk with 2 bit symbols" {
                // vector, 2-bit symbols, 1x large delta + 6x small delta, 7 packets
                // (7 received)
                val chunkData = ByteBuffer.wrap(byteArrayOf(0xe5.toByte(), 0x55.toByte()))
                val chunk = PacketStatusChunk.parse(chunkData)
                should("parse the values correctly") {
                    chunk.getChunkType() shouldBe PacketStatusChunkType.STATUS_VECTOR_CHUNK
                    chunk.numPacketStatuses() shouldBe 7
                    chunk.forEachIndexed { index, status ->
                        when (index) {
                            0 -> status shouldBe TwoBitPacketStatusSymbol.RECEIVED_LARGE_OR_NEGATIVE_DELTA
                            in 1..6 -> status shouldBe TwoBitPacketStatusSymbol.RECEIVED_SMALL_DELTA
                            else -> fail("Unexpected packet status, index: $index")
                        }
                    }
                }
            }
            "parsing a vector chunk with 1 bit symbols and 'extra' statuses" {
                // vector, 1-bit symbols, 3xR + 2NR + 1R + 1NR + 1R [packets over, 6 remaining 0 bits] (5 received)
                val chunkData = ByteBuffer.wrap(byteArrayOf(0xb9.toByte(), 0x40.toByte()))
                val chunk = PacketStatusChunk.parse(chunkData)
                should("parse the values correctly") {
                    chunk.getChunkType() shouldBe PacketStatusChunkType.STATUS_VECTOR_CHUNK
                    // Even though there are 'extra' statuses in the last few bits, the PacketStatusChunk
                    // doesn't know/care about that, so it should parse all of them
                    chunk.numPacketStatuses() shouldBe 14
                    chunk.forEachIndexed { index, status ->
                        when (index) {
                            in 0..2 -> status shouldBe OneBitPacketStatusSymbol.RECEIVED
                            3, 4 -> status shouldBe OneBitPacketStatusSymbol.NOT_RECEIVED
                            5 -> status shouldBe OneBitPacketStatusSymbol.RECEIVED
                            6 -> status shouldBe OneBitPacketStatusSymbol.NOT_RECEIVED
                            7 -> status shouldBe OneBitPacketStatusSymbol.RECEIVED
                            in 8..14 -> Unit
                            else -> fail("Unexpected packet status, index: $index")
                        }
                    }
                }
            }
            "parsing a run length chunk" {
                val chunkData = ByteBuffer.wrap(byteArrayOf(0x16.toByte(), 0xfe.toByte()))
                // RLE, not received: 5886
                val chunk = PacketStatusChunk.parse(chunkData)
                should("parse the values correctly") {
                    chunk.getChunkType() shouldBe PacketStatusChunkType.RUN_LENGTH_CHUNK
                    chunk.numPacketStatuses() shouldBe 5886
                    chunk.forEach { status ->
                        status shouldBe TwoBitPacketStatusSymbol.NOT_RECEIVED
                    }
                }
            }
        }
        "ReceiveDelta" {
            "EightBitReceiveDelta" {

            }
        }
    }
}
