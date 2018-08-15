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

import java.nio.ByteBuffer
import kotlin.properties.Delegates


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
class RtcpRrPacket : RtcpPacket() {
    override var buf: ByteBuffer by Delegates.notNull()
    override var header: RtcpHeader by Delegates.notNull()
    var reportBlocks: List<RtcpReportBlock> = listOf()
    override var size: Int = RtcpHeader.SIZE_BYTES + reportBlocks.size * RtcpReportBlock.SIZE_BYTES
        get() = RtcpHeader.SIZE_BYTES + reportBlocks.size * RtcpReportBlock.SIZE_BYTES

    companion object Create {
        val PT: Int = 201
        fun fromBuffer(header: RtcpHeader, buf: ByteBuffer): RtcpRrPacket {
            return RtcpRrPacket().apply {
                this.buf = buf.slice()
                this.header = header
                reportBlocks = (0 until header.reportCount).map {
                    RtcpReportBlock.fromBuffer(buf)
                }
            }
        }
        fun fromValues(receiver: RtcpRrPacket.() -> Unit): RtcpRrPacket {
            val packet = RtcpRrPacket()
            packet.receiver()
            return packet
        }
    }

    override fun serializeToBuffer(buf: ByteBuffer) {
        header.serializeToBuffer(buf)
        buf.apply {
            reportBlocks.forEach { it.serializeToBuffer(buf) }
        }
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RR packet:")
            appendln("RR sender: ${header.senderSsrc}")
            reportBlocks.forEach {
                appendln(it.toString())
            }

            toString()
        }
    }
}
