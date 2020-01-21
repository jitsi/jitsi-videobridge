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

import org.jitsi.rtp.extensions.bytearray.getInt
import org.jitsi.rtp.extensions.unsigned.toPositiveLong

/**
 * An [RtcpByePacket] cannot be changed once it has been created, so we
 * parse the fields once (lazily) and store them.
 * TODO: technically it COULD be changed, since anyone can change the buffer.
 * can we enforce immutability?
 *
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
    buffer: ByteArray,
    offset: Int,
    length: Int
) : RtcpPacket(buffer, offset, length) {
    val ssrcs: List<Long> by lazy {
        val ssrcStartOffset = offset + 4
        (0 until reportCount)
                .map { buffer.getInt(ssrcStartOffset + it * 4) }
                .map(Int::toPositiveLong)
                .toList()
    }

    val reason: String? by lazy {
        val headerAndSsrcsLengthBytes = RtcpHeader.SIZE_BYTES + (reportCount - 1) * 4
        val hasReason = headerAndSsrcsLengthBytes < length

        if (hasReason) {
            val reasonLengthOffset = offset + headerAndSsrcsLengthBytes
            val reasonLength = buffer.get(reasonLengthOffset).toInt()
            val reasonStr = String(buffer, reasonLengthOffset + 1, reasonLength)
            reasonStr
        } else {
            null
        }
    }

    override fun clone(): RtcpByePacket = RtcpByePacket(cloneBuffer(0), 0, length)

    companion object {
        const val PT: Int = 203
    }
}
