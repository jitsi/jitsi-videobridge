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

import org.jitsi.rtp.extensions.bytearray.toHex
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
                val rtcpPacket = try {
                    RtcpPacket.parse(buffer, currOffset, bytesRemaining)
                } catch (e: InvalidRtcpException) {
                    throw CompoundRtcpContainedInvalidDataException(buffer, offset, length, currOffset, e.reason)
                }
                rtcpPackets.add(rtcpPacket)
                currOffset += rtcpPacket.length
                bytesRemaining -= rtcpPacket.length
            }
            return rtcpPackets
        }

        operator fun invoke(packets: List<RtcpPacket>): CompoundRtcpPacket {
            val totalLength = packets.map { it.length }.sum()
            val buf = BufferPool.getArray(totalLength + BYTES_TO_LEAVE_AT_END_OF_PACKET)

            var off = 0
            packets.forEach {
                System.arraycopy(it.buffer, it.offset, buf, off, it.length)
                off += it.length
            }

            return CompoundRtcpPacket(buf, 0, totalLength)
        }

        /**
         * Create one or more compound RTCP packets from [packets], with each compound packet being
         * no more than [mtu] bytes in size (unless an individual packet is bigger than that, in which
         * case it will be in a compound packet on its own).
         */
        fun createWithMtu(packets: List<RtcpPacket>, mtu: Int = 1500): List<CompoundRtcpPacket> {
            return packets.chunkMaxSize(mtu) { it.length }.map { CompoundRtcpPacket(it) }
        }
    }

    override fun clone(): RtcpPacket = CompoundRtcpPacket(cloneBuffer(0), 0, length)
}

/**
 * Return a List of Lists, where sub-list is made up of an ordered list
 * of values pulled from [this], such that the total size of each sub-list
 * is not more than maxSize.  (Sizes are evaluated by [evaluate]. If an
 * individual element's size is more than [maxSize] it will be returned
 * in a sub-list on its own.)
 */
private fun <T : Any> List<T>.chunkMaxSize(maxSize: Int, evaluate: (T) -> Int): List<List<T>> {
    val chunks = mutableListOf<List<T>>()
    if (this.isEmpty()) {
        return chunks
    }
    var currentChunk = mutableListOf(first())
    chunks.add(currentChunk)
    var chunkSize = evaluate(currentChunk.first())
    // Ignore the first value which we already put in the current chunk
    drop(1).forEach {
        val size = evaluate(it)
        if (chunkSize + size > maxSize) {
            currentChunk = mutableListOf(it)
            chunks.add(currentChunk)
            chunkSize = size
        } else {
            currentChunk.add(it)
            chunkSize += size
        }
    }
    return chunks
}

class CompoundRtcpContainedInvalidDataException(
    compoundRtcpBuf: ByteArray,
    compoundRtcpOffset: Int,
    compoundRtcpLength: Int,
    invalidDataOffset: Int,
    invalidDataReason: String
) : Exception(
    "Compound RTCP contained invalid data.  Compound RTCP packet data is: " +
        "${compoundRtcpBuf.toHex(compoundRtcpOffset, compoundRtcpLength)} Invalid data " +
        "started at offset ${invalidDataOffset - compoundRtcpOffset} and failed due to '$invalidDataReason'"
)
