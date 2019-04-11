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
