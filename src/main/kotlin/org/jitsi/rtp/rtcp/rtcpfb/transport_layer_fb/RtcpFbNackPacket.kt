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

package org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb

import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.extensions.bytearray.getShort
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import java.util.SortedSet

/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|   FMT   |       PT      |          length               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of packet sender                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of media source                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | (optional) PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 */
class RtcpFbNackPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : TransportLayerRtcpFbPacket(buffer, offset, length) {

    private val numNackBlocks: Int =
        (packetLength - RtcpFbPacket.HEADER_SIZE) / NackBlock.SIZE_BYTES

    val missingSeqNums: SortedSet<Int> by lazy {
        (0 until numNackBlocks)
                .map {
                    NackBlock.getMissingSeqNums(buffer, offset + NACK_BLOCK_OFFSET + it * NackBlock.SIZE_BYTES)
                }
                .flatten()
                .toSortedSet()
    }

    override fun clone(): RtcpFbNackPacket = RtcpFbNackPacket(cloneBuffer(0), 0, length)

    companion object {
        const val FMT = 1
        const val NACK_BLOCK_OFFSET = RtcpFbPacket.HEADER_SIZE
    }
}

class RtcpFbNackPacketBuilder(
    val rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    var mediaSourceSsrc: Long = -1,
    val missingSeqNums: SortedSet<Int> = sortedSetOf()
) {
    private val nackBlocks: List<NackBlock> =
        missingSeqNums.toList().chunkMaxDifference(16)
                .map(List<Int>::toSortedSet)
                .map(::NackBlock)
                .toList()

    private val sizeBytes: Int =
        RtcpFbPacket.HEADER_SIZE + nackBlocks.size * NackBlock.SIZE_BYTES

    fun build(): RtcpFbNackPacket {
        val buf = BufferPool.getArray(sizeBytes)
        writeTo(buf, 0)
        return RtcpFbNackPacket(buf, 0, sizeBytes)
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        rtcpHeader.apply {
            packetType = TransportLayerRtcpFbPacket.PT
            reportCount = RtcpFbNackPacket.FMT
            length = RtpUtils.calculateRtcpLengthFieldValue(sizeBytes)
        }
        rtcpHeader.writeTo(buf, offset)
        RtcpFbPacket.setMediaSourceSsrc(buf, offset, mediaSourceSsrc)
        nackBlocks.forEachIndexed { index, nackBlock ->
            nackBlock.writeTo(buf, offset + RtcpFbNackPacket.NACK_BLOCK_OFFSET + index * NackBlock.SIZE_BYTES)
        }
    }
}

/**
 * Return a List of Lists, where sub-list is made up of an ordered list
 * of values pulled from [this], such that the difference between the
 * first element and the last element is not more than [maxDifference]
 */
private fun List<Int>.chunkMaxDifference(maxDifference: Int): List<List<Int>> {
    val chunks = mutableListOf<List<Int>>()
    if (this.isEmpty()) {
        return chunks
    }
    var currentChunk = mutableListOf(first())
    chunks.add(currentChunk)
    // Ignore the first value which we already put in the current chunk
    drop(1).forEach {
        if (it - currentChunk.first() > maxDifference) {
            currentChunk = mutableListOf(it)
            chunks.add(currentChunk)
        } else {
            currentChunk.add(it)
        }
    }
    return chunks
}

/**
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
private class NackBlock(
    val missingSeqNums: SortedSet<Int> = sortedSetOf()
) {

    fun writeTo(buf: ByteArray, offset: Int) {
        putMissingSeqNums(buf, offset, missingSeqNums)
    }

    companion object {
        const val SIZE_BYTES = 4
        fun getMissingSeqNums(buf: ByteArray, offset: Int): List<Int> {
            val packetId = buf.getShort(offset + 0).toPositiveInt()
            val blp = buf.getShort(offset + 2).toPositiveInt()
            val missingSeqNums = mutableListOf(packetId)
            for (shiftAmount in 0..15) {
                if (((blp ushr shiftAmount) and 0x1) == 1) {
                    missingSeqNums.add(packetId + shiftAmount + 1)
                }
            }
            return missingSeqNums
        }

        /**
         * [missingSeqNums].last() - [missingSeqNums].first() MUST be <= 16
         * [offset] should point to the start of where this NackBlock will go
         */
        fun putMissingSeqNums(buf: ByteArray, offset: Int, missingSeqNums: SortedSet<Int>) {
            val packetId = missingSeqNums.first()
            buf.putShort(offset, packetId.toShort())
            var blpField = 0
            for (bitPos in 16 downTo 1) {
                if ((bitPos + packetId) in missingSeqNums) {
                    blpField = blpField or 1
                }
                // Don't shift the last time
                if (bitPos != 1) {
                    blpField = blpField shl 1
                }
            }
            buf.putShort(offset + 2, blpField.toShort())
        }
    }
}
