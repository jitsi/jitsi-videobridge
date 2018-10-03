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

import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.clone
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.toHex
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
class RtcpRrPacket : RtcpPacket {
    private var buf: ByteBuffer? = null
    override var header: RtcpHeader
    var reportBlocks: MutableList<RtcpReportBlock> = mutableListOf()
    override val size: Int
        get() = RtcpHeader.SIZE_BYTES + reportBlocks.size * RtcpReportBlock.SIZE_BYTES

    companion object {
        const val PT: Int = 201
    }

    constructor(buf: ByteBuffer) {
        this.buf = buf.duplicate()
        this.header = RtcpHeader(buf)
        val reportBlockStartPos = RtcpHeader.SIZE_BYTES
        repeat (header.reportCount) {
            val reportBlock = RtcpReportBlock(buf.subBuffer(reportBlockStartPos + (it * RtcpReportBlock.SIZE_BYTES), RtcpReportBlock.SIZE_BYTES))
            reportBlocks.add(reportBlock)
        }
    }

    constructor(
        header: RtcpHeader = RtcpHeader(),
        reportBlocks: MutableList<RtcpReportBlock> = mutableListOf()
    ) {
        this.header = header
        this.reportBlocks = reportBlocks
    }

    override fun getBuffer(): ByteBuffer {
        if (this.buf == null || this.buf!!.limit() < size) {
            this.buf = ByteBuffer.allocate(size)
        }
        this.buf!!.rewind()
        header.length = lengthValue
        this.buf!!.put(header.getBuffer())
        reportBlocks.forEach {
            this.buf!!.put(it.getBuffer())
        }

        this.buf!!.rewind()
        return this.buf!!
    }

    override fun clone(): Packet {
        return RtcpRrPacket(getBuffer().clone())
    }

    override fun toString(): String {
        return with (StringBuffer()) {
            appendln("RR packet")
            appendln(super.toString())
            appendln("Report blocks:")
            reportBlocks.forEach {
                appendln("  ${it}")
            }
            toString()
        }
    }
}
