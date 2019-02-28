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

import org.jitsi.rtp.extensions.getBit
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.Serializable
import java.nio.ByteBuffer
import kotlin.experimental.and

enum class PacketStatusChunkType(val value: Int) {
    UNKNOWN(-1),
    RUN_LENGTH_CHUNK(0),
    STATUS_VECTOR_CHUNK(1);

    companion object {
        private val map = PacketStatusChunkType.values().associateBy(PacketStatusChunkType::value)
        fun fromInt(type: Int): PacketStatusChunkType = map.getOrDefault(type, UNKNOWN)
    }
}

internal abstract class PacketStatusChunk : Iterable<PacketStatusSymbol>, Serializable() {

    class PacketStatusChunkIterator(private val p: PacketStatusChunk) : Iterator<PacketStatusSymbol> {
        private var currSymbolIndex: Int = 0
        override fun hasNext(): Boolean = currSymbolIndex < p.numPacketStatuses()
        override fun next(): PacketStatusSymbol = p.getStatusByIndex(currSymbolIndex++)
    }

    abstract fun getChunkType(): PacketStatusChunkType
    /**
     * Get the number of packet statuses contained within this [PacketStatusChunk].  Note that this will
     * return the amount of packet statuses this chunk is *capable* of containing, not necessarily how many
     * valid ones it will actually contain (in the case of the last chunk).
     */
    abstract fun numPacketStatuses(): Int

    override fun iterator(): Iterator<PacketStatusSymbol> = PacketStatusChunkIterator(this)

    /**
     * Get the status of the [index]th represented in this [PacketStatusChunk]
     */
    abstract fun getStatusByIndex(index: Int): PacketStatusSymbol

    companion object {
        const val SIZE_BYTES = 2
        fun getChunkType(buf: ByteBuffer): PacketStatusChunkType = PacketStatusChunkType.fromInt(buf.get(0).getBit(0))
        fun setChunkType(buf: ByteBuffer, chunkType: PacketStatusChunkType) {
            buf.putBits(0, 0, chunkType.value.toByte(), 1)
        }

        /**
         * Note that the returned [PacketStatusChunk] will parse a status for every
         * available position in the status chunk.  Meaning that a [PacketStatusChunk]
         * doesn't know if the last N of the statuses it parses are actually invalid
         * (as can be the case with the last [PacketStatusChunk] in the list).  The
         * packet status count should always be used in determining how many valid
         * statuses are contained within a chunk.
         */
        fun parse(buf: ByteBuffer): PacketStatusChunk {
            return when (getChunkType(buf)) {
                PacketStatusChunkType.RUN_LENGTH_CHUNK -> RunLengthChunk.fromBuffer(buf)
                PacketStatusChunkType.STATUS_VECTOR_CHUNK -> StatusVectorChunk.fromBuffer(buf)
                else -> throw Exception("Unrecognized packet status chunk type: ${getChunkType(buf)}")
            }
        }
    }
}

/**
 *  https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.3
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |T| S |       Run Length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * T = 0
 * Uses a single 2-bit symbol
 */
internal class RunLengthChunk(
    val statusSymbol: PacketStatusSymbol = UnknownSymbol,
    val runLength: Int = -1
) : PacketStatusChunk() {
    override val sizeBytes: Int = SIZE_BYTES

    override fun getChunkType(): PacketStatusChunkType = PacketStatusChunkType.RUN_LENGTH_CHUNK
    override fun numPacketStatuses(): Int = this.runLength
    /**
     * Every status symbol in a run-length encoding chunk is the same
     */
    override fun getStatusByIndex(index: Int): PacketStatusSymbol = statusSymbol

    override fun serializeTo(buf: ByteBuffer) {
        // The setter methods below assume buf's position 0 is where they should
        // start writing.  That may not be the case, so just create a temporary
        // buffer which has its position 0 at buf's current position
        val serializeBuf = buf.subBuffer(buf.position())
        setChunkType(serializeBuf, PacketStatusChunkType.RUN_LENGTH_CHUNK)
        setStatusSymbol(serializeBuf, statusSymbol)
        setRunLength(serializeBuf, runLength)
        buf.incrementPosition(SIZE_BYTES)
    }

    companion object {
        fun fromBuffer(buf: ByteBuffer): RunLengthChunk {
            val statusSymbol = getStatusSymbol(buf)
            val runLength = getRunLength(buf)
            buf.incrementPosition(SIZE_BYTES)
            return RunLengthChunk(statusSymbol, runLength)
        }
        fun getStatusSymbol(buf: ByteBuffer): PacketStatusSymbol =
            TwoBitPacketStatusSymbol.fromInt(buf.get(0).getBits(1, 2).toPositiveInt())
        fun setStatusSymbol(buf: ByteBuffer, statusSymbol: PacketStatusSymbol) {
            buf.putBits(0, 1, statusSymbol.value.toByte(), 2)
        }

        fun getRunLength(buf: ByteBuffer): Int {
            val byte0 = buf.get(0) and 0x1F.toByte()
            val byte1 = buf.get(1)

            return (byte0.toPositiveInt() shl 8) or (byte1.toPositiveInt())
        }
        fun setRunLength(buf: ByteBuffer, runLength: Int) {
            val byte0 = ((runLength ushr 8) and 0x1F).toByte()
            val byte0val = (buf.get(0).toPositiveInt() or byte0.toPositiveInt()).toByte()
            val byte1 = (runLength and 0x00FF).toByte()

            buf.put(0, byte0val)
            buf.put(1, byte1)
        }
    }
}

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.4
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |T|S|       symbol list         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * T = 1
 */
