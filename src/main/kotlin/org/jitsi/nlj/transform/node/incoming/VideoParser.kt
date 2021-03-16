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
import org.jitsi.utils.logging2.cdebug
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parse video packets at a codec level
 */
class VideoParser(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger
) : TransformerNode("Video parser") {
    private val logger = createChildLogger(parentLogger)
    private val numPacketsDroppedUnknownPt = AtomicInteger()
    private var numKeyframes: Int = 0
    private var numLayeringChanges: Int = 0

    private var sources: Array<MediaSourceDesc> = arrayOf()
    private var signaledSources: Array<MediaSourceDesc> = sources

    private var videoCodecParser: VideoCodecParser? = null

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packetAs<RtpPacket>()
        val payloadType = streamInformationStore.rtpPayloadTypes[packet.payloadType.toByte()] ?: run {
            logger.error("Unrecognized video payload type ${packet.payloadType}, cannot parse video information")
            numPacketsDroppedUnknownPt.incrementAndGet()
            return null
        }
        val parsedPacket = try {
            when (payloadType) {
                is Vp8PayloadType -> {
                    val vp8Packet = packetInfo.packet.toOtherType(::Vp8Packet)
                    packetInfo.packet = vp8Packet
                    packetInfo.resetPayloadVerification()

                    if (videoCodecParser !is Vp8Parser) {
                        resetSources()
                        videoCodecParser = Vp8Parser(sources, logger)
                    }
                    vp8Packet
                }
                is Vp9PayloadType -> {
                    val vp9Packet = packetInfo.packet.toOtherType(::Vp9Packet)
                    packetInfo.packet = vp9Packet
                    packetInfo.resetPayloadVerification()

                    if (videoCodecParser !is Vp9Parser) {
                        resetSources()
                        videoCodecParser = Vp9Parser(sources, logger)
                    }
                    vp9Packet
                }
                else -> {
                    videoCodecParser = null
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
            numKeyframes++
        }
        if (packetInfo.layeringChanged) {
            logger.cdebug { "Layering structure changed for ssrc ${packet.ssrc} ${packet.sequenceNumber}" }
            numLayeringChanges++
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

    override fun getNodeStats(): NodeStatsBlock {
        return super.getNodeStats().apply {
            addNumber("num_packets_dropped_unknown_pt", numPacketsDroppedUnknownPt.get())
            addNumber("num_keyframes", numKeyframes)
            addNumber("num_layering_changes", numLayeringChanges)
        }
    }
}
