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

import org.jitsi.rtp.rtcp.RtcpHeaderBuilder
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils

/**
 * https://tools.ietf.org/html/rfc4585#section-6.3.1
 *
 *  0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |V=2|P|   FMT   |       PT      |          length               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of packet sender                        |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                  SSRC of media source                         |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * PLI does not require parameters.  Therefore, the length field MUST be
 *  2, and there MUST NOT be any Feedback Control Information.
 */
class RtcpFbPliPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : PayloadSpecificRtcpFbPacket(buffer, offset, length) {

    override fun clone(): RtcpFbPliPacket = RtcpFbPliPacket(cloneBuffer(0), 0, length)

    companion object {
        const val FMT = 1
        const val SIZE_BYTES = RtcpFbPacket.HEADER_SIZE
    }
}

class RtcpFbPliPacketBuilder(
    val rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    var mediaSourceSsrc: Long = -1
) {

    fun build(): RtcpFbPliPacket {
        val buf = BufferPool.getArray(RtcpFbPliPacket.SIZE_BYTES)
        writeTo(buf, 0)
        return RtcpFbPliPacket(buf, 0, RtcpFbPliPacket.SIZE_BYTES)
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        rtcpHeader.apply {
            packetType = PayloadSpecificRtcpFbPacket.PT
            reportCount = RtcpFbPliPacket.FMT
            length = RtpUtils.calculateRtcpLengthFieldValue(RtcpFbPliPacket.SIZE_BYTES)
        }
        rtcpHeader.writeTo(buf, offset)
        RtcpFbPacket.setMediaSourceSsrc(buf, offset, mediaSourceSsrc)
    }
}