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

class CompoundRtcpPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : RtcpPacket(buffer, offset, length) {

    val packets: List<RtcpPacket> by lazy { parse(buffer, offset, length) }

    companion object {

        fun parse(buffer: ByteArray, offset: Int, length: Int): List<RtcpPacket> {
            var bytesRemaining = length
            var currOffset = offset
            val rtcpPackets = mutableListOf<RtcpPacket>()
            while (bytesRemaining >= RtcpHeader.SIZE_BYTES) {
                val rtcpPacket = parse(buffer, currOffset)
                rtcpPackets.add(rtcpPacket)
                currOffset += rtcpPacket.length
                bytesRemaining -= rtcpPacket.length
            }
            return rtcpPackets
        }

        operator fun invoke(vararg packets: RtcpPacket): CompoundRtcpPacket {
            val totalLength = packets.map { it.length }.sum()
            val buf = BufferPool.getArray(totalLength + BYTES_TO_LEAVE_AT_END_OF_PACKET)

            var off = 0
            packets.forEach {
                System.arraycopy(it.buffer, it.offset, buf, off, it.length)
                off += it.length
            }

            return CompoundRtcpPacket(buf, 0, totalLength)
        }
    }

    override fun clone(): RtcpPacket = CompoundRtcpPacket(cloneBuffer(0), 0, length)
}
