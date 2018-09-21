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
package org.jitsi.rtp

import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.toHex
import unsigned.toUInt
import unsigned.toUShort
import java.nio.ByteBuffer

/**
 * https://tools.ietf.org/html/rfc4588#section-4
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                         RTP Header                            |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |            OSN                |                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+                               |
 * |                  Original RTP Packet Payload                  |
 * |                                                               |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class RtxPacket : RtpPacket {
    var originalSequenceNumber: Int = 0

    companion object {
        /**
         * The given buffer should start at the beginning of what is
         * detected as the RTP payload (from RTPPacket's point of view)
         * since this is where the OSN is stored.
         */
        fun getOriginalSequenceNumber(buf: ByteBuffer): Int {
            return buf.getShort(0).toUInt()
        }
        /**
        * The given buffer should start at the beginning of what is
        * detected as the RTP payload (from RTPPacket's point of view)
        * since this is where the OSN is stored.  The payload data should
        * have already been shifted to make room for the OSN.
        */
        fun setOriginalSequenceNumber(buf: ByteBuffer, originalSeqNum: Int) {
            buf.putShort(0, originalSeqNum.toUShort())
        }
    }

    constructor(buf: ByteBuffer) : super(buf) {
        originalSequenceNumber = getOriginalSequenceNumber(payload)
    }

    constructor(rtpPacket: RtpPacket) : super(rtpPacket.header, rtpPacket.payload) {
        // Create a new payload buffer with room for the original sequence number
        val newPayloadBuf = ByteBuffer.allocate(payload.limit() + 2)
        (newPayloadBuf.position(2) as ByteBuffer).put(payload)
        newPayloadBuf.rewind()
        this.payload = newPayloadBuf
        this.originalSequenceNumber = rtpPacket.header.sequenceNumber
    }

    override fun getBuffer(): ByteBuffer {
        setOriginalSequenceNumber(payload, originalSequenceNumber)
        return super.getBuffer()
    }

    override fun clone(): Packet {
        return RtxPacket(getBuffer())
    }

    /**
     * Return an [RtpPacket] based on this one but with
     * the RTX original sequence number removed
     */
    fun toRtpPacket(): RtpPacket {
        val buf = ByteBuffer.allocate(getBuffer().limit() - 2)
        buf.put(this.getBuffer().subBuffer(0, header.size))
        buf.put(this.getBuffer().subBuffer(header.size + 2))

        return RtpPacket(buf)
    }
}
