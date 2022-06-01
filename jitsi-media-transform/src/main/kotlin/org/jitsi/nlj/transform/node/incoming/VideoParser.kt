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
import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.SetMediaSourcesEvent
import org.jitsi.nlj.copy
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.nlj.format.Vp9PayloadType
import org.jitsi.nlj.rtp.codec.VideoCodecParser
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.rtp.codec.vp8.Vp8Parser
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.nlj.rtp.codec.vp9.Vp9Parser
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.cdebug

/**
 * Parse video packets at a codec level
 */
class VideoParser(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : TransformerNode("Video parser") {
    private val logger = createChildLogger(parentLogger)
    private val stats = Stats()

    private var sources: Array<MediaSourceDesc> = arrayOf()
    private var signaledSources: Array<MediaSourceDesc> = sources

    private var videoCodecParser: VideoCodecParser? = null

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packetAs<RtpPacket>()
        val payloadType = streamInformationStore.rtpPayloadTypes[packet.payloadType.toByte()] ?: run {
            logger.error("Unrecognized video payload type ${packet.payloadType}, cannot parse video information")
            stats.numPacketsDroppedUnknownPt++
            return null
        }
        val parsedPacket = try {
            when (payloadType) {
                is Vp8PayloadType -> {
                    val vp8Packet = packetInfo.packet.toOtherType(::Vp8Packet)
                    packetInfo.packet = vp8Packet
                    packetInfo.resetPayloadVerification()

                    if (videoCodecParser !is Vp8Parser) {
                        logger.cdebug {
                            "Creating new VP8Parser, current videoCodecParser is ${videoCodecParser?.javaClass}"
                        }
                        resetSources()
                        packetInfo.layeringChanged = true
                        videoCodecParser = Vp8Parser(sources, logger)
                    }
                    vp8Packet
                }
                is Vp9PayloadType -> {
                    val vp9Packet = packetInfo.packet.toOtherType(::Vp9Packet)
                    packetInfo.packet = vp9Packet
                    packetInfo.resetPayloadVerification()

                    if (videoCodecParser !is Vp9Parser) {
                        logger.cdebug {
                            "Creating new VP9Parser, current videoCodecParser is ${videoCodecParser?.javaClass}"
                        }
                        resetSources()
                        packetInfo.layeringChanged = true
                        videoCodecParser = Vp9Parser(sources, logger)
                    }
                    vp9Packet
                }
                else -> {
                    if (videoCodecParser != null) {
                        logger.cdebug {
                            "Removing videoCodecParser on ${payloadType.javaClass} packet, " +
                                "current videoCodecParser is ${videoCodecParser?.javaClass}"
                        }
                        resetSources()
                        packetInfo.layeringChanged = true
                        videoCodecParser = null
                    }
                    return packetInfo
                }
            }.also {
                videoCodecParser?.parse(packetInfo)
            }
        } catch (e: Exception) {
            logger.error(
                "Exception parsing video packet.  Packet data is: " +
                    packet.buffer.toHex(packet.offset, Math.min(packet.length, 80)),
                e
            )
            return null
        }

        /* Some codecs mark keyframes in every packet of the keyframe - only count the start of the frame,
         * so the count is correct. */
        /* Alternately we could keep track of keyframes we've already seen, by timestamp, but that seems unnecessary. */
        if (parsedPacket.isKeyframe && parsedPacket.isStartOfFrame) {
            logger.cdebug { "Received a keyframe for ssrc ${packet.ssrc} ${packet.sequenceNumber}" }
            stats.numKeyframes++
        }
        if (packetInfo.layeringChanged) {
            logger.cdebug { "Layering structure changed for ssrc ${packet.ssrc} ${packet.sequenceNumber}" }
            stats.numLayeringChanges++
        }

        return packetInfo
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                sources = event.mediaSourceDescs
                signaledSources = sources.copy()
                videoCodecParser?.sources = sources
            }
        }
        super.handleEvent(event)
    }

    private fun resetSources() {
        logger.cdebug { "Resetting sources to signaled sources: ${signaledSources.joinToString(separator = "\n")}" }
        for (signaledSource in signaledSources) {
            for (source in sources) {
                if (source.primarySSRC != signaledSource.primarySSRC) {
                    continue
                }
                for (signaledEncoding in signaledSource.rtpEncodings) {
                    source.setEncodingLayers(signaledEncoding.layers, signaledEncoding.primarySSRC)
                }
                break
            }
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply { stats.addToNodeStatsBlock(this) }

    fun getStats() = stats.snapshot()

    class Stats {
        var numKeyframes = 0
        var numLayeringChanges = 0
        var numPacketsDroppedUnknownPt = 0

        fun snapshot() = Snapshot(numKeyframes, numLayeringChanges, numPacketsDroppedUnknownPt)

        fun addToNodeStatsBlock(nodeStatsBlock: NodeStatsBlock) = nodeStatsBlock.apply {
            addNumber("num_packets_dropped_unknown_pt", numPacketsDroppedUnknownPt)
            addNumber("num_keyframes", numKeyframes)
            addNumber("num_layering_changes", numLayeringChanges)
        }

        data class Snapshot(
            val numKeyframes: Int,
            var numLayeringChanges: Int,
            var numPacketsDroppedUnknownPt: Int
        ) {
            fun toJson() = OrderedJsonObject().apply {
                put("num_packets_dropped_unknown_pt", numPacketsDroppedUnknownPt)
                put("num_keyframes", numKeyframes)
                put("num_layering_changes", numLayeringChanges)
            }
        }
    }
}
