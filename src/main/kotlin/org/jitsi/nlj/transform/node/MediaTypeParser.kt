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
package org.jitsi.nlj.transform.node

import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.cdebug
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.MediaType
import java.util.concurrent.ConcurrentHashMap

/**
 * Parse RTP packets as either [VideoRtpPacket]s or [AudioRtpPacket]s
 */
class MediaTypeParser : TransformerNode("Media type parser") {
    private val payloadTypes: MutableMap<Byte, PayloadType> = ConcurrentHashMap()

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val rtpPacket = packetInfo.packetAs<RtpPacket>()
        val mediaType = payloadTypes[rtpPacket.payloadType.toByte()]?.mediaType ?: run {
            logger.cdebug { "Unable to find format for payload type ${rtpPacket.payloadType}" }
            return packetInfo
        }
        packetInfo.packet = when (mediaType) {
            MediaType.AUDIO -> rtpPacket.toOtherType(::AudioRtpPacket)
            MediaType.VIDEO -> rtpPacket.toOtherType(::VideoRtpPacket)
            else -> throw Exception("Unrecognized media type: '$mediaType'")
        }
        return packetInfo
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> payloadTypes[event.payloadType.pt] = event.payloadType
            is RtpPayloadTypeClearEvent -> payloadTypes.clear()
        }
        super.handleEvent(event)
    }
}
