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
import org.jitsi.nlj.transform.node.ObserverNode
import org.jitsi.nlj.util.cinfo
import org.jitsi.rtp.extensions.unsigned.toPositiveLong
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.AudioLevelHeaderExtension
import org.jitsi.service.neomedia.RTPExtension
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
        audioLevelExtId?.let { audioLevelId ->
            val rtpPacket: RtpPacket = packetInfo.packetAs()
            rtpPacket.header.getExtensionAs(audioLevelId, AudioLevelHeaderExtension.Companion::fromUnparsed)?.let {
                val level = it.audioLevel
                if (level != MUTED_LEVEL) {
                    audioLevelListener?.onLevelReceived(rtpPacket.header.ssrc, (127 - level).toPositiveLong())
                }
            }
        }
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpExtensionAddedEvent -> {
                if (RTPExtension.SSRC_AUDIO_LEVEL_URN.equals(event.rtpExtension.uri.toString())) {
                    audioLevelExtId = event.extensionId.toUInt()
                    logger.cinfo { "Audio level reader setting extension ID to $audioLevelExtId" }
                }
            }
            is RtpExtensionClearEvent -> audioLevelExtId = null
        }
    }
}
