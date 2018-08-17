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

import org.jitsi.rtp.extensions.subBuffer
import sun.security.util.Length
import toUInt
import unsigned.toUInt
import unsigned.toULong
import java.nio.ByteBuffer
import java.util.*

abstract class FeedbackControlInformation {
    abstract val size: Int
    protected abstract var buf: ByteBuffer?
    abstract fun getBuffer(): ByteBuffer
}

/**
 * https://tools.ietf.org/html/rfc4585#section-6.3.1
 * PLI does not require parameters.  Therefore, the length field MUST be
*  2, and there MUST NOT be any Feedback Control Information.
 */
class Pli : FeedbackControlInformation() {
    override val size: Int = 0
    override var buf: ByteBuffer? = ByteBuffer.allocate(0)

    override fun getBuffer(): ByteBuffer = buf!!

    override fun toString(): String {
        return "PLI packet"
    }
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
    override var buf: ByteBuffer? = null
    override val size: Int = 4
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

    companion object {
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
            this.buf = ByteBuffer.allocate(4)
        }
        setPacketId(buf!!, packetId)
        setLostPacketBitmask(buf!!, packetId, missingSeqNums)

        return buf!!
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("NACK packet")
            appendln("Missing packets: ${missingSeqNums}")

            toString()
        }
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
    private var buf: ByteBuffer? = null
    override var header: RtcpHeader
    var mediaSourceSsrc: Long
    var feedbackControlInformation: FeedbackControlInformation
    override val size: Int
        get() = RtcpHeader.SIZE_BYTES + 4 /* mediaSourceSsrc */ + feedbackControlInformation.size

    companion object {
        fun getMediaSourceSsrc(buf: ByteBuffer): Long = buf.getInt(8).toULong()
        fun setMediaSourceSsrc(buf: ByteBuffer, mediaSourceSsrc: Long) { buf.putInt(8, mediaSourceSsrc.toUInt()) }

        /**
         * Note that the buffer passed to these two methods, unlike in most other helpers, must already
         * begin at the start of the FCI portion of the header.
         */
        fun getFeedbackControlInformation(fciBuf: ByteBuffer, payloadType: Int, fmt: Int): FeedbackControlInformation {
            return when (payloadType) {
                205 -> {
                    when (fmt) {
                        1 -> Nack(fciBuf)
                        15 -> TODO("tcc https://tools.ietf.org/html/draft-holmer-rmcat-transport-wide-cc-extensions-01#section-3.1")
                        else -> throw Exception("Unrecognized RTCPFB format: $fmt")
                    }
                }
                206 -> {
                    when (fmt) {
                        1 -> Pli()
                        2 -> TODO("sli")
                        3 -> TODO("rpsi")
                        4 -> TODO("fir")
                        15 -> TODO("afb")
                        else -> throw Exception("Unrecognized RTCPFB format: pt 206, fmt $fmt")
                    }
                }
                else -> throw Exception("Unrecognized RTCPFB pt: $payloadType")
            }
        }
    }

    constructor(buf: ByteBuffer) : super() {
        this.buf = buf
        this.header = RtcpHeader(buf)
        this.mediaSourceSsrc = getMediaSourceSsrc(buf)
        this.feedbackControlInformation = getFeedbackControlInformation(buf.subBuffer(12), header.payloadType, header.reportCount)
    }

    constructor(
        header: RtcpHeader = RtcpHeader(),
        mediaSourceSsrc: Long = 0,
        feedbackControlInformation: FeedbackControlInformation
    ) : super() {
        this.header = header
        this.mediaSourceSsrc = mediaSourceSsrc
        this.feedbackControlInformation = feedbackControlInformation
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null) {
            this.buf = ByteBuffer.allocate(header.size + 4 + feedbackControlInformation.size)
        }
        this.buf!!.put(header.getBuffer())
        this.buf!!.putInt(mediaSourceSsrc.toUInt())
        this.buf!!.put(feedbackControlInformation.getBuffer())

        return this.buf!!
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RTCPFB packet")
            appendln(feedbackControlInformation.toString())
            toString()
        }
    }
}
