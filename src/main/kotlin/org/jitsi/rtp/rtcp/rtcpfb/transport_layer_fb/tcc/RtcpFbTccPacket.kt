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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc

import org.jitsi.rtp.extensions.bytearray.cloneFromPool
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.extensions.bytearray.put3Bytes
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket
import org.jitsi.rtp.util.get3BytesAsInt
import org.jitsi.rtp.util.getByteAsInt
import org.jitsi.rtp.util.getShortAsInt
import java.util.TreeMap

/**
 * https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
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
 *
 * packet status count:  16 bits The number of packets this feedback
 *  contains status for, starting with the packet identified
 *  by the base sequence number.
 *
 * feedback packet count:  8 bits A counter incremented by one for each
 *  feedback packet sent.  Used to detect feedback packet
 *  losses.
 */
class RtcpFbTccPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : TransportLayerRtcpFbPacket(buffer, offset, length), Iterable<Map.Entry<Int, Long>> {

    private val packetInfo: Map<Int, Long> by lazy {
        getPacketInfo(buffer, offset)
    }

    val referenceTimeMs: Long = getReferenceTimeMs(buffer, offset)

    /**
     * Get an iterator over the (seqNum, timestamp) of the contained packets
     */
    override fun iterator(): Iterator<Map.Entry<Int, Long>> = packetInfo.iterator()

    override fun clone(): RtcpFbTccPacket =
        RtcpFbTccPacket(buffer.cloneFromPool(), offset, length)

    companion object {
        const val FMT = 15
        const val BASE_SEQ_NUM_OFFSET = RtcpFbPacket.HEADER_SIZE
        const val PACKET_STATUS_COUNT_OFFSET = RtcpFbPacket.HEADER_SIZE + 2
        const val REFERENCE_TIME_OFFSET = RtcpFbPacket.HEADER_SIZE + 4
        const val FB_PACKET_COUNT_OFFSET = RtcpFbPacket.HEADER_SIZE + 7
        const val PACKET_CHUNKS_OFFSET = RtcpFbPacket.HEADER_SIZE + 8

        // baseOffset in all of these refers to the start of the entire RTCP TCC packet
        fun getBaseSeqNum(buf: ByteArray, baseOffset: Int): Int =
            buf.getShortAsInt(baseOffset + BASE_SEQ_NUM_OFFSET)
        fun setBaseSeqNum(buf: ByteArray, baseOffset: Int, value: Int) =
            buf.putShort(baseOffset + BASE_SEQ_NUM_OFFSET, value.toShort())

        fun getPacketStatusCount(buf: ByteArray, baseOffset: Int): Int =
            buf.getShortAsInt(baseOffset + PACKET_STATUS_COUNT_OFFSET)
        fun setPacketStatusCount(buf: ByteArray, baseOffset: Int, value: Int) =
            buf.putShort(baseOffset + PACKET_STATUS_COUNT_OFFSET, value.toShort())

        /**
         * The reference time field is stored as a time for some arbitrary clock and
         * as a multiple of 64ms.  The time returned here is the translated time
         * (i.e. not a multiple of 64ms but a timestamp in milliseconds directly)
         */
        fun getReferenceTimeMs(buf: ByteArray, baseOffset: Int): Long =
            (buf.get3BytesAsInt(baseOffset + REFERENCE_TIME_OFFSET) shl 6).toPositiveLong()
        /**
         * [referenceTime] should be a standard timestamp in milliseconds, this method
         * will convert the value to the packet format (a value which is a multiple of 64ms).
         * [buf] should start at the beginning of the TCC FCI buffer
         */
        fun setReferenceTimeMs(buf: ByteArray, baseOffset: Int, referenceTime: Long) {
            buf.put3Bytes(baseOffset + REFERENCE_TIME_OFFSET, (referenceTime.toInt() ushr 6) and 0xFFFFFF)
        }

        fun getFeedbackPacketCount(buf: ByteArray, baseOffset: Int): Int =
            buf.getByteAsInt(baseOffset + FB_PACKET_COUNT_OFFSET)
        fun setFeedbackPacketCount(buf: ByteArray, baseOffset: Int, value: Int) =
            buf.set(baseOffset + FB_PACKET_COUNT_OFFSET, value.toByte())

        internal fun getPacketInfo(buf: ByteArray, baseOffset: Int) : PacketMap {
            val baseSeqNum = getBaseSeqNum(buf, baseOffset)
            val referenceTimeMs = getReferenceTimeMs(buf, baseOffset)
            val (packetStatuses, packetDeltasOffset) = getPacketStatuses(buf, baseOffset)
            val (packetDeltas, endOfDeltasOffset) = getPacketDeltas(buf, packetStatuses, packetDeltasOffset)

            return PacketMap.createFrom(referenceTimeMs, baseSeqNum, packetStatuses, packetDeltas)
        }

        /**
         * Returns the offset to the end of the last receive delta in [buf]
         */
        internal fun setPacketInfo(buf: ByteArray, baseOffset: Int, referenceTimestampMs: Long, packetInfo: PacketMap): Int {
            setBaseSeqNum(buf, baseOffset, packetInfo.firstKey())
            setPacketStatusCount(buf, baseOffset, packetInfo.size)
            setReferenceTimeMs(buf, baseOffset, referenceTimestampMs)

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
                    val symbol = packetInfo.getStatusSymbol(tccSeqNum, referenceTimestampMs)
                    if (symbol.hasDelta()) {
                        val deltaMs = packetInfo.getDeltaMs(tccSeqNum, referenceTimestampMs)!!
                        receiveDeltas.add(ReceiveDelta.create(deltaMs))
                    }
                    packetStatuses.add(symbol)
                }
                vectorChunks.add(StatusVectorChunk(2, packetStatuses))
            }
            var currChunkOffset = baseOffset + PACKET_CHUNKS_OFFSET
            vectorChunks.forEach {
                it.writeTo(buf, currChunkOffset)
                currChunkOffset += PacketStatusChunk.SIZE_BYTES
            }
            var currDeltaOffset = currChunkOffset
            receiveDeltas.forEach {
                it.writeTo(buf, currDeltaOffset)
                currDeltaOffset += it.sizeBytes
            }

