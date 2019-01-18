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

import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.transform.node.Node
import org.jitsi.rtp.RtpPacket
import org.jitsi.service.neomedia.MediaType
import org.jitsi.service.neomedia.codec.Constants
import org.jitsi.service.neomedia.format.MediaFormat
import unsigned.toUByte
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap

/**
 * Parse video packets at a codec level and set appropriate meta-information
 */
class VideoParser : Node("Video parser") {
    private val payloadFormats: MutableMap<Byte, MediaFormat> = ConcurrentHashMap()
    private var rtpEncodings: List<RTPEncodingDesc> = ArrayList()

    //TODO: things we want to detect here:
    // does this packet belong to a keyframe?
    // does this packet represent the start of a frame?
    // does this packet represent the end of a frame?
    override fun doProcessPackets(p: List<PacketInfo>) {
        val outPackets = mutableListOf<PacketInfo>()
        p.forEachAs<RtpPacket> { packetInfo, pkt ->
            val pt = pkt.header.payloadType.toUByte()
            payloadFormats[pt]?.let { format ->
                val videoRtpPacket = when (format.encoding) {
                    Constants.VP8 -> Vp8Packet(pkt.getBuffer())
                    else -> VideoRtpPacket(pkt.getBuffer())
                }
                packetInfo.packet = videoRtpPacket
                outPackets.add(packetInfo)
            } ?: run {
                logger.error("Unrecognized video payload type $pt, cannot parse video information")
                outPackets.add(packetInfo)
            }
        }
        next(outPackets)
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                if (event.format.mediaType == MediaType.VIDEO) {
                    payloadFormats[event.payloadType] = event.format
                }
            }
            is RtpPayloadTypeClearEvent -> payloadFormats.clear()
        }
        super.handleEvent(event)
    }
}
