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

import org.jitsi.rtp.NewRawPacket
import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.putBitAsBoolean
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import java.nio.ByteBuffer
import kotlin.experimental.and

/**
 * https://tools.ietf.org/html/rfc6464#section-3
 * TODO: this can be held as either 1 byte or 2 byte. (though webrtc clients
 * appear to all use 1 byte)
 *
 *  0                   1
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   | len=0 |V| level       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class AudioLevelHeaderExtension(
    id: Int = -1,
    val containsVoice: Boolean = false,
    val audioLevel: Int = -1
) : RtpHeaderExtension(id) {
    override val dataSizeBytes: Int = 1

    override fun serializeData(buf: ByteBuffer) {
        // We set the audio level first so we don't overwrite the containsVoice
        // bit
        buf.put(buf.position(),(audioLevel and AUDIO_LEVEL_MASK).toByte())
        buf.putBitAsBoolean(buf.position(), 0, containsVoice)
    }

    companion object {
        const val AUDIO_LEVEL_MASK = 0x7F

        fun getAudioLevel(ext: NewRawPacket.HeaderExtension): Int =
            getAudioLevel(ext.buffer, ext.offset, HeaderExtensionType.ONE_BYTE_HEADER_EXT)

        /**
         * [offset] into [buf] is the start of this entire extension (not the data section)
         */
        fun getAudioLevel(buf: ByteArray, offset: Int, extType: HeaderExtensionType): Int =
            (buf.get(offset + extType.headerSizeBytes) and 0x7F).toPositiveInt()

        fun fromUnparsed(unparsedHeaderExtension: UnparsedHeaderExtension): AudioLevelHeaderExtension {
            val data = unparsedHeaderExtension.data
            val containsVoice = data.get(0).getBitAsBool(0)
            val audioLevel = data.get(0).toPositiveInt() and AUDIO_LEVEL_MASK

            return AudioLevelHeaderExtension(unparsedHeaderExtension.id, containsVoice, audioLevel)
        }
    }
}
