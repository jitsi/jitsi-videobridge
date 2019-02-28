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

import org.jitsi.rtp.extensions.get3Bytes
import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.put3Bytes
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtcp.rtcpfb.fci.FeedbackControlInformation
import org.jitsi.rtp.util.RtpUtils
import java.nio.ByteBuffer
import java.util.TreeMap

/**
 * Map the tcc sequence number to the timestamp at which that packet
 * was received
 */
class PacketMap : TreeMap<Int, Long>(RtpUtils.rtpSeqNumComparator), Cloneable {
    // Only does TwoBitPacketStatusSymbols since that's all we use
    fun getStatusSymbol(tccSeqNum: Int, referenceTime: Long): PacketStatusSymbol {
        val deltaMs = getDeltaMs(tccSeqNum, referenceTime) ?: return TwoBitPacketStatusSymbol.NOT_RECEIVED
        try {
            return TwoBitPacketStatusSymbol.fromDeltaMs(deltaMs)
        } catch (e: Exception) {
            println("Error getting status symbol: $e, current packet map: $this")
            throw e
        }
    }

    fun getDeltaMs(tccSeqNum: Int, referenceTime: Long): Double? {
        val timestamp = getOrDefault(tccSeqNum, NOT_RECEIVED_TS)
        if (timestamp == NOT_RECEIVED_TS) {
            return null
        }
        // Get the timestamp for the packet just before this one.  If there isn't one, then
        // this is the first packet so the delta is 0.0
        val previousTimestamp = getPreviousReceivedTimestamp(tccSeqNum)
        val deltaMs = when (previousTimestamp) {
            -1L -> timestamp - referenceTime
            else -> timestamp - previousTimestamp
        }
//        println("packet $tccSeqNum has delta $deltaMs (ts $timestamp, prev ts $previousTimestamp)")
        return deltaMs.toDouble()
    }

    override fun clone(): PacketMap {
        val newMap = PacketMap()
        newMap.putAll(this)
        return newMap
    }

    /**
     * Get the timestamp of the packet before this one that was actually received (i.e. ignore
     * sequence numbers that weren't received)
     */
    private fun getPreviousReceivedTimestamp(tccSeqNum: Int): Long {
        var prevSeqNum = tccSeqNum - 1
        var previousTimestamp = NOT_RECEIVED_TS
        while (previousTimestamp == NOT_RECEIVED_TS && containsKey(prevSeqNum)) {
            previousTimestamp = floorEntry(prevSeqNum--)?.value ?: NOT_RECEIVED_TS
        }

        return previousTimestamp
    }
}

