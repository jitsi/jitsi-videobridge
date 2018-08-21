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
package org.jitsi.rtp.rtcp.rtcpfb

import org.jitsi.rtp.extensions.get3Bytes
import org.jitsi.rtp.extensions.getBit
import org.jitsi.rtp.extensions.getBits
import org.jitsi.rtp.extensions.put3Bytes
import org.jitsi.rtp.extensions.putBits
import org.jitsi.rtp.extensions.subBuffer
import unsigned.toUByte
import unsigned.toUInt
import unsigned.toUShort
import unsigned.toUlong
import java.nio.ByteBuffer
import java.util.*
import kotlin.experimental.and

//TODO: pass a comparator here which can handle sequence number rollover
/**
 * Map the tcc sequence number to the timestamp at which that packet
 * was received
 */
class PacketMap : TreeMap<Int, Long>()
const val NOT_RECEIVED: Long = -1

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|  FMT=15 |    PT=205     |           length              |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                     SSRC of packet sender                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                      SSRC of media source                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |      base sequence number     |      packet status count      |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                 reference time                | fb pkt. count |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |          packet chunk         |         packet chunk          |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |         packet chunk          |  recv delta   |  recv delta   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * .                                                               .
 * .                                                               .
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |           recv delta          |  recv delta   | zero padding  |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class Tcc : FeedbackControlInformation {
    override var buf: ByteBuffer? = null

    var baseSeqNum: Int
    var packetStatusCount: Int
    var referenceTime: Int
    var feedbackPacketCount: Int
    var packetInfo: PacketMap
//    var packetStatusChunks: MutableList<PacketStatusChunk>
//    var receiveDeltas: MutableList<ReceiveDelta>
    override val size: Int
        get() {
            //TODO: optimize this
            return getBuffer().limit()
//            return 8 +
//                    (packetStatusChunks.size * 2) +
//                    (receiveDeltas.map { it.getSize()}.sum())
        }

    companion object {
        const val FMT = 15

        /**
         * [buf] should start at the beginning of the FCI block
         */
        fun getBaseSeqNum(buf: ByteBuffer): Int = buf.getShort(0).toUInt()
        fun setBaseSeqNum(buf: ByteBuffer, baseSeqNum: Int) {
            buf.putShort(0, baseSeqNum.toShort())
        }

        fun getPacketStatusCount(buf: ByteBuffer): Int = buf.getShort(2).toUInt()
        fun setPacketStatusCount(buf: ByteBuffer, packetStatusCount: Int) {
            buf.putShort(2, packetStatusCount.toUShort())
        }

        fun getReferenceTime(buf: ByteBuffer): Int = buf.get3Bytes(4)
        fun setReferenceTime(buf: ByteBuffer, referenceTime: Int) {
            buf.put3Bytes(4, referenceTime)
        }

        fun getFeedbackPacketCount(buf: ByteBuffer): Int = buf.get(7).toUInt()
        fun setFeedbackPacketCount(buf: ByteBuffer, feedbackPacketCount: Int) {
            buf.put(7, feedbackPacketCount.toUByte())
        }

        /**
         * Given a buffer which starts at the beginning of the packet status chunks and the
         * packet status count, return a [Pair] containing 1) the list of [PacketStatusChunk]s
         * and 2) the list of [ReceiveDelta]s
         */
        fun getPacketChunksAndDeltas(packetStatusBuf: ByteBuffer, packetStatusCount: Int): Pair<List<PacketStatusSymbol>, List<ReceiveDelta>> {
            val packetStatuses = mutableListOf<PacketStatusSymbol>()
            var numPacketStatusProcessed = 0
            var currOffset = 0
            while (numPacketStatusProcessed < packetStatusCount) {
                val packetChunkBuf = packetStatusBuf.subBuffer(currOffset)
                val packetStatusChunk = PacketStatusChunk.parse(packetChunkBuf)
                packetStatusChunk.forEach { packetStatus ->
                    if (numPacketStatusProcessed >= packetStatusCount) {
                        return@forEach
                    }
                    packetStatuses.add(packetStatus)
                    numPacketStatusProcessed++
                }
                currOffset += PacketStatusChunk.SIZE_BYTES
            }

            val packetDeltas = mutableListOf<ReceiveDelta>()
            packetStatuses.forEach { packetStatus ->
                val deltaSizeBytes = packetStatus.getDeltaSizeBytes()
                if (deltaSizeBytes != -1) {
                    val deltaBuf = packetStatusBuf.subBuffer(currOffset)
                    val receiveDelta = ReceiveDelta.parse(deltaBuf, deltaSizeBytes)
                    packetDeltas.add(receiveDelta)

                    currOffset += deltaSizeBytes
                }
            }

            return Pair(packetStatuses, packetDeltas)
        }
    }

    /**
     * Buf's position 0 must be the start of the FCI block and its limit
     * must represent the end (including
     */
    constructor(buf: ByteBuffer) : super() {
        this.buf = buf.slice()
        this.baseSeqNum = getBaseSeqNum(buf)
        this.packetStatusCount = getPacketStatusCount(buf)
        this.referenceTime = getReferenceTime(buf)
        this.feedbackPacketCount = getFeedbackPacketCount(buf)
        this.packetInfo = PacketMap()
//        this.packetStatusChunks = mutableListOf()
//        this.receiveDeltas = mutableListOf()

        val pendingReceiveDeltaSizes = mutableListOf<Int>()
        var currOffset = 8
        var numPacketsProcessed = 0
        var currSeqNum = baseSeqNum

        val (packetStatuses, packetDeltas) =
                getPacketChunksAndDeltas(buf.subBuffer(8), packetStatusCount)

        val deltaIter = packetDeltas.iterator()
        packetStatuses.forEachIndexed { index, packetStatus ->
            val seqNum = baseSeqNum + index
            val deltaMs: Long = if (packetStatus.hasDelta()) {
                deltaIter.next().deltaMs.toLong()
            } else {
                NOT_RECEIVED
            }
            val timestamp = if (deltaMs == NOT_RECEIVED) NOT_RECEIVED else referenceTime + deltaMs
            packetInfo[seqNum] = timestamp
        }

//        while (numPacketsProcessed < packetStatusCount) {
//            val packetChunkBuf = buf.subBuffer(currOffset)
//            currOffset += PacketStatusChunk.SIZE_BYTES
//            val packetStatus = PacketStatusChunk.parse(packetChunkBuf)
//            packetStatusChunks.add(packetStatus)
//            // We now need to parse each status symbol to see if it represents a delta that will involve a an extra delta
//            // block
//            packetStatus.forEach {
//                numPacketsProcessed++
//                when (it) {
//                    PacketStatusSymbol.STATUS_PACKET_RECEIVED_SMALL_DELTA -> pendingReceiveDeltaSizes.add(EightBitReceiveDelta.SIZE_BYTES)
//                    PacketStatusSymbol.STATUS_PACKET_LARGE_OR_NEGATIVE_DELTA -> pendingReceiveDeltaSizes.add(SixteenBitReceiveDelta.SIZE_BYTES)
//                    else -> Unit
//                }
//            }
//        }
//        // Now we have a list of the receive delta chunks that will follow the packet status chunks, so parse those
//        pendingReceiveDeltaSizes.forEach {
//            val delta = when (it) {
//                EightBitReceiveDelta.SIZE_BYTES -> {
//                    val deltaBuf = buf.subBuffer(currOffset)
//                    currOffset += EightBitReceiveDelta.SIZE_BYTES
//                    EightBitReceiveDelta(deltaBuf)
//                }
//                SixteenBitReceiveDelta.SIZE_BYTES -> {
//                    val deltaBuf = buf.subBuffer(currOffset)
//                    currOffset += SixteenBitReceiveDelta.SIZE_BYTES
//                    SixteenBitReceiveDelta(deltaBuf)
//                }
//                else -> throw Exception("Unrecognized delta chunk size: $it bytes")
//            }
//            receiveDeltas.add(delta)
//        }
    }

    constructor(
//        baseSeqNum: Int = -1,
//        packetStatusCount: Int = -1,
//        referenceTime: Int = -1,
        feedbackPacketCount: Int = -1,
        packetInfo: PacketMap = PacketMap()
//        packetStatusChunks: MutableList<PacketStatusChunk> = mutableListOf(),
//        receiveDeltas: MutableList<ReceiveDelta> = mutableListOf()
    ) {
        this.baseSeqNum = baseSeqNum
        this.packetStatusCount = packetStatusCount
        this.referenceTime = referenceTime
        this.feedbackPacketCount = feedbackPacketCount
        this.packetStatusChunks = packetStatusChunks
        this.receiveDeltas = receiveDeltas
    }

    fun addPacket(tccSeqNum: Int, timestamp: Int) {
    }

    /**
     * Get a list of pairs, where each pair denotes a packet sequence number
     * and its status
     * TODO: maybe this is the way we want to store the statuses (instead of as chunks)
     * so we can just return it directly?
     */
    fun getPacketStatuses(): List<Pair<Int, PacketStatusSymbol>> {
        var currPacketOffset = 0
        val packetStatuses = mutableListOf<Pair<Int, PacketStatusSymbol>>()
        packetStatusChunks.forEach statusLoop@ { packetStatusChunk ->
            packetStatusChunk.forEach { packetStatus ->
                val packetSeqNum = baseSeqNum + currPacketOffset++
                if (packetStatuses.size >= packetStatusCount) {
                    // We've processed the last status, break out of the loop
                    return@statusLoop
                }
                packetStatuses.add(Pair(packetSeqNum, packetStatus))
            }
        }

        return packetStatuses
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(this.size)
        }
        setBaseSeqNum(buf!!, this.baseSeqNum)
        setPacketStatusCount(buf!!, this.packetStatusCount)
        setReferenceTime(buf!!, this.referenceTime)
        setFeedbackPacketCount(buf!!, this.feedbackPacketCount)
        packetStatusChunks.forEach {
            //TODO
        }
        receiveDeltas.forEach {
            //TODO
        }
        return this.buf!!
    }

    override fun toString(): String {
        return "TCC packet"
    }
}