internal class StatusVectorChunk(
    /**
     * How big each contained symbol size is, in bits
     * (Note that this is different from the symbol size
     * bit itself, which uses a '0' to denote 1 bit symbols
     * and a '1' to denote 2 bit symbols)
     */
    val symbolSizeBits: Int = 0,
    val packetStatusSymbols: List<PacketStatusSymbol> = listOf()
) : PacketStatusChunk() {
    override val sizeBytes: Int = SIZE_BYTES

    override fun getChunkType(): PacketStatusChunkType = PacketStatusChunkType.STATUS_VECTOR_CHUNK
    override fun numPacketStatuses(): Int {
        return when (symbolSizeBits) {
            1 -> 14
            2 -> 7
            else -> throw Exception("Unrecognized symbol size: $symbolSizeBits")
        }
    }
    override fun getStatusByIndex(index: Int): PacketStatusSymbol = packetStatusSymbols[index]

    override fun serializeTo(buf: ByteBuffer) {
        // The setter methods below assume buf's position 0 is where they should
        // start writing.  That may not be the case, so just create a temporary
        // buffer which has its position 0 at buf's current position
        val serializeBuf = buf.subBuffer(buf.position())
        setChunkType(serializeBuf, PacketStatusChunkType.STATUS_VECTOR_CHUNK)
        setSymbolSizeBits(serializeBuf, symbolSizeBits)
        setSymbolList(serializeBuf, packetStatusSymbols, symbolSizeBits)
        buf.incrementPosition(SIZE_BYTES)
    }

    companion object {
        fun fromBuffer(buf: ByteBuffer): StatusVectorChunk {
            val symbolSizeBits = getSymbolsSizeBits(buf)
            val packetStatusSymbols = getSymbolList(buf)
            buf.incrementPosition(SIZE_BYTES)
            return StatusVectorChunk(symbolSizeBits, packetStatusSymbols)
        }
        fun getSymbolsSizeBits(buf: ByteBuffer): Int {
            val symbolSizeBit = buf.get(0).getBits(1, 1).toPositiveInt()
            return when (symbolSizeBit) {
                0 -> 1
                1 -> 2
                else -> throw Exception("Unrecognized symbol size: $symbolSizeBit")
            }
        }
        fun setSymbolSizeBits(buf: ByteBuffer, symbolSizeBits: Int) {
            val symbolSizeBit = when(symbolSizeBits) {
                1 -> 0
                2 -> 1
                else -> throw Exception("Unsupported symbol size: $symbolSizeBits bits")
            }
            buf.putBits(0, 1, symbolSizeBit.toByte(), 1)
        }

        fun getSymbolList(buf: ByteBuffer): List<PacketStatusSymbol> {
            val symbolsSize = getSymbolsSizeBits(buf)
            val symbols = mutableListOf<PacketStatusSymbol>()
            for (bitIndex in 2..15 step symbolsSize) {
                val currByte = if (bitIndex <= 7) 0 else 1
                val bitInByteIndex = if (bitIndex <= 7) bitIndex else bitIndex - 8
                val symbol = when (symbolsSize) {
                    1 -> OneBitPacketStatusSymbol.fromInt((buf.get(currByte).getBits(bitInByteIndex, symbolsSize)).toPositiveInt())
                    2 -> TwoBitPacketStatusSymbol.fromInt((buf.get(currByte).getBits(bitInByteIndex, symbolsSize)).toPositiveInt())
                    else -> TODO()
                }
                symbols.add(symbol)
            }
            return symbols
        }
        fun setSymbolList(buf: ByteBuffer, statusSymbols: List<PacketStatusSymbol>, symbolSizeBits: Int) {
            for (i in 0 until statusSymbols.size) {
                val symbol = statusSymbols[i]
                val bitIndex = 2 + (i * symbolSizeBits)
                val byteIndex = if (bitIndex <= 7) 0 else 1
                val bitInByteIndex = if (bitIndex <= 7) bitIndex else bitIndex - 8
                buf.putBits(byteIndex, bitInByteIndex, symbol.value.toByte(), symbolSizeBits)
            }
        }
    }
}
