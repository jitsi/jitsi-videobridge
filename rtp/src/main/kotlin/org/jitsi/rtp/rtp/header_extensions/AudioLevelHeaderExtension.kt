/*
 * Copyright @ 2018 - present 8x8, Inc.
 * Copyright @ 2023 Vowel, Inc.
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

        fun getAudioLevel(ext: RtpPacket.HeaderExtension): Int =
            getAudioLevel(ext.currExtBuffer, ext.currExtOffset, ext.getHeaderSize())

        /**
         * [offset] into [buf] is the start of this entire extension (not the data section)
         */
        fun getAudioLevel(buf: ByteArray, offset: Int, headerExtensionHeaderSize: Int): Int =
            (buf[offset + headerExtensionHeaderSize] and AUDIO_LEVEL_MASK).toPositiveInt()

        fun getVad(ext: RtpPacket.HeaderExtension): Boolean =
            getVad(ext.currExtBuffer, ext.currExtOffset, ext.getHeaderSize())
        fun getVad(buf: ByteArray, offset: Int, headerExtensionHeaderSize: Int): Boolean =
            (buf[offset + headerExtensionHeaderSize].toInt() and 0x80) != 0
    }
}