            return currDeltaOffset
        }

        /**
         * Return a list of the parsed [PacketStatusSymbol]s, as well as the offset into [buf] at which
         * the deltas start
         */
        private fun getPacketStatuses(buf: ByteArray, baseOffset: Int) : Pair<List<PacketStatusSymbol>, Int> {
            val parsedPacketStatuses = mutableListOf<PacketStatusSymbol>()
            var currOffset = baseOffset + PACKET_CHUNKS_OFFSET
            val packetStatusCount = getPacketStatusCount(buf, baseOffset)

            while (parsedPacketStatuses.size < packetStatusCount) {
                PacketStatusChunk.parse(buf, currOffset).forEach { packetStatus ->
                    if (parsedPacketStatuses.size >= packetStatusCount) {
                        return@forEach
                    }
                    parsedPacketStatuses.add(packetStatus)
                }
                currOffset += PacketStatusChunk.SIZE_BYTES
            }
            return Pair(parsedPacketStatuses, currOffset)
        }

        /**
         * Return a list of the parsed [ReceiveDelta]s, as well as the offset into [buf] at which
         * the deltas ended
         */
        private fun getPacketDeltas(
                buf: ByteArray,
                packetStatuses: List<PacketStatusSymbol>,
                packetDeltaStartOffset: Int
        ) : Pair<List<ReceiveDelta>, Int> {
            var currOffset = packetDeltaStartOffset
            val packetDeltas = mutableListOf<ReceiveDelta>()

            packetStatuses.forEach { packetStatus ->
                if (packetStatus.hasDelta()) {
                    packetDeltas.add(ReceiveDelta.parse(buf, currOffset, packetStatus.getDeltaSizeBytes()))
                    currOffset += packetStatus.getDeltaSizeBytes()
                }
            }
            return Pair(packetDeltas, currOffset)
        }
    }
}

