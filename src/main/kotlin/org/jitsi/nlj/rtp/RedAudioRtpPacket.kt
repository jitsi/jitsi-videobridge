/*
 * Copyright @ 2018 - Present, 8x8 Inc
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

import org.jitsi.rtp.rtp.RedPacketBuilder
import org.jitsi.rtp.rtp.RedPacketParser
import java.lang.IllegalStateException

class RedAudioRtpPacket(
    buffer: ByteArray,
    offset: Int,
    length: Int
) : AudioRtpPacket(buffer, offset, length) {

    private var removed = false

    fun removeRed() {
        if (removed) throw IllegalStateException("RED encapsulation already removed.")
        parser.decapsulate(this, parseRedundancy = false)
        removed = true
    }

    fun removeRedAndGetRedundancyPackets(): List<AudioRtpPacket> =
        if (removed) throw IllegalStateException("RED encapsulation already removed.")
        else parser.decapsulate(this, parseRedundancy = true).also { removed = true }

    override fun clone(): RedAudioRtpPacket =
        RedAudioRtpPacket(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length)

    companion object {
        val parser = RedPacketParser(::AudioRtpPacket)
        val builder = RedPacketBuilder(::RedAudioRtpPacket)
    }
}
