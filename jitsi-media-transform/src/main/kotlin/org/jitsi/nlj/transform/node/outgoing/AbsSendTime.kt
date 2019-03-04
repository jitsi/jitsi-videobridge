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
package org.jitsi.nlj.transform.node.outgoing

import org.jitsi.impl.neomedia.transform.AbsSendTimeEngine
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpExtensionAddedEvent
import org.jitsi.nlj.RtpExtensionClearEvent
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.getByteBuffer
import org.jitsi.nlj.util.toRawPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.service.neomedia.RTPExtension
import unsigned.toUInt

class AbsSendTime : TransformerNode("Absolute send time") {
    private val absSendTimeEngine = AbsSendTimeEngine()

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rawPacket = packetInfo.packet.toRawPacket()
        absSendTimeEngine.transform(rawPacket)
        // We 'lose' some information here because we have to recreate
        // whatever this packet was as an RtpPacket, but I don't think
        // this will be a problem.  Eventually we will port the old transformers
        // over to Packet from RawPacket.
        packetInfo.packet = RtpPacket.fromBuffer(rawPacket.getByteBuffer())

        return packetInfo
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpExtensionAddedEvent -> {
                if (RTPExtension.ABS_SEND_TIME_URN.equals(event.rtpExtension.uri.toString())) {
                    val absSendTimeExtId = event.extensionId.toUInt()
                    absSendTimeEngine.setExtensionID(absSendTimeExtId)
                    logger.cinfo { "AbsSendTime setting extension ID to $absSendTimeExtId" }
                }
            }
            is RtpExtensionClearEvent -> absSendTimeEngine.setExtensionID(-1)
        }
    }
}
