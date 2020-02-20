/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
import org.jitsi.nlj.SetMediaStreamTracksEvent
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi_modified.impl.neomedia.rtp.MediaStreamTrackDesc
import org.jitsi_modified.impl.neomedia.rtp.RTPEncodingDesc
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parse video packets at a codec level and set appropriate meta-information
 */
class VideoParser(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : TransformerNode("Video parser") {
    private val logger = createChildLogger(parentLogger)
    private var tracks: Array<MediaStreamTrackDesc> = arrayOf()
    private val numPacketsDroppedUnknownPt = AtomicInteger()
    private val numPacketsDroppedNoEncoding = AtomicInteger()

    // TODO: things we want to detect here:
    // does this packet belong to a keyframe?
    // does this packet represent the start of a frame?
    // does this packet represent the end of a frame?
    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val videoPacket = packetInfo.packetAs<VideoRtpPacket>()
        val payloadType = streamInformationStore.rtpPayloadTypes[videoPacket.payloadType.toByte()] ?: run {
            logger.error("Unrecognized video payload type ${videoPacket.payloadType}, cannot parse video information")
            numPacketsDroppedUnknownPt.incrementAndGet()
            return null
        }
        val encodingDesc = findRtpEncodingDesc(videoPacket) ?: run {
            logger.warn("Unable to find encoding matching packet! packet=$videoPacket, encodings=${tracks.joinToString(separator = "\n")}")
            numPacketsDroppedNoEncoding.incrementAndGet()
            return null
        }
        try {
            when (payloadType) {
                is Vp8PayloadType -> {
                    val vp8Packet = videoPacket.toOtherType(::Vp8Packet)
                    vp8Packet.qualityIndex = encodingDesc.index
                    packetInfo.packet = vp8Packet
                    packetInfo.resetPayloadVerification()
                }
            }
        } catch (e: Exception) {
            logger.error("Exception parsing video packet.  Packet data is: ${videoPacket.buffer.toHex(videoPacket.offset, Math.min(videoPacket.length, 80))}", e)
            return null
        }
        return packetInfo
    }

    private fun findRtpEncodingDesc(packet: VideoRtpPacket): RTPEncodingDesc? {
        for (track in tracks) {
            track.findRtpEncodingDesc(packet)?.let {
                return it
            }
        }
        return null
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaStreamTracksEvent -> {
                tracks = event.mediaStreamTrackDescs
            }
        }
        super.handleEvent(event)
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_packets_dropped_no_encoding", numPacketsDroppedNoEncoding.get())
            addNumber("num_packets_dropped_unknown_pt", numPacketsDroppedUnknownPt.get())
        }
    }
}
