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

import org.jitsi.rtp.extensions.getBitAsBool
import org.jitsi.rtp.extensions.incrementPosition
import org.jitsi.rtp.extensions.putBitAsBoolean
import org.jitsi.rtp.extensions.subBuffer
import org.jitsi.rtp.extensions.unsigned.toPositiveInt
import java.nio.ByteBuffer

/**
 * https://tools.ietf.org/html/rfc6464#section-3
 * TODO: this can be held as either 1 byte or 2 byte. (though webrtc clients
 * appear to all use 1 byte)
 *
 * 0                   1
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |  ID   | len=0 |V| level       |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
class AudioLevelHeaderExtension(
    id: Int = -1,
    val containsVoice: Boolean = false,
    val audioLevel: Int = -1
) : RtpOneByteHeaderExtension(id) {
    override val sizeBytes: Int
        get() = HEADER_SIZE + 1

    override fun serializeTo(buf: ByteBuffer) {
        val absBuf = buf.subBuffer(buf.position())
        setId(absBuf, id)
        setLength(absBuf, 2)
        absBuf.incrementPosition(1)
        // We set the audio level first so we don't overwrite the containsVoice
        // bit
        absBuf.put(absBuf.position(), (audioLevel and AUDIO_LEVEL_MASK).toByte())
        absBuf.putBitAsBoolean(absBuf.position(), 0, containsVoice)
    }

    companion object {
        const val AUDIO_LEVEL_MASK = 0x7F

        fun fromUnknown(id: Int, data: ByteBuffer): AudioLevelHeaderExtension {
            val containsVoice = data.get(0).getBitAsBool(0)
            val audioLevel = data.get(0).toPositiveInt() and AUDIO_LEVEL_MASK

            return AudioLevelHeaderExtension(id, containsVoice, audioLevel)
        }
    }
}
