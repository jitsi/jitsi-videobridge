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
package org.jitsi.rtp.rtcp

import toUInt
import unsigned.toUInt
import unsigned.toULong
import java.nio.ByteBuffer
import java.util.*
import kotlin.properties.Delegates

abstract class FeedbackControlInformation {
    protected abstract var buf: ByteBuffer
}

/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            PID                |             BLP               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class Nack : FeedbackControlInformation {
    override var buf: ByteBuffer
    /**
     * Packet ID (PID): 16 bits
     *  The PID field is used to specify a lost packet.  The PID field
     *  refers to the RTP sequence number of the lost packet.
     */
    var packetId: Int

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
     */
//    private var lostPacketBitmask: Int by Delegates.notNull()

    var missingSeqNums: List<Int>
        get() {
            val lostPacketBitmask = Nack.getLostPacketBitmask(buf)
            val bitSet = BitSet.valueOf(longArrayOf(lostPacketBitmask.toLong()))
            var i = bitSet.nextSetBit(0)
            val missingSeqNums = mutableListOf<Int>()
            while (i != -1) {
                missingSeqNums.add(packetId + i + 1)
                i = bitSet.nextSetBit(i + 1)
            }
            return missingSeqNums
        }
        set(missingSeqNums) {
            val bitMask: Short = 0
            val bitSet = BitSet.valueOf(longArrayOf(bitMask.toLong()))
            missingSeqNums.forEach {
                val index = it - packetId
                bitSet.set(index)
            }
            Nack.setLostPacketBitmask(buf, bitMask)
        }

    companion object {
        fun fromBuffer(buf: ByteBuffer): Nack = Nack(buf)

        fun getPacketId(buf: ByteBuffer): Int = buf.getShort(0).toUInt()
        fun setPacketId(buf: ByteBuffer, packetId: Int) {
            buf.putShort(0, packetId.toShort())
        }

        fun getLostPacketBitmask(buf: ByteBuffer): Short = buf.getShort(2)
        fun setLostPacketBitmask(buf: ByteBuffer, bitmask: Short) = buf.putShort(2, bitmask)
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf
    }

    constructor(
        packetId: Int = 0,
        missingSeqNums: List<Int> = listOf()
    ) {
        this.buf = ByteBuffer.allocate(4)
        this.packetId = packetId
        this.missingSeqNums = missingSeqNums
    }
}

/**
 * https://tools.ietf.org/html/rfc4585#section-6.2.1
 */
//class PayloadSpecificFeedbackInformation : FeedbackControlInformation() {
//
//}

/**
 * https://tools.ietf.org/html/rfc4585#section-6.1
 *    0                   1                   2                   3
 *    0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |V=2|P|   FMT   |       PT      |          length               |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of packet sender                        |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of media source                         |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    :            Feedback Control Information (FCI)                 :
 *    :                                                               :
 */
//TODO: this changes the common RTCP header (the FMT field in place of
// the RC field).  Should the header parse that field, but hold it
// generically?  Should it make it abstract?  Should it ignore it
// altogether?
class RtcpFbPacket : RtcpPacket {
    override var buf: ByteBuffer
    override var header: RtcpHeader
        get() = RtcpHeader(buf)
        set(header) {
            header.serializeToBuffer(this.buf)
        }
    var mediaSourceSsrc: Long
        get() = RtcpFbPacket.getMediaSourceSsrc(buf)
        set(mediaSourceSsrc) = RtcpFbPacket.setMediaSourceSsrc(buf, mediaSourceSsrc)

    var feedbackControlInformation: FeedbackControlInformation
        get() {
            val payloadType = header.payloadType
            val fmt = header.reportCount
            when (payloadType) {
                205 -> {

                }
                206 -> TODO()

            }
        }

    companion object {
        fun getMediaSourceSsrc(buf: ByteBuffer): Long = buf.getInt(8).toULong()
        fun setMediaSourceSsrc(buf: ByteBuffer, mediaSourceSsrc: Long) { buf.putInt(8, mediaSourceSsrc.toUInt()) }

        fun getFeedbackControlInformation(buf: ByteBuffer): FeedbackControlInformation {

        }
//        fun fromBuffer(header: RtcpHeader, buf: ByteBuffer): RtcpFbPacket {
//            val fmt = header.reportCount
//            return RtcpFbPacket().apply {
//                this.buf = buf.slice()
//                this.header = header
//                mediaSourceSsrc = buf.getInt().toULong()
//                if (header.payloadType == 205) {
//                    when (fmt) {
//                        1 -> feedbackControlInformation = Nack.fromBuffer(buf)
//                        15 -> TODO("tcc https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1")
//                        else -> throw Exception("Unrecognized RTCPFB format: $fmt")
//                    }
//                } else if (header.payloadType == 206) {
//                    println("BRIAN: got rtcfb packet with fmt $fmt")
//                    when (fmt) {
//                        1 -> TODO("pli")
//                        2 -> TODO("sli")
//                        3 -> TODO("rpsi")
//                        4 -> TODO("fir")
//                        15 -> TODO("afb")
//                    }
//                }
//            }
//        }
    }

    constructor(buf: ByteBuffer) : super() {
        this.buf = buf
    }

    constructor(fmt: Int, pt: Int, senderSsrc: Long, mediaSsrc: Long) : super() {
        this.buf = ByteBuffer.allocate(100) // TODO: size
        val header = RtcpHeader(
            reportCount = fmt,
            payloadType = pt,
            senderSsrc = senderSsrc
        )
        header.serializeToBuffer(buf)

        this.mediaSourceSsrc = mediaSsrc
    }

    override val size: Int
        get() = (header.length + 1) * 4

    override fun serializeToBuffer(buf: ByteBuffer) {
        header.serializeToBuffer(buf)
    }

}
