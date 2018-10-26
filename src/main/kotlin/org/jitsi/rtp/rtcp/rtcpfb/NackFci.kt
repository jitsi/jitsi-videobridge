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

import org.jitsi.rtp.extensions.put
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.util.RightToLeftBufferUtils
import unsigned.toUInt
import unsigned.toUShort
import java.nio.ByteBuffer

/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 *
 * The FCI field MUST contain at least one and MAY contain more than one
 * Generic NACK.
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class NackFci : FeedbackControlInformation {
    override var buf: ByteBuffer? = null
    override val fmt: Int = NackFci.FMT
    val missingSeqNums: List<Int>
    override val size: Int
        get() {
            return createNackBlocks(missingSeqNums).size * GenericNack.SIZE_BYTES
        }

    companion object {
        const val FMT = 1
        /**
         * [buf]'s position 0 should start at the beginning of the NACK blocks.  Parses all NACK blocks
         * contained from position 0 to buf.limit() and extracts the missing sequence numbers
         */
        fun getMissingSeqNums(buf: ByteBuffer): List<Int> {
            val missingSeqNums = mutableListOf<Int>()
            val numNackBlocks = buf.limit() / GenericNack.SIZE_BYTES
            for (i in 0 until numNackBlocks) {
                val nackBlock = GenericNack(buf.subBuffer(i * GenericNack.SIZE_BYTES))
                missingSeqNums += nackBlock.missingSeqNums
            }
            return missingSeqNums
        }

        /**
         * Writes the given list of missing sequence numbers into however many NACK blocks are necessary
         * to encode them.  [buf] must be large enough to contain the needed NACK blocks.
         */
        fun setMissingSeqNums(buf: ByteBuffer, missingSeqNums: List<Int>) {
            val nackBlocks = createNackBlocks(missingSeqNums)
            nackBlocks.forEachIndexed { index, nackBlock ->
                buf.put(index * GenericNack.SIZE_BYTES, nackBlock.getBuffer())
            }
        }

        /**
         * Given a list of missing sequence numbers, return a list of NACK blocks
         */
        private fun createNackBlocks(missingSeqNums: List<Int>): List<GenericNack> {
            val nackChunks = missingSeqNums.toNackBlockChunks()
            return nackChunks.map { GenericNack(it) }.toList()
        }

        /**
         * Transforms the list of ints representing lost packet sequence numbers and chunks them such that each chunk
         * represents a set of lost packet sequence numbers that can be encoded in a single NACK block
         */
        private fun List<Int>.toNackBlockChunks(): List<List<Int>> {
            val chunks = mutableListOf<MutableList<Int>>()
            val sorted = this.sorted()
            var currLostPacketsList = mutableListOf<Int>()
            sorted.forEach {
                when {
                    currLostPacketsList.isEmpty() -> {
                        currLostPacketsList.add(it)
                        chunks.add(currLostPacketsList)
                    }
                    it - currLostPacketsList.first() <= 16 -> currLostPacketsList.add(it)
                    else -> {
                        // We've reached a sequence number that won't fit in the current NACK chunk, so start
                        // a new one
                        currLostPacketsList = mutableListOf(it)
                        chunks.add(currLostPacketsList)
                    }
                }
            }
            return chunks

        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf
        this.missingSeqNums = getMissingSeqNums(buf)
    }

    constructor(
        missingSeqNums: List<Int> = listOf()
    ) {
        this.missingSeqNums = missingSeqNums
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(size)
        }
        setMissingSeqNums(buf!!, missingSeqNums)

        return buf!!
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("NACK packet")
            appendln("Missing packets: $missingSeqNums")

            toString()
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
class GenericNack {
    val missingSeqNums: List<Int>

    companion object {
        const val SIZE_BYTES = 4
        fun getPacketId(buf: ByteBuffer): Int = buf.getShort(0).toUInt()
        fun setPacketId(buf: ByteBuffer, packetId: Int) {
            buf.putShort(0, packetId.toUShort())
        }

        fun getBlp(buf: ByteBuffer): GenericNackBlp = GenericNackBlp(buf.subBuffer(2))
        fun setBlp(buf: ByteBuffer, blp: GenericNackBlp) {
            buf.put(2, blp.getBuffer())
        }
    }

    constructor(buf: ByteBuffer) {
        val packetId = getPacketId(buf)
        val blp = getBlp(buf)
        missingSeqNums = listOf(packetId) + blp.lostPacketOffsets.map { it + packetId }
    }

    constructor(missingSeqNums: List<Int>) {
        this.missingSeqNums = missingSeqNums.sorted()
    }

    fun getBuffer(): ByteBuffer {
        val buf = ByteBuffer.allocate(SIZE_BYTES)
        val packetId = missingSeqNums.first()
        setPacketId(buf, packetId)
        val blp = GenericNackBlp(missingSeqNums.map { it - packetId })
        setBlp(buf, blp)

        return buf
    }
}

class GenericNackBlp {
    val lostPacketOffsets: List<Int>
    companion object {
        /**
         * Get the sequence number offset values represent by the BLP block.  The least significant bit of the BLP
         * block is an offset of 1, since the packet ID is already used to denote that packet has been lost.
         */
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
                // We substract 1 here because the least significant bit (index 0) holds a packet offset of '1', since
                // the packet id is already used to encode a lost packet (with offset 0: the packet id itself)
                RightToLeftBufferUtils.putBit(buf, packetOffset - 1, true)
            }
        }
    }
    /**
     * [buf] must be of length 2 and its position 0 must be the start of the BLP field
     */
    constructor(buf: ByteBuffer) {
        lostPacketOffsets = getLostPacketOffsets(buf)
    }

    constructor(lostPacketOffsets: List<Int>) {
        this.lostPacketOffsets = lostPacketOffsets
        if (lostPacketOffsets.last() > 16) {
            throw Exception("Invalid NACK packet offset")
        }
    }

    fun getBuffer(): ByteBuffer {
        val buf = ByteBuffer.allocate(2)
        setLostPacketOffsets(buf, lostPacketOffsets)

        return buf
    }
}

