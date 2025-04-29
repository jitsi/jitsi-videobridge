/*
 * Copyright @ 2019 8x8, Inc
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
package org.jitsi.videobridge.cc.av1

import org.jitsi.nlj.rtp.codec.av1.Av1DDPacket
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyDescriptorReader
import org.jitsi.rtp.rtp.header_extensions.Av1DependencyException
import org.jitsi.rtp.rtp.header_extensions.Av1TemplateDependencyStructure
import org.jitsi.rtp.rtp.header_extensions.FrameInfo
import org.jitsi.rtp.util.RtpUtils.Companion.applySequenceNumberDelta
import org.jitsi.rtp.util.isNewerThan
import org.jitsi.rtp.util.isOlderThan
import org.jitsi.utils.logging2.Logger

class Av1DDFrame internal constructor(
    /**
     * The RTP SSRC of the incoming frame that this instance refers to
     * (RFC3550).
     */
    val ssrc: Long,

    /**
     * The RTP timestamp of the incoming frame that this instance refers to
     * (RFC3550).
     */
    val timestamp: Long,

    /**
     * The earliest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    earliestKnownSequenceNumber: Int,

    /**
     * The latest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    latestKnownSequenceNumber: Int,

    /**
     * A boolean that indicates whether we've seen the first packet of the frame.
     * If so, its sequence is earliestKnownSequenceNumber.
     */
    seenStartOfFrame: Boolean,

    /**
     * A boolean that indicates whether we've seen the last packet of the frame.
     * If so, its sequence is latestKnownSequenceNumber.
     */
    seenEndOfFrame: Boolean,

    /**
     * A boolean that indicates whether we've seen a packet with the marker bit set.
     */
    seenMarker: Boolean,

    /**
     * AV1 FrameInfo for the frame
     */
    frameInfo: FrameInfo?,

    /**
     * The AV1 DD Frame Number of this frame.
     */
    val frameNumber: Int,

    /**
     * The FrameID index (FrameID plus cycles) of this frame.
     */
    val index: Long,

    /**
     * The template ID of this frame
     */
    val templateId: Int,

    /**
     * The AV1 Template Dependency Structure in effect for this frame, if known
     */
    structure: Av1TemplateDependencyStructure?,

    /**
     * A new activeDecodeTargets specified for this frame, if any.
     * TODO: is this always specified in all packets of the frame?
     */
    val activeDecodeTargets: Int?,

    /**
     * A boolean that indicates whether the incoming AV1 frame that this
     * instance refers to is a keyframe.
     */
    var isKeyframe: Boolean,

    /**
     * The raw dependency descriptor included in the packet.  Stored if it could not be parsed initially.
     */
    val rawDependencyDescriptor: RtpPacket.HeaderExtension?
) {
    /**
     * AV1 FrameInfo for the frame
     */
    var frameInfo = frameInfo
        private set

    /**
     * The AV1 Template Dependency Structure in effect for this frame, if known
     */
    var structure = structure
        private set

    /**
     * The earliest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    var earliestKnownSequenceNumber = earliestKnownSequenceNumber
        private set

    /**
     * The latest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    var latestKnownSequenceNumber: Int = latestKnownSequenceNumber
        private set

    /**
     * A boolean that indicates whether we've seen the first packet of the frame.
     * If so, its sequence is earliestKnownSequenceNumber.
     */
    var seenStartOfFrame: Boolean = seenStartOfFrame
        private set

    /**
     * A boolean that indicates whether we've seen the last packet of the frame.
     * If so, its sequence is latestKnownSequenceNumber.
     */
    var seenEndOfFrame: Boolean = seenEndOfFrame
        private set

    /**
     * A boolean that indicates whether we've seen a packet with the marker bit set.
     */
    var seenMarker: Boolean = seenMarker
        private set

    /**
     * A record of how this frame was projected, or null if not.
     */
    var projection: Av1DDFrameProjection? = null

    /**
     * A boolean that records whether this frame was accepted, i.e. should be forwarded to the receiver
     * given the decoding target currently being forwarded.
     */
    var isAccepted = false

    // Validate that the index matches the pictureId
    init {
        assert((index and 0xffff).toInt() == frameNumber)
    }

    constructor(packet: Av1DDPacket, index: Long) : this(
        ssrc = packet.ssrc,
        timestamp = packet.timestamp,
        earliestKnownSequenceNumber = packet.sequenceNumber,
        latestKnownSequenceNumber = packet.sequenceNumber,
        seenStartOfFrame = packet.isStartOfFrame,
        seenEndOfFrame = packet.isEndOfFrame,
        seenMarker = packet.isMarked,
        frameInfo = packet.frameInfo,
        frameNumber = packet.statelessDescriptor.frameNumber,
        index = index,
        templateId = packet.statelessDescriptor.frameDependencyTemplateId,
        structure = packet.descriptor?.structure,
        activeDecodeTargets = packet.activeDecodeTargets,
        isKeyframe = packet.isKeyframe,
        rawDependencyDescriptor = if (packet.frameInfo == null) {
            packet.getHeaderExtension(packet.av1DDHeaderExtensionId)?.clone()
        } else {
            null
        }
    )

    /**
     * Remember another packet of this frame.
     * Note: this assumes every packet is received only once, i.e. a filter
     * like [org.jitsi.nlj.transform.node.incoming.PaddingTermination] is in use.
     * @param packet The packet to remember.  This should be a packet which
     * has tested true with [matchesFrame].
     */
    fun addPacket(packet: Av1DDPacket) {
        require(matchesFrame(packet)) { "Non-matching packet added to frame" }
        val seq = packet.sequenceNumber
        if (seq isOlderThan earliestKnownSequenceNumber) {
            earliestKnownSequenceNumber = seq
        }
        if (seq isNewerThan latestKnownSequenceNumber) {
            latestKnownSequenceNumber = seq
        }
        if (packet.isStartOfFrame) {
            seenStartOfFrame = true
        }
        if (packet.isEndOfFrame) {
            seenEndOfFrame = true
        }
        if (packet.isMarked) {
            seenMarker = true
        }

        if (structure == null && packet.descriptor?.structure != null) {
            structure = packet.descriptor?.structure
        }

        if (frameInfo == null && packet.frameInfo != null) {
            frameInfo = packet.frameInfo
        }
    }

    fun updateParse(templateDependencyStructure: Av1TemplateDependencyStructure, logger: Logger) {
        if (rawDependencyDescriptor == null) {
            return
        }
        val parser = Av1DependencyDescriptorReader(rawDependencyDescriptor)
        val descriptor = try {
            parser.parse(templateDependencyStructure)
        } catch (e: Av1DependencyException) {
            logger.warn("Could not parse updated AV1 Dependency Descriptor: ${e.message}")
            return
        }
        structure = descriptor.structure
        frameInfo = try {
            descriptor.frameInfo
        } catch (e: Av1DependencyException) {
            logger.warn("Could not extract frame info from updated AV1 Dependency Descriptor: ${e.message}")
            null
        }
    }

    /**
     * Small utility method that checks whether the [Av1DDFrame] that is
     * specified as a parameter belongs to the same RTP stream as the frame that
     * this instance refers to.
     *
     * @param av1Frame the [Av1DDFrame] to check whether it belongs to the
     * same RTP stream as the frame that this instance refers to.
     * @return true if the [Av1DDFrame] that is specified as a parameter
     * belongs to the same RTP stream as the frame that this instance refers to,
     * false otherwise.
     */
    fun matchesSSRC(av1Frame: Av1DDFrame): Boolean {
        return ssrc == av1Frame.ssrc
    }

    /**
     * Checks whether the specified RTP packet is part of this frame.
     *
     * @param pkt the RTP packet to check whether it's part of this frame.
     * @return true if the specified RTP packet is part of this frame, false
     * otherwise.
     */
    fun matchesFrame(pkt: Av1DDPacket): Boolean {
        return ssrc == pkt.ssrc && timestamp == pkt.timestamp &&
            frameNumber == pkt.frameNumber
    }

    fun validateConsistency(pkt: Av1DDPacket) {
        if (frameInfo == null) {
            return
        }
        if (frameInfo == pkt.frameInfo) {
            return
        }

        throw RuntimeException(
            buildString {
                with(pkt) {
                    append("Packet ssrc $ssrc, seq $sequenceNumber, frame number $frameNumber, timestamp $timestamp ")
                    append("packet template ${statelessDescriptor.frameDependencyTemplateId} ")
                    append("frame info $frameInfo ")
                }
                append("is not consistent with frame ${this@Av1DDFrame}")
            }
        )
    }
    fun isImmediatelyAfter(otherFrame: Av1DDFrame): Boolean {
        return frameNumber ==
            applySequenceNumberDelta(otherFrame.frameNumber, 1)
    }

    override fun toString() = buildString {
        append("$ssrc, ")
        append("seq $earliestKnownSequenceNumber-$latestKnownSequenceNumber ")
        append("frame number $frameNumber, timestamp $timestamp: ")
        append("frame template $templateId info $frameInfo")
    }
}