class RtcpFbTccPacketBuilder(
    val rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    var mediaSourceSsrc: Long = -1,
    val feedbackPacketCount: Int = -1
) {
    private val packetInfo = PacketMap()
    private var referenceTimeMs: Long = -1
    private var _sizeBytes: Int = -1
    private var sizeNeedsToBeRecalculated = true

    val numPackets: Int get() = packetInfo.size

    private val sizeBytes: Int
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
                    while ((dataSize + paddingSize) % 4 != 0) {
                        paddingSize++
                    }
                    _sizeBytes = RtcpFbPacket.HEADER_SIZE + dataSize + paddingSize
                    sizeNeedsToBeRecalculated = false
                } catch (e: Exception) {
//                    println("Error getting size of TCC fci: $e, buffer:\n${buf?.toHex()}")
                    throw e
                }

            }
            return _sizeBytes
        }

    fun addPacket(tccSeqNum: Int, recvTimestamp: Long): Boolean {
        if (referenceTimeMs == -1L) {
            // Mask out the lower 6 bits, since that precision will be lost
            // when we serialize it and, when calculating the deltas, we need
            // to compare other timestamps against this lower-precision value
            // (since that's how they will be reconstructed on the other
            // side).
            referenceTimeMs = recvTimestamp - (recvTimestamp % 64)
        }
        if (!canAdd(tccSeqNum, recvTimestamp)) {
            return false
        }
        sizeNeedsToBeRecalculated = true
        packetInfo[tccSeqNum] = recvTimestamp
        return true
    }

    private fun canAdd(seqNum: Int, recvTimestamp: Long): Boolean {
        if (recvTimestamp == NOT_RECEIVED_TS) {
            return true
        }
        val deltaMs = packetInfo.getDeltaMs(seqNum, recvTimestamp, referenceTimeMs)

        return deltaMs in -8192.0..8191.75
    }

    fun build(): RtcpFbTccPacket {
        val buf = BufferPool.getArray(sizeBytes)
        writeTo(buf, 0)
        return RtcpFbTccPacket(buf, 0, sizeBytes)
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        rtcpHeader.apply {
            packetType = TransportLayerRtcpFbPacket.PT
            reportCount = RtcpFbTccPacket.FMT
            length = RtpUtils.calculateRtcpLengthFieldValue(sizeBytes)
        }.writeTo(buf, offset)

        RtcpFbPacket.setMediaSourceSsrc(buf, offset, mediaSourceSsrc)
        RtcpFbTccPacket.setFeedbackPacketCount(buf, offset, feedbackPacketCount)
        RtcpFbTccPacket.setPacketInfo(buf, offset, referenceTimeMs, packetInfo)
    }
}

const val NOT_RECEIVED_TS: Long = -1

/**
 * Map the tcc sequence number to the timestamp at which that packet
 * was received
 */
internal class PacketMap : TreeMap<Int, Long>(RtpUtils.rtpSeqNumComparator), Cloneable {
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

    /**
     * Looks up the receive timestamp for [tccSeqNum] and then computes the delta
     * for the previously received packet (or [referenceTime] if there were no
     * previously received packets)
     */
    fun getDeltaMs(tccSeqNum: Int, referenceTime: Long): Double? {
        val timestamp = getOrDefault(tccSeqNum, NOT_RECEIVED_TS)
        if (timestamp == NOT_RECEIVED_TS) {
            return null
        }
        return getDeltaMs(tccSeqNum, timestamp, referenceTime)
    }

    /**
     * Finds the delta between [tccSeqNum] received at [recvTimestamp] and the previously
     * received packet (by finding the first packet less than [tccSeqNum] which was received)
     */
    fun getDeltaMs(tccSeqNum: Int, recvTimestamp: Long, referenceTime: Long): Double {
        // Get the timestamp for the packet just before this one.  If there isn't one, then
        // this is the first packet so the delta is 0.0
        val previousTimestamp = getPreviousReceivedTimestamp(tccSeqNum)
        val deltaMs = when (previousTimestamp) {
            -1L -> recvTimestamp - referenceTime
            else -> recvTimestamp - previousTimestamp
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

    companion object {
        fun createFrom(
            referenceTimeMs: Long,
            baseSeqNum: Int,
            packetStatuses: List<PacketStatusSymbol>,
            packetDeltas: List<ReceiveDelta>
        ): PacketMap {
            val packetInfo = PacketMap()
            val deltaIter = packetDeltas.iterator()
            var previousTimestamp = referenceTimeMs
            packetStatuses.forEachIndexed { index, packetStatus ->
                val seqNum = baseSeqNum + index
                val timestamp = if (packetStatus.hasDelta()) {
                    val deltaMs = deltaIter.next().deltaMs.toLong()
                    val ts = previousTimestamp + deltaMs
                    previousTimestamp = ts
                    ts
                } else {
                    NOT_RECEIVED_TS
                }
                packetInfo[seqNum] = timestamp
            }
            return packetInfo
        }
    }
}
