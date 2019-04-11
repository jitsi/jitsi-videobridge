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
package org.jitsi.nlj.transform.node.incoming

import org.jitsi.nlj.AudioLevelListener
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpExtensionClearEvent
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.RtpExtensionType.SSRC_AUDIO_LEVEL
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.cdebug
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.rtp.header_extensions.AudioLevelHeaderExtension
import unsigned.toUInt

/**
 * https://tools.ietf.org/html/rfc6464#section-3
 */
class AudioLevelReader : ObserverNode("Audio level reader") {
    private var audioLevelExtId: Int? = null
    var audioLevelListener: AudioLevelListener? = null
    companion object {
        const val MUTED_LEVEL = 127
    }

    override fun observe(packetInfo: PacketInfo) {
        val audioRtpPacket = packetInfo.packet as? AudioRtpPacket ?: return

        audioLevelExtId?.let { audioLevelId ->
            audioRtpPacket.getHeaderExtension(audioLevelId)?.let { ext ->
                val level = AudioLevelHeaderExtension.getAudioLevel(ext)
                if (level != MUTED_LEVEL) {
                    audioLevelListener?.onLevelReceived(audioRtpPacket.ssrc, (127 - level).toPositiveLong())
                } else {
                    packetInfo.shouldDiscard = true
                }
            }
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpExtensionAddedEvent -> {
                if (event.rtpExtension.type == SSRC_AUDIO_LEVEL) {
                    audioLevelExtId = event.rtpExtension.id.toUInt()
                    logger.cdebug { "Setting extension ID to $audioLevelExtId" }
                }
            }
            is RtpExtensionClearEvent -> audioLevelExtId = null
        }
    }
}
