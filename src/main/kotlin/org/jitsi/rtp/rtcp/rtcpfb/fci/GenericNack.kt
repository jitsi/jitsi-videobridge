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
import org.jitsi.rtp.util.RightToLeftBufferUtils
import java.nio.ByteBuffer

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
) : FeedbackControlInformation() {
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
            val packetId = buf.short.toInt()
            val blp = GenericNackBlp.parse(buf)
            return GenericNack(packetId, blp)
        }
        fun fromValues(missingSeqNums: List<Int>): GenericNack {
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