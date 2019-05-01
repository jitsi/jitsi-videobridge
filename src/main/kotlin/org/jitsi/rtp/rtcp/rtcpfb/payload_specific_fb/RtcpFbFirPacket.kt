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

package org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb

import org.jitsi.rtp.extensions.bytearray.cloneFromPool
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.rtp.extensions.bytearray.getInt
import org.jitsi.rtp.extensions.bytearray.putInt
import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket

/**
 *
 * https://tools.ietf.org/html/rfc4585#section-6.1
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|   FMT   |       PT      |          length               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of packet sender                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of media source                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                              SSRC                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | Seq nr.       |    Reserved                                   |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                              ....                             |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * https://tools.ietf.org/html/rfc5104#section-4.3.1.1
 *
 * The SSRC field in the FIR FCI block is used to set the media sender
 * SSRC, the media source SSRC field in the RTCPFB header is unused for FIR packets.
 * Within the common packet header for feedback messages (as defined in
 * section 6.1 of [RFC4585]), the "SSRC of packet sender" field
 * indicates the source of the request, and the "SSRC of media source"
 * is not used and SHALL be set to 0.  The SSRCs of the media senders to
 * which the FIR command applies are in the corresponding FCI entries.
 * A FIR message MAY contain requests to multiple media senders, using
 * one FCI entry per target media sender.
 */
// TODO: support multiple FIR blocks
class RtcpFbFirPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : PayloadSpecificRtcpFbPacket(buffer, offset, length) {

    override fun clone(): RtcpFbFirPacket {
        return RtcpFbFirPacket(buffer.cloneFromPool(), offset, length)
    }

    var mediaSenderSsrc: Long
        get() = getMediaSenderSsrc(buffer, offset)
        set(value) = setMediaSenderSsrc(buffer, offset, value)

    var seqNum: Int
        get() = getSeqNum(buffer, offset)
        set(value) = setSeqNum(buffer, offset, value)

    companion object {
        const val FMT = 4
        // TODO: support multiple FCI?
        const val SIZE_BYTES = RtcpFbPacket.HEADER_SIZE + 8

        const val MEDIA_SENDER_SSRC_OFFSET = RtcpFbPacket.FCI_OFFSET
        const val SEQ_NUM_OFFSET = MEDIA_SENDER_SSRC_OFFSET + 4

        fun getMediaSenderSsrc(buf: ByteArray, baseOffset: Int): Long =
            buf.getInt(baseOffset + MEDIA_SENDER_SSRC_OFFSET).toPositiveLong()
        fun setMediaSenderSsrc(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + MEDIA_SENDER_SSRC_OFFSET, value.toInt())

        fun getSeqNum(buf: ByteArray, baseOffset: Int): Int =
            buf.get(baseOffset + SEQ_NUM_OFFSET).toPositiveInt()
        fun setSeqNum(buf: ByteArray, baseOffset: Int, value: Int) =
            buf.set(baseOffset + SEQ_NUM_OFFSET, value.toByte())
    }
}

// TODO: we need an RtcpFbHeader so that we can fill out the mediaSourceSsrc for FB packets
class RtcpFbFirPacketBuilder(
    val rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    var mediaSenderSsrc: Long = -1,
    var firCommandSeqNum: Int = -1
) {

    fun build(): RtcpFbFirPacket {
        val buf = BufferPool.getArray(RtcpFbFirPacket.SIZE_BYTES)
        writeTo(buf, 0)
        return RtcpFbFirPacket(buf, 0, RtcpFbFirPacket.SIZE_BYTES)
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        rtcpHeader.apply {
            packetType = PayloadSpecificRtcpFbPacket.PT
            reportCount = RtcpFbFirPacket.FMT
            length = RtpUtils.calculateRtcpLengthFieldValue(RtcpFbFirPacket.SIZE_BYTES)
        }
        rtcpHeader.writeTo(buf, offset)
        RtcpFbPacket.setMediaSourceSsrc(buf, offset, 0) // SHALL be 0
        RtcpFbFirPacket.setMediaSenderSsrc(buf, offset, mediaSenderSsrc)
        RtcpFbFirPacket.setSeqNum(buf, offset, firCommandSeqNum)
    }
}
