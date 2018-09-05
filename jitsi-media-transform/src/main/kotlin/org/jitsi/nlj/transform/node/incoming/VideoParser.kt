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

import org.jitsi.impl.neomedia.codec.video.vp8.VP8Utils
import org.jitsi_modified.impl.neomedia.rtp.FrameDesc
import org.jitsi.nlj.Event
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.forEachAs
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.service.neomedia.MediaType
import org.jitsi.service.neomedia.codec.Constants
import org.jitsi.service.neomedia.format.MediaFormat
import unsigned.toUByte
import java.util.concurrent.ConcurrentHashMap

/**
 * Parse video packets at a codec level and set appropriate meta-information
 */
class VideoParser : Node("Video parser") {
    private val payloadFormats: MutableMap<Byte, MediaFormat> = ConcurrentHashMap()
    //TODO: i don't *think* we need concurrent here, but remember to change this if we do
    private val frames: MutableMap<Long, FrameDesc> = mutableMapOf()

    //TODO: things we want to detect here:
    // does this packet belong to a keyframe?
    // does this packet represent the start of a frame?
    // does this packet represent the end of a frame?
    override fun doProcessPackets(p: List<Packet>) {
        val outPackets = mutableListOf<RtpPacket>()
        p.forEachAs<RtpPacket> { pkt ->
            val pt = pkt.header.payloadType.toUByte()
            payloadFormats[pt]?.let { format ->
                val videoRtpPacket = VideoRtpPacket(pkt.getBuffer())
                val frameDesc = frames.computeIfAbsent(videoRtpPacket.header.timestamp) { FrameDesc(videoRtpPacket.header.timestamp, System.currentTimeMillis()) }
                videoRtpPacket.frameDesc = frameDesc
                //TODO: this will need some cleanup to allow for other codecs (and other methods of denoting these
                // things like frame markings)
                if (format.encoding == Constants.VP8) {
//                    videoRtpPacket.isKeyFrame = VP8Utils.isKeyFrame(videoRtpPacket.payload)
                    if (VP8Utils.isKeyFrame(videoRtpPacket.payload)) {
                        println("BRIAN: detected packet ${pkt.header.ssrc} ${pkt.header.sequenceNumber} is part of a key frame")
                        frameDesc.independent = true
                    }
                    if (VP8Utils.isStartOfFrame(videoRtpPacket.payload)) {
                        frameDesc.start = videoRtpPacket.header.sequenceNumber
                        println("BRIAN: detected packet ${pkt.header.ssrc} ${pkt.header.sequenceNumber} is the start of a frame")
                    } else if (videoRtpPacket.header.marker) {
                        println("BRIAN: detected packet ${pkt.header.ssrc} ${pkt.header.sequenceNumber} is the end of a frame")
                        frameDesc.end = videoRtpPacket.header.sequenceNumber
                    }
                }
                outPackets.add(videoRtpPacket)
            } ?: run {
                outPackets.add(pkt)
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
