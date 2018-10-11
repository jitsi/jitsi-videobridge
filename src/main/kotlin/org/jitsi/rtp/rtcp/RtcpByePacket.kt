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
import org.jitsi.rtp.util.ByteBufferUtils
import toUInt
import unsigned.toUByte
import unsigned.toUInt
import unsigned.toULong
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
class RtcpByePacket : RtcpPacket {
    private var buf: ByteBuffer? = null
    override var header: RtcpHeader
    val ssrcs: MutableList<Long>
    private var reasonData: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER
    val reason: String
        get() = String(reasonData.array(), StandardCharsets.US_ASCII)

    override val size: Int
        get() {
            val dataSize = header.size - 4 + ssrcs.size * 4
            val reasonSize = if (reasonData != ByteBufferUtils.EMPTY_BUFFER) {
                val fieldSize = 1 + reasonData.limit()
                var paddingSize = 0
                while (fieldSize + paddingSize % 4 != 0) {
                    paddingSize++
                }
                fieldSize + paddingSize
            } else {
                0
            }

            return dataSize + reasonSize
        }

    companion object {
        const val PT: Int = 203
        const val SSRCS_OFFSET = 4

        /**
         * [buf]'s position 0 should be at the start of the RTCP BYE packet
         */
        fun getSsrcs(buf: ByteBuffer, numSsrcs: Int): MutableList<Long> {
            val ssrcs = mutableListOf<Long>()
            buf.position(SSRCS_OFFSET)
            repeat(numSsrcs) {
                ssrcs.add(buf.getInt().toULong())
            }
            buf.rewind()
            return ssrcs
        }

        /**
         * [buf]'s position 0 should be at the start of the RTCP BYE packet
         */
        fun setSsrcs(buf: ByteBuffer, ssrcs: Collection<Long>) {
            buf.position(SSRCS_OFFSET)
            ssrcs.forEach {
                buf.putInt(it.toUInt())
            }
            buf.rewind()
        }

        /**
         * [buf]'s position 0 should be at the beginning of the BYE packet header
         */
        fun getHeaderAndSsrcsSize(buf: ByteBuffer): Int {
            val ssrcCount = RtcpHeader.getReportCount(buf)
            val length = RtcpHeader.getLength(buf)
            return RtcpHeader.SIZE_BYTES - 4 + ssrcCount * 4
        }

        /**
         * [buf]'s position 0 should be at the beginning of the BYE packet header
         */
        fun hasReason(buf: ByteBuffer): Boolean {
            val packetLength = RtcpHeader.getLength(buf)

            return getHeaderAndSsrcsSize(buf) < packetLength
        }

        /**
         * [buf]'s position 0 should start at the beginning of the RTCP BYE header
         */
        fun getReason(buf: ByteBuffer): ByteBuffer {
            val reasonOffset = getHeaderAndSsrcsSize(buf)
            buf.position(reasonOffset)
            val length = buf.get().toUInt()
            buf.rewind()
            return buf.subBuffer(reasonOffset + 1, length)
        }

        /**
         * [buf]'s position 0 should start at the beginning of the RTCP BYE header.
         * Will write the length and reason data fields, as well as add any necessary padding
         */
        fun setReason(buf: ByteBuffer, reason: ByteBuffer) {
            val reasonOffset = getHeaderAndSsrcsSize(buf)
            buf.position(reasonOffset)
            buf.put(reason.limit().toUByte())
            buf.put(reason)
            while (buf.position() % 4 != 0) {
                buf.put(0x00)
            }
            buf.rewind()
        }
    }

    constructor(buf: ByteBuffer) {
        //TODO: i think this parsing could cause a problem if the report count is 0, as then there won't be
        // an entire rtcp header actually present, and we could index outside of the buffer. same goes for SDES.
        // Maybe RTCPHeader should just provide the static methods for parsing and we'll need to implement different
        // RTCPHeader sub-types which parse based on some different rules (this could also make the re-use/redefinition
        // of the reportCount field cleaner)
        header = RtcpHeader(buf)
        ssrcs = getSsrcs(buf, header.reportCount)
        if (hasReason(buf)) {
            reasonData = getReason(buf)
        }
        this.buf = buf.subBuffer(0, size)
    }

    override fun getBuffer(): ByteBuffer {
        val b = ByteBufferUtils.ensureCapacity(buf, size)
        b.rewind()
        b.limit(size)

        header.length = lengthValue
        header.reportCount = ssrcs.size
        b.put(header.getBuffer())
        // Rewind the buffer's position 4 bytes, since the first ssrc uses the last 4 bytes of the header
        b.position(b.position() - 4)
        setSsrcs(b, ssrcs)
        if (reasonData != ByteBufferUtils.EMPTY_BUFFER) {
            setReason(b, reasonData)
        }

        b.rewind()
        buf = b
        return b
    }

    override fun clone(): Packet = RtcpByePacket(getBuffer().clone())
}
