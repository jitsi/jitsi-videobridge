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

package org.jitsi.rtp.rtcp

import org.jitsi.rtp.util.BufferPool
import org.jitsi.rtp.util.RtpUtils

/**
 * https://tools.ietf.org/html/rfc3550#section-6.4.2
 *
 *         0                   1                   2                   3
 *         0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * header |V=2|P|    RC   |   PT=RR=201   |             length            |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                     SSRC of packet sender                     |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * report |                 SSRC_1 (SSRC of first source)                 |
 * block  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * 1      | fraction lost |       cumulative number of packets lost       |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |           extended highest sequence number received           |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                      interarrival jitter                      |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                         last SR (LSR)                         |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                   delay since last SR (DLSR)                  |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * report |                 SSRC_2 (SSRC of second source)                |
 * block  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * 2      :                               ...                             :
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 *        |                  profile-specific extensions                  |
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class RtcpRrPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : RtcpPacket(buffer, offset, length) {
    val reportBlocks: List<RtcpReportBlock> by lazy {
        (0 until reportCount).map {
            RtcpReportBlock.fromBuffer(buffer, offset + REPORT_BLOCKS_OFFSET + it * RtcpReportBlock.SIZE_BYTES)
        }.toList()
    }

    override fun clone(): RtcpRrPacket = RtcpRrPacket(cloneBuffer(0), 0, length)

    companion object {
        const val PT = 201
        const val REPORT_BLOCKS_OFFSET = 8
    }
}

data class RtcpRrPacketBuilder(
    var rtcpHeader: RtcpHeaderBuilder = RtcpHeaderBuilder(),
    val reportBlocks: MutableList<RtcpReportBlock> = mutableListOf()
) {

    private fun getLengthValue(): Int =
        RtpUtils.calculateRtcpLengthFieldValue(sizeBytes)

    private val sizeBytes: Int
        get() = RtcpHeader.SIZE_BYTES + reportBlocks.size * RtcpReportBlock.SIZE_BYTES

    fun build(): RtcpRrPacket {
        val buf = BufferPool.getArray(sizeBytes)
        writeTo(buf, 0)
        return RtcpRrPacket(buf, 0, sizeBytes)
    }

    fun writeTo(buf: ByteArray, offset: Int) {
        rtcpHeader.apply {
            packetType = RtcpRrPacket.PT
            reportCount = reportBlocks.size
            length = getLengthValue()
        }.writeTo(buf, offset)
        reportBlocks.forEachIndexed { index, reportBlock ->
            reportBlock.writeTo(buf, offset + RtcpRrPacket.REPORT_BLOCKS_OFFSET + index * RtcpReportBlock.SIZE_BYTES)
        }
    }
}