// https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.1
enum class PacketStatusSymbol(val value: Int) {
    UNKNOWN(-1),
    STATUS_PACKET_NOT_RECEIVED(0),
    STATUS_PACKET_RECEIVED_SMALL_DELTA(1),
    STATUS_PACKET_LARGE_OR_NEGATIVE_DELTA(2);

    companion object {
        private val map = PacketStatusSymbol.values().associateBy(PacketStatusSymbol::value);
        fun fromInt(type: Int): PacketStatusSymbol = map.getOrDefault(type, UNKNOWN)
    }

    fun hasDelta(): Boolean {
        return this == STATUS_PACKET_RECEIVED_SMALL_DELTA ||
                this == STATUS_PACKET_LARGE_OR_NEGATIVE_DELTA
    }

    fun getDeltaSizeBytes(): Int {
        return when (this) {
            STATUS_PACKET_RECEIVED_SMALL_DELTA -> 1
            STATUS_PACKET_LARGE_OR_NEGATIVE_DELTA -> 2
            else -> -1
        }
    }
}

enum class PacketStatusChunkType(val value: Int) {
    UNKNOWN(-1),
    RUN_LENGTH_CHUNK(0),
    STATUS_VECTOR_CHUNK(1);

    companion object {
        private val map = PacketStatusChunkType.values().associateBy(PacketStatusChunkType::value);
        fun fromInt(type: Int): PacketStatusChunkType = map.getOrDefault(type, UNKNOWN)
    }
}

