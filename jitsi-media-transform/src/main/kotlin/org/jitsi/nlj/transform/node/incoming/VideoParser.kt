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
import org.jitsi.nlj.findRtpSource
import org.jitsi.nlj.findRtpSourceByPrimary
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.format.Vp9PayloadType
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.codec.VideoCodecParser
import org.jitsi.nlj.rtp.codec.av1.Av1DDParser
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.rtp.codec.vp8.Vp8Parser
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.nlj.rtp.codec.vp9.Vp9Parser
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.TransformerNode
import org.jitsi.nlj.util.ReadOnlyStreamInformationStore
import org.jitsi.rtp.extensions.bytearray.toHex
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.cdebug
import org.jitsi.utils.logging2.createChildLogger

/**
 * Parse video packets at a codec level
 */
class VideoParser(
    private val streamInformationStore: ReadOnlyStreamInformationStore,
    parentLogger: Logger,
    private val diagnosticContext: DiagnosticContext
) : TransformerNode("Video parser") {
    private val logger = createChildLogger(parentLogger)
    private val stats = Stats()

    private var sources: Array<MediaSourceDesc> = arrayOf()
    private var signaledSources: Array<MediaSourceDesc> = sources

    private var av1DDExtId: Int? = null

    private val videoCodecParsers = mutableMapOf<Long, VideoCodecParser>()

    init {
        streamInformationStore.onRtpExtensionMapping(RtpExtensionType.AV1_DEPENDENCY_DESCRIPTOR) {
            av1DDExtId = it
        }
    }

    override fun transform(packetInfo: PacketInfo): PacketInfo? {
        val packet = packetInfo.packetAs<RtpPacket>()
        val av1DDExtId = this.av1DDExtId // So null checks work
        val payloadType = streamInformationStore.rtpPayloadTypes[packet.payloadType.toByte()] ?: run {
            logger.error("Unrecognized video payload type ${packet.payloadType}, cannot parse video information")
            stats.numPacketsDroppedUnknownPt++
            return null
        }
        val videoCodecParser: VideoCodecParser?
        val parsedPacket = try {
            when {
                payloadType is Vp8PayloadType -> {
                    val (vp8Packet, parser) = parseNormalPayload<Vp8Parser>(packetInfo, ::Vp8Packet) { source ->
                        Vp8Parser(source, logger)
                    }
                    videoCodecParser = parser
                    vp8Packet
                }
                payloadType is Vp9PayloadType -> {
                    val (vp9Packet, parser) = parseNormalPayload<Vp9Parser>(packetInfo, ::Vp9Packet) { source ->
                        Vp9Parser(source, logger)
                    }
                    videoCodecParser = parser
                    vp9Packet
                }
                av1DDExtId != null && packet.getHeaderExtension(av1DDExtId) != null -> {
                    videoCodecParser = checkParserType<Av1DDParser>(packetInfo) { source ->
                        Av1DDParser(source, logger, diagnosticContext)
                    }

                    val av1DDPacket = videoCodecParser?.createFrom(packet, av1DDExtId)?.also {
                        packetInfo.packet = it
                        packetInfo.resetPayloadVerification()
                    }

                    av1DDPacket
                }
                else -> {
                    val curParser = videoCodecParsers[packet.ssrc]
                    if (curParser != null) {
                        logger.cdebug {
                            "Removing videoCodecParser on ${payloadType.javaClass} packet, " +
                                "current videoCodecParser is ${curParser.javaClass}"
                        }
                        sources.findRtpSource(packet)?.let { source ->
                            resetSource(source)
                            source.rtpEncodings.forEach {
                                videoCodecParsers.remove(it.primarySSRC)
                            }
                        }
                        packetInfo.layeringChanged = true
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
        if (parsedPacket != null && parsedPacket.isKeyframe && parsedPacket.isStartOfFrame) {
            logger.cdebug { "Received a keyframe for ssrc ${packet.ssrc} at seq ${packet.sequenceNumber}" }
            stats.numKeyframes++
        }
        if (packetInfo.layeringChanged) {
            logger.cdebug { "Layering structure changed for ssrc ${packet.ssrc} at seq ${packet.sequenceNumber}" }
            stats.numLayeringChanges++
        }

        return packetInfo
    }

    /** A normal payload is one where we choose the subclass of the ParsedVideoPacket and VideoCodecParser
     * based on the payload type, as opposed to the header extension (like AV1).  If the packet doesn't
     * satisfy [ParsedVideoPacket.meetsRoutingNeeds] but it has an AV1 DD header extension, we will parse
     * this packet as AV1 rather than as its normal type.
     * */
    private inline fun <reified T : VideoCodecParser> parseNormalPayload(
        packetInfo: PacketInfo,
        otherTypeCreator: (ByteArray, Int, Int) -> ParsedVideoPacket,
        parserConstructor: (MediaSourceDesc) -> T
    ): Pair<ParsedVideoPacket?, VideoCodecParser?> {
        val parsedPacket = packetInfo.packet.toOtherType(otherTypeCreator)
        if (!parsedPacket.meetsRoutingNeeds()) {
            // See if we can parse this packet as AV1
            val packet = packetInfo.packetAs<RtpPacket>()
            val av1DDExtId = this.av1DDExtId // So null checks work
            if (av1DDExtId != null && packet.getHeaderExtension(av1DDExtId) != null) {
                val parser = checkParserType<Av1DDParser>(packetInfo) { source ->
                    Av1DDParser(source, logger, diagnosticContext)
                }

                val av1DDPacket = parser?.createFrom(packet, av1DDExtId)?.also {
                    packetInfo.packet = it
                    packetInfo.resetPayloadVerification()
                }

                return Pair(av1DDPacket, parser)
            }
        }
        packetInfo.packet = parsedPacket
        packetInfo.resetPayloadVerification()

        val parser = checkParserType<T>(packetInfo, parserConstructor)

        return Pair(parsedPacket, parser)
    }

    private inline fun <reified T : VideoCodecParser> checkParserType(
        packetInfo: PacketInfo,
        constructor: (MediaSourceDesc) -> T
    ): T? {
        val packet = packetInfo.packetAs<RtpPacket>()
        val parser = videoCodecParsers[packet.ssrc]
        if (parser is T) {
            return parser
        }

        val source = sources.findRtpSource(packet)
            ?: // VideoQualityLayerLookup will drop this packet later, so no need to warn about it now
            return null
        logger.cdebug {
            "Creating new ${T::class.java.simpleName} for source ${source.sourceName}, " +
                "current videoCodecParser is ${parser?.javaClass?.simpleName}"
        }
        resetSource(source)
        packetInfo.layeringChanged = true
        val newParser = constructor(source)
        source.rtpEncodings.forEach {
            videoCodecParsers[it.primarySSRC] = newParser
        }

        return newParser
    }

    override fun handleEvent(event: Event) {
        when (event) {
            is SetMediaSourcesEvent -> {
                sources = event.mediaSourceDescs
                signaledSources = event.signaledMediaSourceDescs
                val ssrcsSeen = mutableSetOf<Long>()
                sources.forEach { source ->
                    source.rtpEncodings.forEach {
                        videoCodecParsers[it.primarySSRC]?.source = source
                        ssrcsSeen.add(it.primarySSRC)
                    }
                }
                videoCodecParsers.keys.removeIf { !ssrcsSeen.contains(it) }
            }
        }
        super.handleEvent(event)
    }

    private fun resetSource(source: MediaSourceDesc) {
        val signaledSource = signaledSources.findRtpSourceByPrimary(source.primarySSRC)
        if (signaledSource == null) {
            logger.warn("Unable to find signaled source corresponding to ${source.primarySSRC}")
            return
        }
        logger.cdebug { "Resetting source ${source.sourceName} to signaled source: $signaledSource" }
        for (signaledEncoding in signaledSource.rtpEncodings) {
            source.setEncodingLayers(signaledEncoding.layers, signaledEncoding.primarySSRC)
        }
    }

    override fun trace(f: () -> Unit) = f.invoke()

    override fun getNodeStats(): NodeStatsBlock = super.getNodeStats().apply { stats.addToNodeStatsBlock(this) }
    override fun statsJson() = super.statsJson().apply { stats.addToJson(this) }

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
        fun addToJson(o: OrderedJsonObject) {
            o["num_packets_dropped_unknown_pt"] = numPacketsDroppedUnknownPt
            o["num_keyframes"] = numKeyframes
            o["num_layering_changes"] = numLayeringChanges
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
