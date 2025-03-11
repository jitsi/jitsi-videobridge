/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.videobridge

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.VideoType
import org.jitsi.nlj.codec.vpx.VpxUtils
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.nlj.rtp.codec.av1.applyTemplateIdDelta
import org.jitsi.nlj.rtp.codec.av1.getTemplateIdDelta
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.rtp.rtcp.RtcpPacket
import org.jitsi.rtp.rtcp.RtcpSrPacket
import org.jitsi.rtp.rtcp.rtcpfb.RtcpFbPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.LRUCache
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.message.AudioSourceMapping
import org.jitsi.videobridge.message.AudioSourcesMap
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.VideoSourceMapping
import org.jitsi.videobridge.message.VideoSourcesMap
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.json.simple.JSONObject

/**
 * Align common fields from different source types.
 * Perhaps this could become a base class of those types.
 */
class SourceDesc private constructor(
    val name: String,
    val owner: String,
    val videoType: VideoType,
    val ssrc1: Long,
    val ssrc2: Long
) {
    constructor(s: AudioSourceDesc) : this(
        s.sourceName ?: "anon",
        s.owner ?: "unknown",
        VideoType.DISABLED,
        s.ssrc,
        -1
    )
    constructor(s: MediaSourceDesc) : this(s.sourceName, s.owner, s.videoType, s.primarySSRC, getRtx(s))
    companion object {
        fun getRtx(s: MediaSourceDesc): Long {
            /* Ignoring any additional entries for now. */
            return if (s.rtpEncodings.isEmpty()) -1 else s.rtpEncodings[0].getSecondarySsrc(SsrcAssociationType.RTX)
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String = "$owner:$ssrc1/$ssrc2"
}

/**
 * Some RTP header state to track.
 */
class RtpState {
    var lastSequenceNumber = 0
    var lastTimestamp = 0L
    var codecState: CodecState? = null
    var av1PersistentState: CodecState? = null // TODO? Generalize if needed in the future
    var valid = false

    fun update(packet: RtpPacket) {
        lastSequenceNumber = packet.sequenceNumber
        lastTimestamp = packet.timestamp
        codecState = packet.getCodecState()
        if (packet is Av1DDPacket) {
            av1PersistentState = codecState
        }
        valid = true
    }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String = if (valid) "$lastSequenceNumber/$lastTimestamp/$codecState" else "-"
}

/**
 * RTP state for received SSRCs.
 */
class ReceiveSsrc(val props: SourceDesc) {
    val state = RtpState()

    /**
     * True when sequence number and timestamp deltas have been calculated.
     * If false, calculate them on the next relayed packet.
     */
    var hasDeltas = false

    /**
     * {@inheritDoc}
     */
    override fun toString(): String = "$state" + if (hasDeltas) "" else " (no \u2206)"
}

/**
 * RTP state for sent SSRCs.
 */
class SendSsrc(val ssrc: Long) {
    private val state = RtpState()
    private var sequenceNumberDelta = 0
    private var timestampDelta = 0L
    private var codecDeltas: CodecDeltas? = null

    /**
     * Update RTP state and apply deltas.
     */
    fun rewriteRtp(packet: RtpPacket, sending: Boolean, recv: ReceiveSsrc) {
        if (sending) {
            if (!recv.hasDeltas) {
                /* Calculate new deltas the first time a receive ssrc is mapped to a send ssrc. */
                if (state.valid) {
                    if (recv.state.valid) {
                        sequenceNumberDelta =
                            RtpUtils.getSequenceNumberDelta(state.lastSequenceNumber, recv.state.lastSequenceNumber)
                        timestampDelta =
                            RtpUtils.getTimestampDiff(state.lastTimestamp, recv.state.lastTimestamp)
                        codecDeltas = state.codecState?.getDeltas(
                            recv.state.codecState
                        )
                    } else {
                        val prevSequenceNumber =
                            RtpUtils.applySequenceNumberDelta(packet.sequenceNumber, -1)
                        val prevTimestamp =
                            RtpUtils.applyTimestampDelta(packet.timestamp, -960) // guessing
                        sequenceNumberDelta =
                            RtpUtils.getSequenceNumberDelta(state.lastSequenceNumber, prevSequenceNumber)
                        timestampDelta =
                            RtpUtils.getTimestampDiff(state.lastTimestamp, prevTimestamp)
                        codecDeltas = state.codecState?.getDeltas(packet)
                    }
                }
                recv.hasDeltas = true
            }

            if (packet is Av1DDPacket && state.codecState !is Av1DDCodecState && state.av1PersistentState != null) {
                codecDeltas = state.av1PersistentState?.getDeltas(packet)
            }

            recv.state.update(packet)

            packet.ssrc = ssrc
            packet.sequenceNumber = RtpUtils.applySequenceNumberDelta(packet.sequenceNumber, sequenceNumberDelta)
            packet.timestamp = RtpUtils.applyTimestampDelta(packet.timestamp, timestampDelta)
            codecDeltas?.rewritePacket(packet)

            state.update(packet)
        } else {
            /* Don't touch send state if we're dropping the packet. */
            recv.state.update(packet)
        }
    }

    /**
     * Fix SSRC and timestamps in an RTCP packet.
     * For packets in the same direction as media flow; feedback messages handled separately.
     */
    fun rewriteRtcp(packet: RtcpPacket) {
        packet.senderSsrc = ssrc
        if (packet is RtcpSrPacket) {
            packet.senderInfo.rtpTimestamp = RtpUtils.applyTimestampDelta(
                packet.senderInfo.rtpTimestamp,
                timestampDelta
            )
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String = "$ssrc{$state,\u2206=$sequenceNumberDelta/$timestampDelta/$codecDeltas}"
}

/**
 * Associates primary and secondary send SSRCs.
 * Primary constructor preserves state of the existing send SSRCs.
 */
class SendSource(val props: SourceDesc, val send1: SendSsrc, val send2: SendSsrc) {

    /**
     * If false, do not send on this SSRC until a packet with start=true arrives.
     */
    private var started = false

    /**
     * Create object with new send SSRCs.
     */
    constructor(props: SourceDesc, ssrc1: Long, ssrc2: Long) : this(props, SendSsrc(ssrc1), SendSsrc(ssrc2))

    /**
     * Demux to proper SSRC.
     */
    private fun getSender(ssrc: Long) = if (ssrc == props.ssrc2) send2 else send1

    /**
     * Update RTP state and apply deltas.
     * Returns true if packet should be sent.
     */
    fun rewriteRtp(packet: RtpPacket, start: Boolean, recv: ReceiveSsrc): Boolean {
        if (start) {
            started = true
        }
        getSender(packet.ssrc).rewriteRtp(packet, started, recv)
        return started
    }

    /**
     * Fix SSRC and timestamps in an RTCP packet.
     * For packets in the same direction as media flow; feedback messages handled separately.
     */
    fun rewriteRtcp(packet: RtcpPacket) {
        getSender(packet.senderSsrc).rewriteRtcp(packet)
    }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String = "$send1, $send2" + if (started) "" else " (not started)"
}

/**
 * Interface between an SsrcCache and its owner.
 */
interface SsrcRewriter {
    /**
     * Find the properties of the video source indicated by the given SSRC. Returns null if not found.
     */
    fun findVideoSourceProps(ssrc: Long): MediaSourceDesc?

    /**
     * Find the properties of the audio source indicated by the given SSRC. Returns null if not found.
     */
    fun findAudioSourceProps(ssrc: Long): AudioSourceDesc?

    /**
     * Get a unique send SSRC.
     */
    fun getNextSendSsrc(): Long

    /**
     * Sends a specific message to this endpoint over its bridge channel.
     */
    fun sendMessage(msg: BridgeChannelMessage)
}

/**
 * Limit the number of local SSRCs used for the given media type to the number specified.
 * If there are more sources in the conference than the limit, then the least recently used
 * SSRCs are remapped. RTP packets have their header fields rewritten so the stream appears
 * to be a continuation of an already advertised SSRC.
 */
abstract class SsrcCache(val size: Int, val ep: SsrcRewriter, val parentLogger: Logger, label: String) {

    private val logger = createChildLogger(parentLogger).apply { addContext("type", label) }

    /**
     * All remote SSRCs that have been seen.
     */
    private val receivedSsrcs = HashMap<Long, ReceiveSsrc>()

    /**
     * The most recently forwarded remote SSRC groups. If this list is full and a new remote SSRC needs to be
     * forwarded to the endpoint, the element at the front of this list will be removed and that element's
     * local SSRC will be used. Note: indexed by primary SSRC.
     */
    private val sendSources = LRUCache<Long, SendSource>(
        size,
        // accessOrder
        true
    )

    /**
     * Whether an incoming RTP packet can automatically activate its source (i.e. acquire a send SSRC).
     * If false, sources must be activated using the activate() method.
     */
    protected abstract val allowCreateOnPacket: Boolean

    /**
     * Number of times an SSRC has changed its mapping. (Does not count initial mappings.)
     */
    private var remapCount = 0

    /**
     * Find the properties of the source indicated by the given SSRC. Returns null if not found.
     */
    protected abstract fun findSourceProps(ssrc: Long): SourceDesc?

    /**
     * Notify new SSRC mappings to the client. The list will contain at least one element.
     */
    protected abstract fun notifyMappings(sources: List<SendSource>)

    /**
     * Assign a group of send SSRCs to use for the specified source.
     * If remapping the send SSRCs from another source, transfer RTP state from the old source.
     * Returns null if no current mapping exists and allowCreate is false.
     * Otherwise, returns the send source information to use.
     */
    private fun getSendSource(
        ssrc: Long,
        props: SourceDesc,
        allowCreate: Boolean,
        /* Collect remapped sources into this list, if provided. */
        remappings: MutableList<SendSource>? = null
    ): SendSource? {
        /* Moves to end of LRU when found. */
        var sendSource = sendSources[ssrc]

        if (sendSource == null) {
            if (!allowCreate) {
                return null
            }
            if (sendSources.size == size) {
                val eldest = sendSources.eldest()
                sendSource = SendSource(props, eldest.value.send1, eldest.value.send2)
                logger.debug { "Remapping SSRC: ${props.ssrc1}->$sendSource. ${eldest.key}->inactive" }
                /* Request new deltas on next sent packet */
                receivedSsrcs[props.ssrc1]?.hasDeltas = false
                if (props.ssrc2 != -1L) {
                    receivedSsrcs[props.ssrc2]?.hasDeltas = false
                }
                ++remapCount
            } else {
                val ssrc1 = ep.getNextSendSsrc()
                val ssrc2 = ep.getNextSendSsrc()
                sendSource = SendSource(props, ssrc1, ssrc2)
                logger.debug { "Added send SSRC: ${props.ssrc1}->$sendSource" }
            }
            sendSources[ssrc] = sendSource
            remappings?.add(sendSource)
        }

        return sendSource
    }

    /**
     * Remove all send sources owned by [owner]. Does not signal anything to the client, as only additions need to be
     * signaled.
     */
    fun removeByOwner(owner: String) {
        synchronized(sendSources) {
            sendSources.values.removeIf { sendSource ->
                sendSource.props.owner == owner
            }
        }
    }

    /**
     * Assign send SSRCs to the given sources. Any remapped SSRCs will be notified to the client.
     */
    fun activate(sources: List<MediaSourceDesc>) {
        val remappings = mutableListOf<SendSource>()

        synchronized(sendSources) {
            /* Before creating any new send sources, do a first pass that
            touches the already active sources, to prevent them from being
            bumped out of the LRU by earlier elements of the list. */
            sources.filter { source ->
                sendSources[source.primarySSRC] == null
            }.forEach { source ->
                getSendSource(source.primarySSRC, SourceDesc(source), allowCreate = true, remappings)
            }

            logger.debug { this.toString() }
        }

        if (remappings.isNotEmpty()) {
            notifyMappings(remappings)
        }
    }

    /**
     * Send all current mappings to the endpoint.
     * Can be used to resynchronize after message transport reconnects.
     */
    fun sendAllMappings() {
        val remappings: List<SendSource>

        synchronized(sendSources) {
            remappings = sendSources.values.toList()
        }

        if (remappings.isNotEmpty()) {
            notifyMappings(remappings)
        }
    }

    /**
     * Rewrite RTP fields for a relayed packet.
     * Activates a send SSRC if necessary.
     * @param packet the packet about to be sent.
     * @param start whether this packet can be the first packet sent on a new SSRC mapping.
     * @return whether to send this packet.
     */
    fun rewriteRtp(packet: RtpPacket, start: Boolean = true): Boolean {
        val remappings = mutableListOf<SendSource>()
        var send = false

        try {
            synchronized(sendSources) {
                var rs = receivedSsrcs[packet.ssrc]
                if (rs == null) {
                    val props = findSourceProps(packet.ssrc) ?: return false
                    rs = ReceiveSsrc(props)
                    receivedSsrcs[packet.ssrc] = rs
                    logger.debug { "Added receive SSRC: ${packet.ssrc}" }
                }

                val ss = getSendSource(rs.props.ssrc1, rs.props, allowCreateOnPacket, remappings)
                if (ss != null) {
                    send = ss.rewriteRtp(packet, start, rs)
                }
            }

            if (remappings.isNotEmpty()) {
                notifyMappings(remappings)
            }
        } catch (e: Exception) {
            logger.error("Error rewriting SSRC, packet $packet", e)
            send = false
        }

        return send
    }

    /**
     * Fix SSRC and timestamps in an RTCP packet.
     * For packets in the same direction as media flow; feedback messages handled separately.
     */
    fun rewriteRtcp(packet: RtcpPacket): Boolean {
        synchronized(sendSources) {
            /* Don't activate a source on RTCP. */
            val rs = receivedSsrcs[packet.senderSsrc] ?: return false
            val ss = getSendSource(rs.props.ssrc1, rs.props, allowCreate = false) ?: return false
            ss.rewriteRtcp(packet)
            return true
        }
    }

    /**
     * Change the SSRC in an RTCP Feedback packet to the receive-side SSRC.
     * Return the endpoint identifier of the source owner.
     * If the SSRC in the packet does not refer to an active source,
     * then do not modify the packet and return null.
     */
    fun unmapRtcpFbSsrc(packet: RtcpFbPacket): String? {
        val mediaSsrc = packet.mediaSourceSsrc

        synchronized(sendSources) {
            val ss = sendSources.values.find { sendSource ->
                if (mediaSsrc == sendSource.send1.ssrc) {
                    packet.mediaSourceSsrc = sendSource.props.ssrc1
                    return@find true
                }
                if (mediaSsrc == sendSource.send2.ssrc) {
                    packet.mediaSourceSsrc = sendSource.props.ssrc2
                    return@find true
                }
                return@find false
            }

            if (ss != null) {
                logger.debug {
                    "Received RTCP FB packet. " +
                        "Translated send SSRC $mediaSsrc to receive SSRC ${packet.mediaSourceSsrc}. " +
                        "Owner = ${ss.props.owner}."
                }
                return ss.props.owner
            } else {
                logger.debug { "Received RTCP FB packet for SSRC $mediaSsrc. Not active." }
                return null
            }
        }
    }

    /**
     * Returns JSON statistics useful for debugging.
     */
    fun getDebugState(): JSONObject {
        synchronized(sendSources) {
            return JSONObject().apply {
                put("max", this@SsrcCache.size)
                put("received", receivedSsrcs.size)
                put("sent", sendSources.size)
                put("remappings", remapCount)
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String {
        return "SSRCs: received=" +
            receivedSsrcs.entries.joinToString(", ", "[", "]") {
                "(${it.key}->${it.value})"
            } +
            " mappings=" +
            sendSources.entries.joinToString(", ", "[", "]") {
                "(${it.key}->${it.value})"
            }
    }
}

/**
 * SSRC Cache for audio.
 */
class AudioSsrcCache(size: Int, ep: SsrcRewriter, parentLogger: Logger) :
    SsrcCache(size, ep, parentLogger, MediaType.AUDIO.toString()) {

    /* Switching occurs on received packets */
    override val allowCreateOnPacket = true

    /**
     * {@inheritDoc}
     */
    override fun findSourceProps(ssrc: Long): SourceDesc? {
        val p = ep.findAudioSourceProps(ssrc)
        return if (p?.sourceName == null || p.owner == null) {
            null
        } else {
            SourceDesc(p)
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun notifyMappings(sources: List<SendSource>) {
        sources.map {
            val props = it.props
            AudioSourceMapping(props.name, props.owner, it.send1.ssrc)
        }.also {
            ep.sendMessage(AudioSourcesMap(it))
        }
    }
}

/**
 * SSRC Cache for video.
 */
class VideoSsrcCache(size: Int, ep: SsrcRewriter, parentLogger: Logger) :
    SsrcCache(size, ep, parentLogger, MediaType.VIDEO.toString()) {

    /* Switching triggered by activate() method only  */
    override val allowCreateOnPacket = false

    /**
     * {@inheritDoc}
     */
    override fun findSourceProps(ssrc: Long): SourceDesc? = ep.findVideoSourceProps(ssrc)?.let { SourceDesc(it) }

    /**
     * {@inheritDoc}
     */
    override fun notifyMappings(sources: List<SendSource>) {
        sources.map {
            val props = it.props
            VideoSourceMapping(props.name, props.owner, it.send1.ssrc, it.send2.ssrc, props.videoType)
        }.also {
            ep.sendMessage(VideoSourcesMap(it))
        }
    }
}

/** Codec-specific packet state. */
interface CodecState {
    fun getDeltas(otherState: CodecState?): CodecDeltas?
    fun getDeltas(packet: RtpPacket): CodecDeltas?
}

/** Codec-specific packet deltas. */
interface CodecDeltas {
    fun rewritePacket(packet: RtpPacket)
}

private class Vp8CodecState(val lastTl0Index: Int) : CodecState {
    constructor(packet: Vp8Packet) : this(packet.TL0PICIDX)

    override fun getDeltas(otherState: CodecState?): CodecDeltas? {
        if (otherState !is Vp8CodecState) {
            return null
        }
        val tl0IndexDelta = VpxUtils.getTl0PicIdxDelta(lastTl0Index, otherState.lastTl0Index)
        return Vp8CodecDeltas(tl0IndexDelta)
    }

    override fun getDeltas(packet: RtpPacket): CodecDeltas? {
        if (packet !is Vp8Packet) {
            return null
        }
        val tl0IndexDelta = VpxUtils.getTl0PicIdxDelta(
            lastTl0Index,
            VpxUtils.applyTl0PicIdxDelta(packet.TL0PICIDX, -1)
        )
        return Vp8CodecDeltas(tl0IndexDelta)
    }

    override fun toString() = "[VP8 TL0Idx]$lastTl0Index"
}

private class Vp8CodecDeltas(val tl0IndexDelta: Int) : CodecDeltas {
    override fun rewritePacket(packet: RtpPacket) {
        if (packet !is Vp8Packet) {
            return
        }
        packet.TL0PICIDX = VpxUtils.applyTl0PicIdxDelta(packet.TL0PICIDX, tl0IndexDelta)
    }

    override fun toString() = "[VP8 TL0Idx]$tl0IndexDelta"
}

private class Vp9CodecState(val lastTl0Index: Int) : CodecState {
    constructor(packet: Vp9Packet) : this(packet.TL0PICIDX)

    override fun getDeltas(otherState: CodecState?): CodecDeltas? {
        if (otherState !is Vp9CodecState) {
            return null
        }
        val tl0IndexDelta = VpxUtils.getTl0PicIdxDelta(lastTl0Index, otherState.lastTl0Index)
        return Vp9CodecDeltas(tl0IndexDelta)
    }

    override fun getDeltas(packet: RtpPacket): CodecDeltas? {
        if (packet !is Vp9Packet) {
            return null
        }
        val tl0IndexDelta = if (packet.hasTL0PICIDX) {
            VpxUtils.getTl0PicIdxDelta(
                lastTl0Index,
                VpxUtils.applyTl0PicIdxDelta(packet.TL0PICIDX, -1)
            )
        } else {
            0
        }
        return Vp9CodecDeltas(tl0IndexDelta)
    }

    override fun toString() = "[VP9 TL0Idx]$lastTl0Index"
}

private class Vp9CodecDeltas(val tl0IndexDelta: Int) : CodecDeltas {
    override fun rewritePacket(packet: RtpPacket) {
        if (packet !is Vp9Packet) {
            return
        }
        if (packet.hasTL0PICIDX) {
            packet.TL0PICIDX = VpxUtils.applyTl0PicIdxDelta(packet.TL0PICIDX, tl0IndexDelta)
        }
    }

    override fun toString() = "[VP9 TL0Idx]$tl0IndexDelta"
}

private class Av1DDCodecState : CodecState {
    val lastFrameNum: Int
    val nextTemplateIdx: Int
    constructor(lastFrameNum: Int, lastTemplateIdx: Int) {
        this.lastFrameNum = lastFrameNum
        this.nextTemplateIdx = lastTemplateIdx
    }

    constructor(packet: Av1DDPacket) {
        val descriptor = packet.descriptor
        requireNotNull(descriptor) { "AV1 Packet being routed must have non-null descriptor" }
        this.lastFrameNum = packet.frameNumber
        this.nextTemplateIdx = descriptor.structure.templateIdOffset + descriptor.structure.templateCount
    }

    override fun getDeltas(otherState: CodecState?): CodecDeltas? {
        if (otherState !is Av1DDCodecState) {
            return null
        }
        val frameNumDelta = RtpUtils.getSequenceNumberDelta(lastFrameNum, otherState.lastFrameNum)
        val templateIdDelta = getTemplateIdDelta(nextTemplateIdx, otherState.nextTemplateIdx)
        return Av1DDCodecDeltas(frameNumDelta, templateIdDelta)
    }

    override fun getDeltas(packet: RtpPacket): CodecDeltas? {
        if (packet !is Av1DDPacket) {
            return null
        }
        val descriptor = packet.descriptor ?: return null
        val frameNumDelta = RtpUtils.getSequenceNumberDelta(
            lastFrameNum,
            RtpUtils.applySequenceNumberDelta(packet.frameNumber, -1)
        )
        val templateIdDelta = getTemplateIdDelta(nextTemplateIdx, descriptor.structure.templateIdOffset)
        return Av1DDCodecDeltas(frameNumDelta, templateIdDelta)
    }

    override fun toString() = "[Av1DD FrameNum]$lastFrameNum [Av1DD TemplateIdx]$nextTemplateIdx"
}

private class Av1DDCodecDeltas(val frameNumDelta: Int, val templateIdDelta: Int) : CodecDeltas {
    override fun rewritePacket(packet: RtpPacket) {
        if (packet !is Av1DDPacket) {
            return
        }

        val descriptor = packet.descriptor
        requireNotNull(descriptor)

        descriptor.frameNumber = RtpUtils.applySequenceNumberDelta(descriptor.frameNumber, frameNumDelta)
        descriptor.frameDependencyTemplateId =
            applyTemplateIdDelta(descriptor.frameDependencyTemplateId, templateIdDelta)
        descriptor.structure.templateIdOffset =
            applyTemplateIdDelta(descriptor.structure.templateIdOffset, templateIdDelta)

        packet.reencodeDdExt()
    }

    override fun toString() = "[AV1DD FrameNum]$frameNumDelta [Av1DD templateId]$templateIdDelta"
}

private fun RtpPacket.getCodecState(): CodecState? {
    return when {
        this is Vp8Packet && isRewritable() -> Vp8CodecState(this)
        this is Vp9Packet -> Vp9CodecState(this)
        this is Av1DDPacket -> Av1DDCodecState(this)
        else -> null
    }
}

private fun Vp8Packet.isRewritable(): Boolean = hasTL0PICIDX
