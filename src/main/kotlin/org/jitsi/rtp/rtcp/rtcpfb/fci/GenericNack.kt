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

package org.jitsi.rtp.rtcp.rtcpfb.fci

import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.Serializable
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.util.RightToLeftBufferUtils
import java.nio.ByteBuffer
import java.util.SortedSet

private fun List<Int>.chunkMaxDifference(maxDifference: Int): List<List<Int>> {
    val chunks = mutableListOf<List<Int>>()
    if (this.isEmpty()) {
        return chunks
    }
    var currentChunk = mutableListOf<Int>(first())
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
 * Models potentially multiple [GenericNack] blocks
 */
class GenericNackFci(
    val genericNacks: List<GenericNack> = listOf()
) : FeedbackControlInformation() {
    override val sizeBytes: Int = genericNacks.map(GenericNack::sizeBytes).sum()

    override fun serializeTo(buf: ByteBuffer) {
        genericNacks.forEach { it.serializeTo(buf) }
    }

    val missingSeqNums: List<Int> = genericNacks.flatMap { it.missingSeqNums }.toList()

    public override fun clone(): GenericNackFci {
        return GenericNackFci(genericNacks.map { it.clone() }.toList())
    }

    companion object {
        /**
         * [buf]'s current position should be the start of the NACK blocks
         * and its limit should be the end of those blocks
         */
        fun fromBuffer(buf: ByteBuffer): GenericNackFci {
            val genericNacks = mutableListOf<GenericNack>()
            while (buf.remaining() >= GenericNack.SIZE_BYTES) {
                genericNacks.add(GenericNack.fromBuffer(buf))
            }
            return GenericNackFci(genericNacks)
        }
        fun fromValues(missingSeqNums: SortedSet<Int>): GenericNackFci {
            val missingSeqNumChunks = missingSeqNums.toList().chunkMaxDifference(16)
            val nackBlocks = missingSeqNumChunks.map { GenericNack.fromValues(it.toSortedSet()) }.toList()
            return GenericNackFci(nackBlocks)
        }
    }
}

/**
 * Models a single Generic NACK field
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class GenericNack(
    private val packetId: Int = 0,
    private val genericNackBlp: GenericNackBlp = GenericNackBlp()
) : Serializable(), Cloneable {
    override val sizeBytes: Int = SIZE_BYTES

    val missingSeqNums: List<Int> = listOf(packetId) + genericNackBlp.lostPacketOffsets.map { it + packetId }

    override fun serializeTo(buf: ByteBuffer) {
        buf.putShort(packetId.toShort())
        genericNackBlp.serializeTo(buf)
    }

    public override fun clone(): GenericNack =
        GenericNack(packetId, genericNackBlp.clone())

    companion object {
        const val SIZE_BYTES  = 2 + GenericNackBlp.SIZE_BYTES
        fun fromBuffer(buf: ByteBuffer): GenericNack {
            val packetId = buf.short.toPositiveInt()
            val blp = GenericNackBlp.parse(buf)
            return GenericNack(packetId, blp)
        }
        fun fromValues(missingSeqNums: SortedSet<Int>): GenericNack {
            val packetId = missingSeqNums.first()
            val lostPacketOffsets = missingSeqNums.drop(1)
                    .map { it - packetId }
                    .toList()
            val nackBlp = GenericNackBlp(lostPacketOffsets)
            return GenericNack(packetId, nackBlp)
        }
    }
}

/**
 * Parse the NACK BLP field into a more user-friendly set of lost packet offsets which
 * can be applied to the packet ID to get the lost sequence numbers
 */
class GenericNackBlp(
    val lostPacketOffsets: List<Int> = listOf()
) : Serializable(), Cloneable {
    override val sizeBytes: Int = SIZE_BYTES

    override fun serializeTo(buf: ByteBuffer) {
        setLostPacketOffsets(buf.subBuffer(buf.position(), SIZE_BYTES), lostPacketOffsets)
        // setLostPacketOffsets doesn't increment the buffer as it goes, so increment it
        // manually here
        buf.incrementPosition(SIZE_BYTES)
    }

    public override fun clone(): GenericNackBlp =
        GenericNackBlp(lostPacketOffsets.toList())

    companion object {
        const val SIZE_BYTES = 2
        fun getLostPacketOffsets(buf: ByteBuffer): List<Int> {
            val lostPacketOffsets = mutableListOf<Int>()
            for (rightToLeftBitIndex in 0..15) {
                if (RightToLeftBufferUtils.getBitAsBool(buf, rightToLeftBitIndex)) {
                    lostPacketOffsets += rightToLeftBitIndex + 1
                }
            }
            return lostPacketOffsets
        }

        fun setLostPacketOffsets(buf: ByteBuffer, lostPacketOffsets: List<Int>) {
            lostPacketOffsets.forEach { packetOffset ->
                // We subtract 1 here because the least significant bit (index 0) holds a packet offset of '1', since
                // the packet id is already used to encode a lost packet (with offset 0: the packet id itself)
                RightToLeftBufferUtils.putBit(buf, packetOffset - 1, true)
            }
        }

        fun parse(buf: ByteBuffer): GenericNackBlp {
            val lostPacketOffsets = getLostPacketOffsets(buf.subBuffer(buf.position(), SIZE_BYTES))
            // getLostPacketOffsets doesn't increment the buffer as it goes, so increment it
            // manually here
            buf.incrementPosition(SIZE_BYTES)
            return GenericNackBlp(lostPacketOffsets)
        }
    }
}