abstract class PacketStatusChunk : Iterable<PacketStatusSymbol> {
    companion object {
        const val SIZE_BYTES = 2
        fun getChunkType(buf: ByteBuffer): PacketStatusChunkType = PacketStatusChunkType.fromInt(buf.get(0).getBit(0))
        fun setChunkType(buf: ByteBuffer, chunkType: PacketStatusChunkType) {
            buf.putBits(0, 0, chunkType.value.toUByte(), 1)
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
                PacketStatusChunkType.RUN_LENGTH_CHUNK -> RunLengthChunk(buf)
                PacketStatusChunkType.STATUS_VECTOR_CHUNK -> StatusVectorChunk(buf)
                else -> throw Exception("Unrecognized packet status chunk type: ${getChunkType(buf)}")
            }
        }
    }

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

    abstract fun getBuffer(): ByteBuffer
}

/**
 *  https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.3
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |T| S |       Run Length        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
private class RunLengthChunk : PacketStatusChunk {
    var buf: ByteBuffer? = null
    var statusSymbol: PacketStatusSymbol
    var runLength: Int
    companion object {
        fun getStatusSymbol(buf: ByteBuffer): PacketStatusSymbol = PacketStatusSymbol.fromInt(buf.get(0).getBits(1, 2).toUInt())
        fun setStatusSymbol(buf: ByteBuffer, statusSymbol: PacketStatusSymbol) {
            buf.putBits(0, 1, statusSymbol.value.toUByte(), 2)
        }

        fun getRunLength(buf: ByteBuffer): Int {
            val byte0 = buf.get(0) and 0x1F.toByte()
            val byte1 = buf.get(1)

            return (byte0.toUInt() shl 8) or (byte1.toUInt())
        }
        fun setRunLength(buf: ByteBuffer, runLength: Int) {
            val byte0 = ((runLength ushr 8) and 0x1F).toUByte()
            val byte0val = (buf.get(0).toUInt() or byte0.toUInt()).toUByte()
            val byte1 = (runLength and 0x00).toUByte()

            buf.put(0, byte0val)
            buf.put(1, byte1)
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.slice()
        if (getChunkType(buf) != PacketStatusChunkType.RUN_LENGTH_CHUNK) {
            throw Exception("Invalid RunLengthChunk type ${getChunkType(buf)}")
        }
        this.statusSymbol = RunLengthChunk.getStatusSymbol(buf)
        this.runLength = RunLengthChunk.getRunLength(buf)
    }

    constructor(
        statusSymbol: PacketStatusSymbol = PacketStatusSymbol.UNKNOWN,
        runLength: Int = 0
    ) {
        this.statusSymbol = statusSymbol
        this.runLength = runLength
    }

    override fun getChunkType(): PacketStatusChunkType = PacketStatusChunkType.RUN_LENGTH_CHUNK
    override fun numPacketStatuses(): Int = this.runLength
    /**
     * Every status symbol in a run-length encoding chunk is the same
     */
    override fun getStatusByIndex(index: Int): PacketStatusSymbol = statusSymbol

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(PacketStatusChunk.SIZE_BYTES)
        }
        setChunkType(buf!!, PacketStatusChunkType.RUN_LENGTH_CHUNK)
        setStatusSymbol(buf!!, statusSymbol)
        setRunLength(buf!!, runLength)

        return buf!!
    }
}

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.4
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |T|S|       symbol list         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
private class StatusVectorChunk : PacketStatusChunk {
    var buf: ByteBuffer? = null
    /**
     * How big each contained symbol size is, in bits
     * (Note that this is different from the symbol size
     * bit itself, which uses a '0' to denote 1 bit symbols
     * and a '1' to denote 2 bit symbols)
     */
    val symbolSizeBits: Int
    val packetStatusSymbols: List<PacketStatusSymbol>
    companion object {
        fun getSymbolsSizeBits(buf: ByteBuffer): Int {
            val symbolSizeBit = buf.get(0).getBits(1, 1).toUInt()
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
            buf.putBits(0, 1, symbolSizeBit.toUByte(), 1)
        }

        fun getSymbolList(buf: ByteBuffer): List<PacketStatusSymbol> {
            val symbolsSize = getSymbolsSizeBits(buf)
            val symbols = mutableListOf<PacketStatusSymbol>()
            for (bitIndex in 2..15 step symbolsSize) {
                val currByte = if (bitIndex <= 7) 0 else 1
                val bitInByteIndex = if (bitIndex <= 7) bitIndex else bitIndex - 8
                val symbol = PacketStatusSymbol.fromInt((buf.get(currByte).getBits(bitInByteIndex, symbolsSize)).toUInt())
                symbols.add(symbol)
            }
            return symbols
        }
        fun setSymbolList(buf: ByteBuffer, statusSymbols: List<PacketStatusSymbol>, symbolSizeBits: Int) {
            for (i in 0..statusSymbols.size) {
                val symbol = statusSymbols.get(i)
                val bitIndex = 2 + (i * symbolSizeBits)
                val byteIndex = if (bitIndex <= 7) 0 else 1
                val bitInByteIndex = if (bitIndex <= 7) bitIndex else bitIndex - 8
                buf.putBits(byteIndex, bitInByteIndex, symbol.value.toUByte(), symbolSizeBits)
            }
        }
    }

