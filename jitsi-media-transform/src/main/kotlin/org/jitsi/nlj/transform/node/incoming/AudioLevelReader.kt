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

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpExtensionClearEvent
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.transform.node.Node
import org.jitsi.rtp.RtpPacket
import org.jitsi.service.neomedia.RTPExtension
import org.jitsi.service.neomedia.event.CsrcAudioLevelListener
import unsigned.toUInt
import kotlin.experimental.and

class AudioLevelReader : Node("Audio level reader") {
    private var audioLevelExtId: Int? = null
    var csrcAudioLevelListener: CsrcAudioLevelListener? = null
    override fun doProcessPackets(p: List<PacketInfo>) {
        audioLevelExtId?.let { audioLevelId ->
            p.forEachAs<RtpPacket> currPkt@ { _, pkt ->
                val levelExt = pkt.header.getExtension(audioLevelId) ?: return@currPkt
                val level = (levelExt.data.get() and 0x7F).toLong()
                csrcAudioLevelListener?.audioLevelsReceived(longArrayOf(pkt.header.ssrc, level))
            }
        }
        next(p)
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpExtensionAddedEvent -> {
                if (RTPExtension.SSRC_AUDIO_LEVEL_URN.equals(event.rtpExtension.uri.toString())) {
                    audioLevelExtId = event.extensionId.toUInt()
                    println("Audio level reader setting extension ID to $audioLevelExtId")
                }
            }
            is RtpExtensionClearEvent -> audioLevelExtId = null
        }
    }
}
