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

package org.jitsi.rtp.rtp.header_extensions

import org.jitsi.rtp.extensions.bytearray.put3Bytes
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.getBitsAsInt
import org.jitsi.rtp.util.getShortAsInt
import java.time.Instant

/**
 * https://webrtc.org/experiments/rtp-hdrext/abs-send-time/
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | ID   |  LEN   |         AbsSendTime value                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class AbsSendTimeHeaderExtension {
    companion object {
        const val DATA_SIZE_BYTES = 3
        /**
         * One billion.
         */
        private const val b = 1_000_000_000

        fun setTime(ext: RtpPacket.HeaderExtension, timestampNanos: Long) =
            setTime(ext.currExtBuffer, ext.currExtOffset, timestampNanos)

        fun setTime(buf: ByteArray, offset: Int, timestampNanos: Long) {
            val fraction = ((timestampNanos % b) * (1 shl 18) / b)
            val seconds = ((timestampNanos / b) % 64) // 6 bits only

            val timestamp = ((seconds shl 18) or fraction) and 0x00FFFFFF

            buf.put3Bytes(offset + RtpPacket.HEADER_EXT_HEADER_SIZE, timestamp.toInt())
        }

        /**
         * Gets the timestamp converted to nanoseconds.
         */
        fun getTime(ext: RtpPacket.HeaderExtension): Instant = getTime(ext.currExtBuffer, ext.currExtOffset)
        fun getTime(buf: ByteArray, baseOffset: Int): Instant {
            val offset = baseOffset + RtpPacket.HEADER_EXT_HEADER_SIZE
            val seconds = buf.getBitsAsInt(offset, 0, 6)
            val fraction =
                (buf.getBitsAsInt(offset, 6, 2) + buf.getShortAsInt(offset + 1)).toDouble() / 0x03ffff

            val instantMillis = Instant.ofEpochSecond(seconds.toLong())
            return instantMillis.plusNanos((fraction * b).toLong())
        }
    }
}