    constructor(buf: ByteBuffer) : super() {
        this.buf = buf.slice()
        this.symbolSizeBits = getSymbolsSizeBits(buf)
        this.packetStatusSymbols = getSymbolList(buf)
    }

    constructor(
        symbolSize: Int = 0,
        packetStatusSymbols: List<PacketStatusSymbol> = listOf()
    ) {
        this.symbolSizeBits = symbolSize
        this.packetStatusSymbols = packetStatusSymbols
    }

    override fun getChunkType(): PacketStatusChunkType = PacketStatusChunkType.STATUS_VECTOR_CHUNK
    override fun numPacketStatuses(): Int {
        return when (symbolSizeBits) {
            1 -> 14
            2 -> 7
            else -> throw Exception("Unrecognized symbol size: $symbolSizeBits")
        }
    }
    override fun getStatusByIndex(index: Int): PacketStatusSymbol = packetStatusSymbols[index]


    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(PacketStatusChunk.SIZE_BYTES)
        }
        setChunkType(buf!!, PacketStatusChunkType.STATUS_VECTOR_CHUNK)
        setSymbolSizeBits(buf!!, symbolSizeBits)
        setSymbolList(buf!!, packetStatusSymbols, symbolSizeBits)

        return buf!!
    }
}

abstract class ReceiveDelta {
    abstract var deltaMs: Double //TODO: should we be able to hold this as a long? don't think a double makes sense?
    abstract fun getBuffer(): ByteBuffer
    abstract fun getSize(): Int

