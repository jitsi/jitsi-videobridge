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

import org.jitsi.impl.neomedia.rtp.FrameDesc
import org.jitsi.impl.neomedia.rtp.RTPEncodingDesc
import org.jitsi_modified.impl.neomedia.codec.video.vp8.VP8Utils
import org.jitsi.nlj.Event
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingsEvent
import org.jitsi.nlj.RtpPayloadTypeAddedEvent
import org.jitsi.nlj.RtpPayloadTypeClearEvent
import org.jitsi.nlj.forEachAs
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.transform.node.Node
import org.jitsi.nlj.util.cdebug
import org.jitsi.nlj.util.cinfo
import org.jitsi.nlj.util.toRawPacket
import org.jitsi.rtp.Packet
import org.jitsi.rtp.RtpPacket
import org.jitsi.service.neomedia.MediaType
import org.jitsi.service.neomedia.codec.Constants
import org.jitsi.service.neomedia.format.MediaFormat
import unsigned.toUByte
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Parse video packets at a codec level and set appropriate meta-information
 */
class VideoParser : Node("Video parser") {
    private val payloadFormats: MutableMap<Byte, MediaFormat> = ConcurrentHashMap()
    private var rtpEncodings: List<RTPEncodingDesc> = ArrayList()
    //TODO: i don't *think* we need concurrent here, but remember to change this if we do
//    private val frames: MutableMap<Long, FrameDesc> = mutableMapOf()

    //TODO: things we want to detect here:
    // does this packet belong to a keyframe?
    // does this packet represent the start of a frame?
    // does this packet represent the end of a frame?
    override fun doProcessPackets(p: List<PacketInfo>) {
        val outPackets = mutableListOf<PacketInfo>()
//        p.forEachAs<RtpPacket> { packetInfo, pkt ->
//            val pt = pkt.header.payloadType.toUByte()
//            payloadFormats[pt]?.let { format ->
//                val videoRtpPacket = VideoRtpPacket(pkt.getBuffer())
//                val rtpEncodingDesc = getEncoding(videoRtpPacket) ?: run {
//                    logger.cdebug {
//                        "Couldn't find rtpencoding desc " +
//                                "for video packet ${videoRtpPacket.header.ssrc} ${videoRtpPacket.header.sequenceNumber}"
//                    }
//                    return@forEachAs
//                }
////                val frameDesc = frames.computeIfAbsent(videoRtpPacket.header.timestamp) { FrameDesc(videoRtpPacket.header.timestamp, System.currentTimeMillis()) }
////                val frameDesc = frames.computeIfAbsent(videoRtpPacket.header.timestamp) { FrameDesc(rtpEncodingDesc, videoRtpPacket.toRawPacket(), System.currentTimeMillis()) }
//                rtpEncodingDesc.update(videoRtpPacket.toRawPacket(), System.currentTimeMillis())
//                val frameDesc = rtpEncodingDesc.findFrameDesc(videoRtpPacket.header.timestamp)
//                videoRtpPacket.frameDesc = frameDesc
//                videoRtpPacket.trackEncodings = rtpEncodings.toTypedArray()
//                //TODO: this will need some cleanup to allow for other codecs (and other methods of denoting these
//                // things like frame markings)
////                if (format.encoding == Constants.VP8) {
//////                    videoRtpPacket.isKeyFrame = VP8Utils.isKeyFrame(videoRtpPacket.payload)
////                    if (VP8Utils.isKeyFrame(videoRtpPacket.payload)) {
//////                        logger.cdebug { "BRIAN: detected packet ${pkt.header.ssrc} ${pkt.header.sequenceNumber} is part of a key frame" }
////                        frameDesc.independent = true
////                    }
////                    if (VP8Utils.isStartOfFrame(videoRtpPacket.payload)) {
////                        frameDesc.start = videoRtpPacket.header.sequenceNumber
//////                        logger.cdebug { "BRIAN: detected packet ${pkt.header.ssrc} ${pkt.header.sequenceNumber} is the start of a frame" }
////                    } else if (videoRtpPacket.header.marker) {
//////                        logger.cdebug { "BRIAN: detected packet ${pkt.header.ssrc} ${pkt.header.sequenceNumber} is the end of a frame" }
////                        frameDesc.end = videoRtpPacket.header.sequenceNumber
////                    }
////                }
//                packetInfo.packet = videoRtpPacket
//                outPackets.add(packetInfo)
//            } ?: run {
//                outPackets.add(packetInfo)
//            }
//        }
//        next(outPackets)
        next(p)
    }

    private fun getEncoding(p: RtpPacket): RTPEncodingDesc? {
        for (encoding in rtpEncodings) {
            if (encoding.matches(p.header.ssrc)) {
                return encoding
            }
        }
        return null
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is RtpPayloadTypeAddedEvent -> {
                if (event.format.mediaType == MediaType.VIDEO) {
                    payloadFormats[event.payloadType] = event.format
                }
            }
            is RtpPayloadTypeClearEvent -> payloadFormats.clear()
            is RtpEncodingsEvent -> {
                logger.cinfo { "VideoParser got rtp encodings: ${event.rtpEncodings}" }
                rtpEncodings = event.rtpEncodings
            }
        }
        super.handleEvent(event)
    }
}
