package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
import org.jitsi.rtp.extensions.toHex
import org.junit.jupiter.api.Assertions.*
import java.nio.ByteBuffer

internal class TccTest : ShouldSpec() {
    private val fci = ByteBuffer.wrap(byteArrayOf(
        // base=4, pkt status count=0x1729=5929
        0x00.toByte(), 0x04.toByte(), 0x17.toByte(), 0x29.toByte(),
        // ref time=0x298710, fbPktCount=1
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
        // large
        0xff.toByte(), 0x64.toByte(),
        // 6x small
        0x04.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),

        // large
        0xff.toByte(), 0x65.toByte(),
        // 6x small
        0x04.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte(),
        // large
        0xff.toByte(), 0x65.toByte(),
        // 6x small
        0x04.toByte(), 0x04.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), 0x00.toByte()
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

    init {
        "f:TCC Packet" {
            "!Parsing a TCC packet from a buffer" {
                val tcc = Tcc(fci)
                should("parse the values correctly") {
                    // Based on the values in the packet above
                    tcc.feedbackPacketCount shouldBe 1
                    tcc.packetInfo.firstKey() shouldBe 4
                    // 5929 total packet statuses
                    tcc.packetInfo.size shouldBe 5929
                    // We should have 7 deltas
                    tcc.packetInfo.filter { it.value != NOT_RECEIVED_TS }.size shouldBe 7
                }
            }
            "!blah" {
                val tcc = Tcc(pktFromCall)
                println(tcc.packetInfo)
                tcc.getBuffer()
            }
            "Creating a TCC packet" {
                val tcc = Tcc(fciAll2BitVectorChunks)
                val buf = tcc.getBuffer()
                should("write the data to the buffer correctly") {
                    println("new buf:\n${buf.toHex()}")
                    println("original buf:\n${fciAll2BitVectorChunks.toHex()}")
                    buf.compareTo(fciAll2BitVectorChunks) shouldBe 0
                }
//                val baseSeqNum = 0
//                val packetStatusList = mutableListOf<Pair<Int, PacketStatusSymbol>>()
//                val packetDeltas = mutableListOf<ReceiveDelta>()
//                for (i in 0..100) {
//                    packetStatusList.add(Pair(i, PacketStatusSymbol.RECEIVED_SMALL_DELTA))
//                    packetDeltas.add(ReceiveDelta.create(20.0))
//                }
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
