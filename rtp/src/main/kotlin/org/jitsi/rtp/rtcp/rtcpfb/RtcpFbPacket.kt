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

import org.jitsi.rtp.extensions.bytearray.getInt
import org.jitsi.rtp.extensions.bytearray.putInt
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.PayloadSpecificRtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbFirPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbPliPacket
import org.jitsi.rtp.rtcp.rtcpfb.payload_specific_fb.RtcpFbRembPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.RtcpFbNackPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.TransportLayerRtcpFbPacket
import org.jitsi.rtp.rtcp.rtcpfb.transport_layer_fb.tcc.RtcpFbTccPacket

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
    buffer: ByteArray,
    offset: Int,
    length: Int
) : RtcpPacket(buffer, offset, length) {

    var mediaSourceSsrc: Long
        get() = getMediaSourceSsrc(buffer, offset)
        set(value) = setMediaSourceSsrc(buffer, offset, value)

    companion object {
        val PACKET_TYPES = listOf(TransportLayerRtcpFbPacket.PT, PayloadSpecificRtcpFbPacket.PT)
        const val MEDIA_SOURCE_SSRC_OFFSET = RtcpHeader.SIZE_BYTES
        const val HEADER_SIZE = RtcpHeader.SIZE_BYTES + 4
        const val FCI_OFFSET = HEADER_SIZE

        fun getFmt(buf: ByteArray, baseOffset: Int): Int =
            RtcpHeader.getReportCount(buf, baseOffset)
        fun getMediaSourceSsrc(buf: ByteArray, baseOffset: Int): Long =
            buf.getInt(baseOffset + MEDIA_SOURCE_SSRC_OFFSET).toPositiveLong()
        fun setMediaSourceSsrc(buf: ByteArray, baseOffset: Int, value: Long) =
            buf.putInt(baseOffset + MEDIA_SOURCE_SSRC_OFFSET, value.toInt())

        fun parse(buf: ByteArray, offset: Int, length: Int): RtcpFbPacket {
            val packetType = RtcpHeader.getPacketType(buf, offset)
            val fmt = getFmt(buf, offset)
            return when (packetType) {
                TransportLayerRtcpFbPacket.PT -> {
                    when (fmt) {
                        RtcpFbNackPacket.FMT -> RtcpFbNackPacket(buf, offset, length)
                        RtcpFbTccPacket.FMT -> RtcpFbTccPacket(buf, offset, length)
                        else -> UnsupportedRtcpFbPacket(buf, offset, length)
                    }
                }
                PayloadSpecificRtcpFbPacket.PT -> {
                    when (fmt) {
                        RtcpFbFirPacket.FMT -> RtcpFbFirPacket(buf, offset, length)
                        RtcpFbPliPacket.FMT -> RtcpFbPliPacket(buf, offset, length)
                        RtcpFbRembPacket.FMT -> RtcpFbRembPacket(buf, offset, length)
                        else -> UnsupportedRtcpFbPacket(buf, offset, length)
                    }
                }
                else -> throw Exception("Unrecognized RTCPFB payload type: ${packetType.toString(16)}")
            }
        }
    }
}