    companion object {
        fun parse(buf: ByteBuffer, deltaSizeBytes: Int): ReceiveDelta {
            return when (deltaSizeBytes) {
                EightBitReceiveDelta.SIZE_BYTES -> EightBitReceiveDelta(buf)
                SixteenBitReceiveDelta.SIZE_BYTES -> SixteenBitReceiveDelta(buf)
                else -> throw Exception("Unsupported receive delta size: $deltaSizeBytes bytes")
            }
        }
        fun create(delta: Double): ReceiveDelta {
            return when (delta) {
                in 0.0..63.75 -> EightBitReceiveDelta(delta)
                in -8192.0..8191.75 -> SixteenBitReceiveDelta(delta)
                else -> throw Exception("Unsupported delta value: $delta")
            }
        }
    }

    override fun toString(): String {
        return "delta: ${deltaMs}ms"
    }
}

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1.5
 * If the "Packet received, small delta" symbol has been appended to
 * the status list, an 8-bit unsigned receive delta will be appended
 * to recv delta list, representing a delta in the range [0, 63.75]
 * ms.
 */
class EightBitReceiveDelta : ReceiveDelta {
    var buf: ByteBuffer? = null
    override var deltaMs: Double

    companion object {
        const val SIZE_BYTES = 1

        /**
         * The value written in the field is represented as multiples of 250us
         */
        fun getDeltaMs(buf: ByteBuffer): Double {
            val uSecMultiple = buf.get().toUInt()
            val uSecs = uSecMultiple * 25.0
            return uSecs / 1000.0
        }
        fun setDeltaMs(buf: ByteBuffer, deltaMs: Double) {
            val uSecs = deltaMs * 1000.0
            val uSecMultiple = uSecs / 250.0
            buf.put(uSecMultiple.toByte())
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.slice()
        this.deltaMs = getDeltaMs(buf)
    }

    constructor(delta: Double = 0.0) {
        this.deltaMs = delta
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(EightBitReceiveDelta.SIZE_BYTES)
        }
        setDeltaMs(this.buf!!, deltaMs)

        return this.buf!!
    }

    override fun getSize(): Int = EightBitReceiveDelta.SIZE_BYTES
}

/**
 * If the "Packet received, large or negative delta" symbol has been
 * appended to the status list, a 16-bit signed receive delta will be
 * appended to recv delta list, representing a delta in the range
 * [-8192.0, 8191.75] ms.
 */
class SixteenBitReceiveDelta : ReceiveDelta {
    var buf: ByteBuffer? = null
    override var deltaMs: Double

    companion object {
        const val SIZE_BYTES = 2

        /**
         * The value written in the field is represented as multiples of 250us
         */
        fun getDeltaMs(buf: ByteBuffer): Double {
            val uSecMultiple = buf.getShort().toUInt()
            val uSecs = uSecMultiple * 25.0
            return uSecs / 1000.0
        }
        fun setDeltaMs(buf: ByteBuffer, deltaMs: Double) {
            val uSecs = deltaMs * 1000.0
            val uSecMultiple = uSecs / 250.0
            buf.putShort(uSecMultiple.toShort())
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.slice()
        this.deltaMs = getDeltaMs(buf)
    }

    constructor(delta: Double = 0.0) {
        this.deltaMs = delta
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(SixteenBitReceiveDelta.SIZE_BYTES)
        }
        setDeltaMs(this.buf!!, deltaMs)

        return this.buf!!
    }
    override fun getSize(): Int = SixteenBitReceiveDelta.SIZE_BYTES
}