const val NOT_RECEIVED_TS: Long = -1

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
class Tcc(
    feedbackPacketCount: Int = -1,
    referenceTimeMs: Long = -1,
    packetInfo: PacketMap = PacketMap()
): FeedbackControlInformation() {
    /**
     * Because calculating the size of the TCC FCI is complicated, we
     * use a flag to denote when something has changed and the
     * size value should be recalculated
     */
    private var sizeNeedsToBeRecalculated = true
    private var _sizeBytes: Int = -1

    var feedbackPacketCount: Int = feedbackPacketCount
        private set
    var referenceTimeMs: Long = referenceTimeMs
        private set
    var packetInfo: PacketMap = packetInfo
        private set

    /**
     * How many packets are currently represented by this TCC
     */
    val numPackets: Int get() = packetInfo.size

    override val sizeBytes: Int
        get() {
            if (sizeNeedsToBeRecalculated) {
                try {
                    val deltaBlocksSize = packetInfo.keys
                            .map { tccSeqNum -> packetInfo.getStatusSymbol(tccSeqNum, referenceTimeMs) }
                            .map(PacketStatusSymbol::getDeltaSizeBytes)
                            .sum()

                    // We always write status as vector chunks with 2 bit symbols
                    val dataSize = 8 + // header values
                            // We can encode 7 statuses per packet chunk.  The '+ 6' is to
                            // account for integer division.
                            ((packetInfo.size + 6) / 7) * PacketStatusChunk.SIZE_BYTES +
                            deltaBlocksSize
                    var paddingSize = 0
                    //TODO: should we worry about padding here?  this is the padding at the
                    // entire RTCP packet level, would be nice if we could handle it there,
                    // but i think that would involve changing the way we implement sizeBytes
                    while ((dataSize + paddingSize) % 4 != 0) {
                        paddingSize++
                    }
                    _sizeBytes = dataSize + paddingSize
                    sizeNeedsToBeRecalculated = false
                } catch (e: Exception) {
//                    println("Error getting size of TCC fci: $e, buffer:\n${buf?.toHex()}")
                    throw e
                }

            }
            return _sizeBytes
        }

    fun addPacket(seqNum: Int, timestamp: Long) {
        if (this.referenceTimeMs == -1L) {
            this.referenceTimeMs = timestamp
        }
        packetInfo[seqNum] = timestamp
        sizeNeedsToBeRecalculated = true
    }

    /**
     * Iterate over the pairs of (sequence number, timestamp) represented by this TCC FCI
     */
    fun forEach(action: (Int, Long) -> Unit) = packetInfo.forEach(action)

    override fun serializeTo(buf: ByteBuffer) {
        // The methods below use aboslute positions to write the data, assuming
        // that buf's position 0 is the start of the FCI block.  Since that may
        // not be the case, create a temp buffer here which starts at buf's
        // current position
        val absBuf = buf.subBuffer(buf.position())
        try {
            setFeedbackPacketCount(absBuf, this.feedbackPacketCount)
            try {
                setPacketInfo(absBuf, this.packetInfo, referenceTimeMs)
            } catch (e: Exception) {
                println("BRIAN exception setting packet info to buffer: $e, buffer size: ${buf.limit()}, needed size: ${this.sizeBytes}\n" +
                        "the FCI was: $this")
                throw e
            }
        } catch (e: Exception) {
            println("Exception getting tcc buffer: $e")
            println("tcc detected size: ${this.sizeBytes}, buffer capacity: ${buf.capacity()}")
            throw e
        }
        buf.incrementPosition(sizeBytes)
    }

    public override fun clone(): Tcc {
        return Tcc(feedbackPacketCount, referenceTimeMs, packetInfo.clone())
    }

    companion object {
        const val FMT = 15
        /**
         * How far into the FCI block the packet chunks start
         */
        private const val PACKET_CHUNK_OFFSET = 8

        fun fromBuffer(buf: ByteBuffer): Tcc {
            val feedbackPacketCount = getFeedbackPacketCount(buf)
            val referenceTimeMs = getReferenceTimeMs(buf)
            val packetInfo = getPacketInfo(buf)

            return Tcc(feedbackPacketCount, referenceTimeMs, packetInfo)
        }

        /**
         * [buf] should start at the beginning of the FCI block
         */
        fun getBaseSeqNum(buf: ByteBuffer): Int = buf.getShort(0).toPositiveInt()

        fun setBaseSeqNum(buf: ByteBuffer, baseSeqNum: Int) {
            buf.putShort(0, baseSeqNum.toShort())
        }

        fun getPacketStatusCount(buf: ByteBuffer): Int = buf.getShort(2).toPositiveInt()
        fun setPacketStatusCount(buf: ByteBuffer, packetStatusCount: Int) {
            buf.putShort(2, packetStatusCount.toShort())
        }

        /**
         * The reference time field is stored as a time for some arbitrary clock and
         * as a multiple of 64ms.  The time returned here is the translated time
         * (i.e. not a multiple of 64ms but a timestamp in milliseconds directly)
         */
        fun getReferenceTimeMs(buf: ByteBuffer): Long = (buf.get3Bytes(4) shl 6).toLong()

        /**
         * [referenceTime] should be a standard timestamp in milliseconds, this method
         * will convert the value to the packet format (a value which is a multiple of 64ms).
         * [buf] should start at the beginning of the TCC FCI buffer
         */
        fun setReferenceTimeMs(buf: ByteBuffer, referenceTime: Long) {
            buf.put3Bytes(4, (referenceTime.toInt() shr 6) and 0xFFFFFF)
        }

        fun getFeedbackPacketCount(buf: ByteBuffer): Int = buf.get(7).toPositiveInt()
        fun setFeedbackPacketCount(buf: ByteBuffer, feedbackPacketCount: Int) {
            buf.put(7, feedbackPacketCount.toByte())
        }

        /**
         * Given a buffer which starts at the beginning of the packet status chunks and the
         * packet status count, return a [Triple] containing:
         * 1) the list of [PacketStatusChunk]s
         * 2) the list of [ReceiveDelta]s
         * 3) the amount of bytes processed in the buffer
         */
        private fun getPacketChunksAndDeltas(
            packetStatusBuf: ByteBuffer,
            packetStatusCount: Int
        ): Triple<List<PacketStatusSymbol>, List<ReceiveDelta>, Int> {
            val packetStatuses = mutableListOf<PacketStatusSymbol>()
            var numPacketStatusProcessed = 0
            var currOffset = 0
            while (numPacketStatusProcessed < packetStatusCount) {
                val packetChunkBuf = packetStatusBuf.subBuffer(currOffset, PacketStatusChunk.SIZE_BYTES)
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
                if (packetStatus.hasDelta()) {
                    val deltaSizeBytes = packetStatus.getDeltaSizeBytes()
                    val deltaBuf = packetStatusBuf.subBuffer(currOffset)
                    val receiveDelta = ReceiveDelta.parse(deltaBuf, deltaSizeBytes)
                    packetDeltas.add(receiveDelta)
                    currOffset += deltaSizeBytes
                }
            }

            return Triple(packetStatuses, packetDeltas, currOffset)
        }

        /**
         * Given [buf], which is a buffer that starts at the beginning of the TCC FCI payload,
         * return a [PacketMap] containing the tcc sequence numbers and their received timestamps for
         * all packets described in this TCC packet
         */
        fun getPacketInfo(buf: ByteBuffer): PacketMap {
            val baseSeqNum = getBaseSeqNum(buf)
            val packetStatusCount = getPacketStatusCount(buf)
            val referenceTimeMs = getReferenceTimeMs(buf)
            // Increment buf's position to the start of the packet chunks
            buf.incrementPosition(PACKET_CHUNK_OFFSET)

            val (packetStatuses, packetDeltas, bytesRead) =
                getPacketChunksAndDeltas(buf.subBuffer(buf.position()), packetStatusCount)

            val packetInfo = PacketMap()
            val deltaIter = packetDeltas.iterator()
            var previousTimestamp = referenceTimeMs
            packetStatuses.forEachIndexed { index, packetStatus ->
                val seqNum = baseSeqNum + index
                val timestamp: Long = if (packetStatus.hasDelta()) {
                    val deltaMs = deltaIter.next().deltaMs.toLong()
                    val ts = previousTimestamp + deltaMs
                    previousTimestamp = ts
                    ts
                } else {
                    NOT_RECEIVED_TS
                }
                packetInfo[seqNum] = timestamp
            }
            buf.incrementPosition(bytesRead)
            return packetInfo
        }

        /**
         * Given [buf], which is a buffer that starts at the first packet status chunk, write all the
         * TCC packet information contained in [packetInfo] to the buffer.
         * For now we will always write status vector chunks with 2-bit symbols.
         */
        fun setPacketInfo(buf: ByteBuffer, packetInfo: PacketMap, referenceTimestamp: Long) {
            if (packetInfo.isEmpty()) {
                return
            }
            setBaseSeqNum(buf, packetInfo.firstKey())
            setPacketStatusCount(buf, packetInfo.size)
            setReferenceTimeMs(buf, referenceTimestamp)

            // Set the buffer's position to the start of the status chunks
            buf.position(8)

            val seqNums = packetInfo.keys.toList()
            val vectorChunks = mutableListOf<StatusVectorChunk>()
            val receiveDeltas = mutableListOf<ReceiveDelta>()
            // Each vector chunk will contain 7 packet status symbols
            for (vectorChunkStartIndex in 0 until packetInfo.size step 7) {
                val packetStatuses = mutableListOf<PacketStatusSymbol>()
                for (statusSymbolIndex in vectorChunkStartIndex until vectorChunkStartIndex + 7) {
                    if (statusSymbolIndex >= packetInfo.size) {
                        // The last block may not be full
                        break
                    }
                    val tccSeqNum = seqNums[statusSymbolIndex]
                    val symbol = packetInfo.getStatusSymbol(tccSeqNum, referenceTimestamp)
                    if (symbol.hasDelta()) {
                        val deltaMs = packetInfo.getDeltaMs(tccSeqNum, referenceTimestamp)!!
                        receiveDeltas.add(ReceiveDelta.create(deltaMs))
                    }
                    packetStatuses.add(symbol)
                }
                vectorChunks.add(StatusVectorChunk(2, packetStatuses))
            }
            vectorChunks.forEach {
                it.serializeTo(buf)
            }
            receiveDeltas.forEach {
                it.serializeTo(buf)
            }
        }
    }
}
