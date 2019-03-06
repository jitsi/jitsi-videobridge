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

import org.jitsi.rtp.extensions.put3Bytes
import java.nio.ByteBuffer

/**
 * https://webrtc.org/experiments/rtp-hdrext/abs-send-time/
 *
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * | ID   |  LEN   |         AbsSendTime value                     |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class AbsSendTimeHeaderExtension(
    id: Int = -1,
    val timestampNanos: Long = -1
) : RtpHeaderExtension(id) {
    override val dataSizeBytes: Int = 3

    override fun serializeData(buf: ByteBuffer) {
        val fraction = ((timestampNanos % b) * (1 shl 18) / b )
        val seconds = ((timestampNanos / b) % 64); //6 bits only

        val timestamp = ((seconds shl 18) or fraction) and 0x00FFFFFF

        buf.put3Bytes(timestamp.toInt())
    }

    companion object {
        /**
         * One billion.
         */
        private const val b = 1_000_000_000
    }
}