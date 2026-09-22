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

import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import org.jitsi.rtp.rtp.RtpPacket
import kotlin.experimental.and

/**
 * https://tools.ietf.org/html/rfc6464#section-3
 * TODO: this can be held as either 1 byte or 2 byte. (though webrtc clients appear to all use 1 byte)
 *
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   | len=0 |V| level       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class AudioLevelHeaderExtension {
    companion object {
        private const val AUDIO_LEVEL_MASK = 0x7F.toByte()
        private const val VAD_MASK = 0x80

        /** The RFC 6464 extension carries one byte: the V (VAD) bit and a 7-bit level. */
        const val DATA_SIZE_BYTES = 1

        /** The level of digital silence: -127 dBov, the quietest value the 7-bit field can express. */
        const val MUTED_LEVEL = 127

        /**
         * Write an RFC 6464 audio level into [ext] (an extension of [DATA_SIZE_BYTES] bytes, e.g. one just added
         * with [RtpPacket.addHeaderExtension]). [level] is in -dBov, 0 (full scale) to 127 (silence); [vad] is the
         * voice-activity flag (the V bit).
         */
        @JvmStatic
        fun setAudioLevel(ext: RtpPacket.HeaderExtension, level: Int, vad: Boolean) {
            require(level in 0..MUTED_LEVEL) { "Audio level $level out of range 0..$MUTED_LEVEL" }
            require(ext.dataLengthBytes >= DATA_SIZE_BYTES) { "Audio level extension needs $DATA_SIZE_BYTES byte" }
            ext.buffer[ext.dataOffset] = ((if (vad) VAD_MASK else 0) or level).toByte()
        }

        fun getAudioLevel(ext: RtpPacket.HeaderExtension): Int = getAudioLevel(ext.buffer, ext.dataOffset)

        private fun getAudioLevel(buf: ByteArray, offset: Int): Int = (buf[offset] and AUDIO_LEVEL_MASK).toPositiveInt()

        fun getVad(ext: RtpPacket.HeaderExtension): Boolean = getVad(ext.buffer, ext.dataOffset)
        private fun getVad(buf: ByteArray, offset: Int): Boolean = (buf[offset].toInt() and 0x80) != 0
    }
}
