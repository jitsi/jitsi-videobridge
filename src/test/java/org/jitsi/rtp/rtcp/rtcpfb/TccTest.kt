package org.jitsi.rtp.rtcp.rtcpfb

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec
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

    init {
        "f:TCC Packet" {
            "Parsing a TCC packet from a buffer" {
                val tcc = Tcc(fci)
                should("parse the values correctly") {
                    tcc.baseSeqNum shouldBe 4
                    tcc.packetStatusCount shouldBe 5929
                    tcc.referenceTime shouldBe 0x298710
                    tcc.feedbackPacketCount shouldBe 1

                    val packetStatuses = tcc.getPacketStatuses()
                    packetStatuses.forEach { (seqNum, status) ->
                        // Based on the values in the packet above
                        when (seqNum) {
                            // vector, 1-bit symbols, 1xR + 13xNR, 14 pkts (1 received)
                            4 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
                            in 5..17 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_NOT_RECEIVED
                            // vector, 1-bit symbols, 1xR + 13xNR, 14 pkts (1 received)
                            18 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
                            in 19..31 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_NOT_RECEIVED
                            // RLE, not received: 5886
                            in 32..5917 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_NOT_RECEIVED
                            // vector, 2-bit symbols, 1x large delta + 6x small delta, 7 packets (7 received)
                            5918 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_LARGE_OR_NEGATIVE_DELTA
                            in 5919..5924 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
                            // vector, 1-bit symbols, 3xR + 2NR + 1R + 1NR + 1R [packets over, 6 remaining 0 bits] (5 received)
                            5925, 5926, 5927 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
                            5928, 5929 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_NOT_RECEIVED
                            5930 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
                            5931 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_NOT_RECEIVED
                            5932 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
                            else -> fail("Unexpected packet sequence number $seqNum with status $status")
                        }
                    }
                    var currDeltaIndex = 0
                    packetStatuses.forEach { (_, status) ->
                        if (status == PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA ||
                                status == PacketStatusSymbol.STATUS_PACKET_LARGE_OR_NEGATIVE_DELTA) {
                            val delta = tcc.receiveDeltas[currDeltaIndex++]
                            if (status == PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA) {
                                delta.getSize() shouldBe EightBitReceiveDelta.SIZE_BYTES
                            } else {
                                delta.getSize() shouldBe SixteenBitReceiveDelta.SIZE_BYTES
                            }
                        }
                    }
                }
            }
            "Creating a TCC packet" {
                //TODO
                val baseSeqNum = 0
                val packetStatusList = mutableListOf<Pair<Int, PacketStatusSymbol>>()
                val packetDeltas = mutableListOf<ReceiveDelta>()
                for (i in 0..100) {
                    packetStatusList.add(Pair(i, PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA))
                    packetDeltas.add(ReceiveDelta.create(20.0))
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
                            0 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
                            in 1..13 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_NOT_RECEIVED
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
                            0 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_LARGE_OR_NEGATIVE_DELTA
                            in 1..6 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
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
                            in 0..2 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
                            3, 4 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_NOT_RECEIVED
                            5 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
                            6 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_NOT_RECEIVED
                            7 -> status shouldBe PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA
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
                        status shouldBe PacketStatusSymbol.STATUS_PACKET_NOT_RECEIVED
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
