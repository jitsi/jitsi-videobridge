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
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.rtp.rtp.RtpPacket
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parse video packets at a codec level and set appropriate meta-information
 */
class VideoParser(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : TransformerNode("Video parser") {
    private val logger = createChildLogger(parentLogger)
    private var sources: Array<MediaSourceDesc> = arrayOf()
    private val numPacketsDroppedUnknownPt = AtomicInteger()
    private val numPacketsDroppedNoEncoding = AtomicInteger()

    // TODO: things we want to detect here:
    // does this packet belong to a keyframe?
    // does this packet represent the start of a frame?
    // does this packet represent the end of a frame?
    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packetAs<RtpPacket>()
        val payloadType = streamInformationStore.rtpPayloadTypes[packet.payloadType.toByte()] ?: run {
            logger.error("Unrecognized video payload type ${packet.payloadType}, cannot parse video information")
            numPacketsDroppedUnknownPt.incrementAndGet()
            return null
        }
        try {
            when (payloadType) {
                is Vp8PayloadType -> {
                    val vp8Packet = packetInfo.packet.toOtherType(::Vp8Packet)
                    packetInfo.packet = vp8Packet
                    packetInfo.resetPayloadVerification()
                }
            }
        } catch (e: Exception) {
            logger.error("Exception parsing video packet.  Packet data is: ${packet.buffer.toHex(packet.offset, Math.min(packet.length, 80))}", e)
            return null
        }

        val videoPacket = packetInfo.packetAs<VideoRtpPacket>()
        val encodingDesc = findRtpLayerDesc(videoPacket) ?: run {
            logger.warn("Unable to find encoding matching packet! packet=$videoPacket, encodings=${sources.joinToString(separator = "\n", limit = 1, truncated = "[${sources.size - 1} more source descriptions omitted]")}")
            numPacketsDroppedNoEncoding.incrementAndGet()
            return null
        }
        videoPacket.qualityIndex = encodingDesc.index

        return packetInfo
    }

    private fun findRtpLayerDesc(packet: VideoRtpPacket): RtpLayerDesc? {
        for (source in sources) {
            source.findRtpLayerDesc(packet)?.let {
                return it
            }
        }
        return null
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                sources = event.mediaSourceDescs
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
