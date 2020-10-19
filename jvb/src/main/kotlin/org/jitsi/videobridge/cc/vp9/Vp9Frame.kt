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
package org.jitsi.videobridge.cc.vp9

import org.jitsi.nlj.codec.vpx.VpxUtils.Companion.getExtendedPictureIdDelta
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.rtp.util.isNewerThan
import org.jitsi.rtp.util.isOlderThan

/**
 * Groups together some RTP/VP9 fields that refer to a specific incoming VP9
 * frame. Most of those fields are final and cannot be changed, with the
 * exception of the ending and max sequence number that may be unknown at the
 * time of the creation of this instance.
 *
 * Instances of this class are *NOT* thread safe. While most internal state of
 * this class instances is final, the sequence number ranges, haveStart/haveEnd,
 * and isKeyframe are not.
 *
 * @author Jonathan Lennox
 */
class Vp9Frame internal constructor(
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
     * A boolean that indicates whether or not we've seen the first packet of the frame.
     * If so, its sequence is earliestKnownSequenceNumber.
     */
    seenStartOfFrame: Boolean,

    /**
     * A boolean that indicates whether or not we've seen the last packet of the frame.
     * If so, its sequence is latestKnownSequenceNumber.
     */
    seenEndOfFrame: Boolean,

    /**
     * A boolean that indicates whether we've seen a packet with the marker bit set.
     */
    seenMarker: Boolean,

    /**
     * The temporal layer of this frame.
     */
    val temporalLayer: Int,

    /**
     * The spatial layer of this frame.
     */
    val spatialLayer: Int,

    /**
     * Whether the frame is used as an upper level reference.
     */
    val isUpperLevelReference: Boolean,

    /**
     * Whether the frame is a temporal switching-up point.
     */
    val isSwitchingUpPoint: Boolean,

    /**
     * Whether the frame uses inter-layer dependency.
     */
    val usesInterLayerDependency: Boolean,

    /**
     * Whether the frame is inter-picture predicted.
     */
    val isInterPicturePredicted: Boolean,

    /**
     * The VP9 PictureID of the incoming VP9 frame that this instance refers to.
     */
    val pictureId: Int,

    /**
     * The VP9 TL0PICIDX of the incoming VP9 frame that this instance refers to
     * (RFC7741).
     */
    val tl0PICIDX: Int,

    /**
     * A boolean that indicates whether the incoming VP9 frame that this
     * instance refers to is a keyframe.
     */
    var isKeyframe: Boolean,

    /**
     * The number of spatial layers reported by this frame's scalability structure,
     * if it has one, otherwise -1.
     */
    var numSpatialLayers: Int
) {
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
     * A boolean that indicates whether or not we've seen the first packet of the frame.
     * If so, its sequence is earliestKnownSequenceNumber.
     */
    var seenStartOfFrame: Boolean = seenStartOfFrame
        private set

    /**
     * A boolean that indicates whether or not we've seen the last packet of the frame.
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
    var projection: Vp9FrameProjection? = null

    /**
     * A boolean that records whether this frame was accepted, i.e. should be forwarded to the receiver
     * given the layer currently being forwarded.
     */
    var isAccepted = false

    constructor(packet: Vp9Packet) : this(
        ssrc = packet.ssrc,
        timestamp = packet.timestamp,
        earliestKnownSequenceNumber = packet.sequenceNumber,
        latestKnownSequenceNumber = packet.sequenceNumber,
        seenStartOfFrame = packet.isStartOfFrame,
        seenEndOfFrame = packet.isEndOfFrame,
        seenMarker = packet.isMarked,
        temporalLayer = packet.temporalLayerIndex,
        spatialLayer = packet.spatialLayerIndex,
        isUpperLevelReference = packet.isUpperLevelReference,
        isSwitchingUpPoint = packet.isSwitchingUpPoint,
        usesInterLayerDependency = packet.usesInterLayerDependency,
        isInterPicturePredicted = packet.isInterPicturePredicted,
        pictureId = packet.pictureId,
        tl0PICIDX = packet.TL0PICIDX,
        isKeyframe = packet.isKeyframe,
        numSpatialLayers = packet.scalabilityStructureNumSpatial
    )
    /**
     * Remember another packet of this frame.
     * Note: this assumes every packet is received only once, i.e. a filter
     * like [org.jitsi.nlj.transform.node.incoming.PaddingTermination] is in use.
     * @param packet The packet to remember.  This should be a packet which
     * has tested true with [matchesFrame].
     */
    fun addPacket(packet: Vp9Packet) {
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
        if (packet.hasScalabilityStructure) {
            numSpatialLayers = packet.scalabilityStructureNumSpatial
        }
    }

    /**
     * @return true if this is a base temporal layer frame, false otherwise
     * @note We treat unknown temporal layer frames as TL0.
     */
    val isTL0: Boolean
        get() = temporalLayer <= 0

    /**
     * Small utility method that checks whether the [Vp9Frame] that is
     * specified as a parameter belongs to the same RTP stream as the frame that
     * this instance refers to.
     *
     * @param vp9Frame the [Vp9Frame] to check whether it belongs to the
     * same RTP stream as the frame that this instance refers to.
     * @return true if the [Vp9Frame] that is specified as a parameter
     * belongs to the same RTP stream as the frame that this instance refers to,
     * false otherwise.
     */
    fun matchesSSRC(vp9Frame: Vp9Frame): Boolean {
        return ssrc == vp9Frame.ssrc
    }

    /**
     * Determines whether the [VideoRtpPacket] that is specified as an
     * argument is part of the VP9 picture that is represented by this
     * [Vp9Frame] instance.
     *
     * @param pkt the [VideoRtpPacket] instance to check whether it's part
     * of the VP9 picture that is represented by this [Vp9Frame]
     * instance.
     * @return true if the [VideoRtpPacket] that is specified as an
     * argument is part of the VP9 picture that is represented by this
     * [Vp9Frame] instance, false otherwise.
     */
    private fun matchesSSRC(pkt: VideoRtpPacket): Boolean {
        return ssrc == pkt.ssrc
    }

    /**
     * Checks whether the specified RTP packet is part of this frame.
     *
     * @param pkt the RTP packet to check whether it's part of this frame.
     * @return true if the specified RTP packet is part of this frame, false
     * otherwise.
     */
    fun matchesFrame(pkt: Vp9Packet): Boolean {
        return matchesSSRC(pkt) && timestamp == pkt.timestamp &&
            spatialLayer == pkt.spatialLayerIndex
    }

    /**
     * Validates that the specified RTP packet consistently matches all the
     * parameters of this frame.
     *
     * This can be useful for diagnosing invalid streams if this fails when
     * [matchesFrame] is true.
     *
     * @param pkt the RTP packet to check whether its parameters match this frame.
     * @throws RuntimeException if the specified RTP packet is inconsistent with this frame
     */
    fun validateConsistency(pkt: Vp9Packet) {
        if (temporalLayer == pkt.temporalLayerIndex &&
            tl0PICIDX == pkt.TL0PICIDX &&
            pictureId == pkt.pictureId &&
            isSwitchingUpPoint == pkt.isSwitchingUpPoint &&
            isUpperLevelReference == pkt.isUpperLevelReference &&
            usesInterLayerDependency == pkt.usesInterLayerDependency &&
            isInterPicturePredicted == pkt.isInterPicturePredicted
        ) /* TODO: also check start, end, seq nums? */ {
            return
        }
        throw RuntimeException(buildString {
            with(pkt) {
                append("Packet ssrc $ssrc, seq $sequenceNumber, picture id $pictureId, timestamp $timestamp ")
            }
            append("is not consistent with frame $ssrc, seq $earliestKnownSequenceNumber-$latestKnownSequenceNumber ")
            append("picture id $pictureId, timestamp $timestamp: ")

            var complained = false
            if (temporalLayer != pkt.temporalLayerIndex) {
                append("packet temporal layer ${pkt.temporalLayerIndex} != frame temporal layer $temporalLayer")
                complained = true
            }
            if (tl0PICIDX != pkt.TL0PICIDX) {
                if (complained) {
                    append("; ")
                }
                append("packet TL0PICIDX ${pkt.TL0PICIDX} != frame TL0PICIDX $tl0PICIDX")
                complained = true
            }
            if (pictureId != pkt.pictureId) {
                if (complained) {
                    append("; ")
                }
                append("packet PictureID ${pkt.pictureId} != frame PictureID $pictureId")
                complained = true
            }
            if (isSwitchingUpPoint != pkt.isSwitchingUpPoint) {
                if (complained) {
                    append("; ")
                }
                append("packet switchingUpPoint ${pkt.isSwitchingUpPoint} != " +
                    "frame switchingUpPoint $isSwitchingUpPoint")
                complained = true
            }
            if (isUpperLevelReference != pkt.isUpperLevelReference) {
                if (complained) {
                    append("; ")
                }
                append("packet upperLevelReference ${pkt.isUpperLevelReference} != " +
                    "frame upperLevelReference $isUpperLevelReference")
                complained = true
            }
            if (usesInterLayerDependency != pkt.usesInterLayerDependency) {
                if (complained) {
                    append("; ")
                }
                append("packet usesInterLayerDepencency ${pkt.usesInterLayerDependency} != " +
                    "frame usesInterLayerDepencency $usesInterLayerDependency")
                complained = true
            }
            if (isInterPicturePredicted != pkt.isInterPicturePredicted) {
                if (complained) {
                    append("; ")
                }
                append("packet isInterPicturePredicted ${pkt.isInterPicturePredicted} != " +
                    "frame isInterPicturePredicted $isInterPicturePredicted")
                complained = true
            }
        })
    }

    /**
     * Check whether this frame is immediately after another one, according
     * to their extended picture IDs and spatial layers
     */
    fun isImmediatelyAfter(otherFrame: Vp9Frame): Boolean {
        val delta = getExtendedPictureIdDelta(otherFrame.pictureId, pictureId)

        return when {
            delta == 0 ->
                spatialLayer == otherFrame.spatialLayer + 1
            delta == 1 ->
                spatialLayer == 0 && otherFrame.spatialLayer == 2 /* ??? */
            else ->
                false
        }
    }
}
