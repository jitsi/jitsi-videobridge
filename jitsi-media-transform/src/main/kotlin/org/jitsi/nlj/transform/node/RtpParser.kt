/*
 * Copyright @ 2019 - present 8x8 Inc
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
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.utils.MediaType
import java.util.concurrent.ConcurrentHashMap

class RtpParser : TransformerNode("RTP Parser") {
    private val payloadTypes: MutableMap<Byte, PayloadType> = ConcurrentHashMap()

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packet
        val payloadTypeNumber = RtpHeader.getPayloadType(packet.buffer, packet.offset).toByte()

        val payloadType = payloadTypes[payloadTypeNumber] ?: run {
            logger.cdebug { "Unknown payload type: $payloadTypeNumber" }
            return null
        }

        packetInfo.packet = when (payloadType.mediaType) {
            MediaType.AUDIO -> packet.toOtherType(::AudioRtpPacket)
            MediaType.VIDEO -> packet.toOtherType(::VideoRtpPacket)
            else -> throw Exception("Unrecognized media type: '${payloadType.mediaType}'")
        }

        packetInfo.resetPayloadVerification()
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
