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
    override val size: Int
        get() = getBuffer().limit()
    val originalSequenceNmber: Int

    companion object {
        /**
         * The given buffer should start at the beginning of what is
         * detected as the RTP payload (from RTPPacket's point of view)
         * since this is where the OSN is stored.
         */
        fun getOriginalSequenceNumber(buf: ByteBuffer): Int {
            return buf.getShort(0).toUInt()
        }
    }

    constructor(buf: ByteBuffer) : super(buf) {
        originalSequenceNmber = getOriginalSequenceNumber(payload)
    }

    //TODO: add field-based ctor

    override fun clone(): Packet {
        return RtxPacket(getBuffer())
    }

    /**
     * Return an [RtpPacket] based on this one but with
     * the RTX header removed
     */
    fun toRtpPacket(): RtpPacket {
        val buf = ByteBuffer.allocate(getBuffer().limit() - 2)
        buf.put(this.getBuffer().subBuffer(0, header.size))
        buf.put(this.getBuffer().subBuffer(header.size + 2))

        return RtpPacket(buf)
    }
}
