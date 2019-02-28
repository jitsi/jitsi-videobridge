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

package org.jitsi.rtp.rtcp.rtcpfb

import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.SerializedField
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.fci.FeedbackControlInformation
import java.nio.ByteBuffer

/**
 * https://tools.ietf.org/html/rfc4585#section-6.1
 *     0                   1                   2                   3
 *     0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |V=2|P|   FMT   |       PT      |          length               |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of packet sender                        |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    |                  SSRC of media source                         |
 *    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *    :            Feedback Control Information (FCI)                 :
 *    :                                                               :
 *
 *    Note that an RTCP FB packet re-interprets the standard report count
 *    (RC) field of the RTCP header as a FMT field
 */
abstract class RtcpFbPacket(
    header: RtcpHeader = RtcpHeader(),
    mediaSourceSsrc: Long = -1,
    private val fci: FeedbackControlInformation,
    backingBuffer: ByteBuffer? = null
) : RtcpPacket(header, backingBuffer) {
    private var dirty: Boolean = true

    final override val sizeBytes: Int
        get() = header.sizeBytes + 4 + fci.sizeBytes

    var mediaSourceSsrc: Long by SerializedField(mediaSourceSsrc, ::dirty)

    final override fun serializeTo(buf: ByteBuffer) {
        super.serializeTo(buf)
        buf.putInt(mediaSourceSsrc.toInt())
        fci.serializeTo(buf)

        // TCC packet may require padding, so call addPadding here
        // to add it if needed.  It should be a no-op for packets
        // which don't need it
        addPadding(buf)
    }

    companion object {
        val PACKET_TYPES = listOf(TransportLayerFbPacket.PT, PayloadSpecificFbPacket.PT)
        /**
         * The RTCP fixed-header size + the media source SSRC size
         */
        const val FIXED_HEADER_SIZE = RtcpHeader.SIZE_BYTES + 4

        fun getMediaSourceSsrc(buf: ByteBuffer): Long = buf.int.toPositiveLong()
        fun setMediaSourceSsrc(buf: ByteBuffer, mediaSourceSsrc: Long) {
            buf.putInt(mediaSourceSsrc.toInt())
        }

        fun fromBuffer(buf: ByteBuffer): RtcpFbPacket {
            val packetType = RtcpHeader.getPacketType(buf)
            val fmt = RtcpHeader.getReportCount(buf)
            return when (packetType) {
                TransportLayerFbPacket.PT -> {
                    when (fmt) {
                        RtcpFbNackPacket.FMT -> RtcpFbNackPacket.fromBuffer(buf)
                        RtcpFbTccPacket.FMT -> RtcpFbTccPacket.fromBuffer(buf)
                        else -> throw Exception("Unrecognized RTCPFB format: pt $packetType, fmt $fmt")
                    }
                }
                PayloadSpecificFbPacket.PT -> {
                    when (fmt) {
                        RtcpFbPliPacket.FMT -> RtcpFbPliPacket.fromBuffer(buf)
                        RtcpFbFirPacket.FMT -> RtcpFbFirPacket.fromBuffer(buf)
                        2 -> TODO("sli")
                        3 -> TODO("rpsi")
                        15 -> TODO("afb")
                        else -> throw Exception("Unrecognized RTCPFB format: pt $packetType, fmt $fmt")
                    }
                }
                else -> throw Exception("Unrecognized RTCPFB payload type: ${packetType.toString(16)}")
            }
        }
    }
}