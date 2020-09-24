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

package org.jitsi.nlj.rtp

import org.jitsi.nlj.util.shiftPayloadRight
import org.jitsi.rtp.extensions.bytearray.putShort
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.getShortAsInt

/**
 * https://tools.ietf.org/html/rfc4588#section-4
 *
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
class RtxPacket {
    companion object {
        fun getOriginalSequenceNumber(rtxPacket: RtpPacket): Int =
            rtxPacket.buffer.getShortAsInt(rtxPacket.offset + rtxPacket.headerLength)

        /**
         * Removes the original sequence number by shifting the header 2
         * bytes to the right
         */
        fun removeOriginalSequenceNumber(rtxPacket: RtpPacket) = rtxPacket.apply {
            // Remove the original sequence number by moving the RTP header 2 bytes to the right
            // Note this changes the buffer underlying the RtpPacket -- this is safe (but fragile)
            // because we leave header values (which are cached) unchanged.
            System.arraycopy(buffer, offset, buffer, offset + 2, headerLength)
            offset += 2
            length -= 2
        }

        fun addOriginalSequenceNumber(rtpPacket: RtpPacket) = rtpPacket.apply {
            // TODO: possible optimization to try to shift the header left instead
            shiftPayloadRight(2)
            buffer.putShort(offset + headerLength, sequenceNumber.toShort())
        }
    }
}
