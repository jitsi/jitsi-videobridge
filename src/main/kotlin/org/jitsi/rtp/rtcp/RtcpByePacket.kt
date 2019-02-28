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

import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.Packet
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * https://tools.ietf.org/html/rfc3550#section-6.6
 *
 *       0                   1                   2                   3
 *       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *       |V=2|P|    SC   |   PT=BYE=203  |             length            |
 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *       |                           SSRC/CSRC                           |
 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *       :                              ...                              :
 *       +=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+=+
 * (opt) |     length    |               reason for leaving            ...
 *       +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class RtcpByePacket(
    header: RtcpHeader = RtcpHeader(),
    // Not including the one in the header
    private val additionalSsrcs: List<Long> = listOf(),
    val reason: String? = null,
    backingBuffer: ByteBuffer? = null
) : RtcpPacket(header.apply { packetType = PT }, backingBuffer) {

    override val sizeBytes: Int
        get() {
            val dataSize = additionalSsrcs.size * 4
            val reasonSize: Int = reason?.let {
                val fieldSize = it.toByteArray(StandardCharsets.US_ASCII).size
                // Plus 1 for the reason length field
                fieldSize + 1
            } ?: 0
            return header.sizeBytes + dataSize + reasonSize
        }

    val ssrcs: List<Long> = listOf(header.senderSsrc) + additionalSsrcs

    override fun serializeTo(buf: ByteBuffer) {
        super.serializeTo(buf)
        additionalSsrcs.stream()
                .map(Long::toInt)
                .forEach { buf.putInt(it) }
        reason?.let {
            val reasonBuf = ByteBuffer.wrap(it.toByteArray(StandardCharsets.US_ASCII))
            buf.put(reasonBuf.limit().toByte())
            buf.put(reasonBuf)
        }
        addPadding(buf)
    }

    override fun clone(): Packet =
        RtcpByePacket(header.clone(), additionalSsrcs.toList(), reason?.plus(""))

    companion object {
        const val PT: Int = 203
        fun create(buf: ByteBuffer): RtcpByePacket {
            val header = RtcpHeader.fromBuffer(buf)
            val hasReason = run {
                val packetLengthBytes = header.lengthBytes
                val headerAndSsrcsLengthBytes = header.sizeBytes + (header.reportCount - 1) * 4
                headerAndSsrcsLengthBytes < packetLengthBytes
            }
            val ssrcs = (0 until header.reportCount - 1)
                    .map(buf::getInt)
                    .map(Int::toPositiveLong)
                    .toList()

            val reason = if (hasReason) {
                val reasonLength = buf.get().toInt()
                val reasonStr = String(buf.array(), buf.position(), reasonLength)
                buf.incrementPosition(reasonLength)
                reasonStr
            } else {
                null
            }
            if (header.hasPadding) {
                consumePadding(buf)
            }
            return RtcpByePacket(header, ssrcs, reason, buf)
        }
   }
}