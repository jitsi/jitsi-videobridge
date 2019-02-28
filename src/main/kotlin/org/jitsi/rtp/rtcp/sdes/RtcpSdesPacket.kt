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

package org.jitsi.rtp.rtcp.sdes

import org.jitsi.rtp.extensions.decrementPosition
import org.jitsi.rtp.rtcp.RtcpHeader
import org.jitsi.rtp.rtcp.RtcpPacket
import java.nio.ByteBuffer

/**
 * https://tools.ietf.org/html/rfc3550#section-6.5
 *        0                   1                   2                   3
 *        0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *        +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * header |V=2|P|    SC   |  PT=SDES=202  |             length            |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * chunk  |                          SSRC/CSRC_1                          |
 *   1    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                           SDES items                          |
 *        |                              ...                              |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * chunk  |                          SSRC/CSRC_2                          |
 *   2    +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *        |                           SDES items                          |
 *        |                              ...                              |
 *        +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 */
class RtcpSdesPacket(
    header: RtcpHeader = RtcpHeader(),
    // Note that these chunks should include the first chunk, which has its
    // SSRC in the header
    val sdesChunks: List<SdesChunk> = listOf(),
    backingBuffer: ByteBuffer? = null
) : RtcpPacket(header.apply { packetType = PT }, backingBuffer) {

    // Subtract 4 since the SSRC in the header are actually part of the first
    // chunk
    override val sizeBytes: Int = header.sizeBytes - 4 + sdesChunks.map(SdesChunk::sizeBytes).sum()

    override fun serializeTo(buf: ByteBuffer) {
        super.serializeTo(buf)
        // Rewind 4 bytes, since the first part of the first chunk is written
        // into the second 4 bytes of the header
        buf.decrementPosition(4)
        sdesChunks.forEach { it.serializeTo(buf) }
    }

    override fun clone(): RtcpSdesPacket =
        RtcpSdesPacket(header.clone(), sdesChunks.toList())

    companion object {
        const val PT = 202

        fun fromBuffer(buf: ByteBuffer): RtcpSdesPacket {
            val header = RtcpHeader.fromBuffer(buf)
            // Rewind 4 bytes so we can parse the ssrc in the
            // header as part of the first chunk
            buf.decrementPosition(4)
            val chunks = (0 until header.reportCount)
                    .map { SdesChunk.fromBuffer(buf) }
                    .toList()
            return RtcpSdesPacket(header, chunks, buf)
        }
    }
}
