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

import unsigned.toUInt
import java.nio.ByteBuffer
import java.util.*

/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class Nack : FeedbackControlInformation {
    override var buf: ByteBuffer? = null
    override val size: Int = 4
    /**
     * Packet ID (PID): 16 bits
     *  The PID field is used to specify a lost packet.  The PID field
     *  refers to the RTP sequence number of the lost packet.
     */
    private var packetId: Int

    /**
     * bitmask of following lost packets (BLP): 16 bits
     *  The BLP allows for reporting losses of any of the 16 RTP packets
     *  immediately following the RTP packet indicated by the PID.  The
     *  BLP's definition is identical to that given in [6].  Denoting the
     *  BLP's least significant bit as bit 1, and its most significant bit
     *  as bit 16, then bit i of the bit mask is set to 1 if the receiver
     *  has not received RTP packet number (PID+i) (modulo 2^16) and
     *  indicates this packet is lost; bit i is set to 0 otherwise.  Note
     *  that the sender MUST NOT assume that a receiver has received a
     *  packet because its bit mask was set to 0.  For example, the least
     *  significant bit of the BLP would be set to 1 if the packet
     *  corresponding to the PID and the following packet have been lost.
     *  However, the sender cannot infer that packets PID+2 through PID+16
     *  have been received simply because bits 2 through 15 of the BLP are
     *  0; all the sender knows is that the receiver has not reported them
     *  as lost at this time.
     *
     *  NOTE that because the lost packet bitmask is not very useful as a raw
     *  field, we model it directly as a list of the missing sequence numbers.
     *  (which is also why packetId is private) TODO: maybe we should remove
     *  that member altogether as well?
     */
    var missingSeqNums: List<Int>

    companion object {
        const val FMT = 1
        const val SIZE_BYTES = 4
        fun getPacketId(buf: ByteBuffer): Int = buf.getShort(0).toUInt()
        fun setPacketId(buf: ByteBuffer, packetId: Int) {
            buf.putShort(0, packetId.toShort())
        }

        fun getLostPacketBitmask(buf: ByteBuffer, packetId: Int): List<Int> {
            val lostPacketBitmask = buf.getShort(2)
            val bitSet = BitSet.valueOf(longArrayOf(lostPacketBitmask.toLong()))
            var i = bitSet.nextSetBit(0)
            val missingSeqNums = mutableListOf<Int>()
            // The packed id denotes a missing packet as well, add that first
            missingSeqNums.add(getPacketId(buf))
            while (i != -1) {
                missingSeqNums.add(packetId + i + 1)
                i = bitSet.nextSetBit(i + 1)
            }
            return missingSeqNums

        }

        fun setLostPacketBitmask(buf: ByteBuffer, packetId: Int, missingSeqNums: List<Int>) {
            val bitMask: Short = 0
            val bitSet = BitSet.valueOf(longArrayOf(bitMask.toLong()))
            missingSeqNums.forEach {
                val index = it - packetId
                bitSet.set(index)
            }
            buf.putShort(2, bitMask)
        }
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf
        this.packetId = getPacketId(buf)
        this.missingSeqNums = getLostPacketBitmask(buf, packetId)
    }

    constructor(
        packetId: Int = 0,
        missingSeqNums: List<Int> = listOf()
    ) {
        this.packetId = packetId
        this.missingSeqNums = missingSeqNums
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(Nack.SIZE_BYTES)
        }
        setPacketId(buf!!, packetId)
        setLostPacketBitmask(buf!!, packetId, missingSeqNums)

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

