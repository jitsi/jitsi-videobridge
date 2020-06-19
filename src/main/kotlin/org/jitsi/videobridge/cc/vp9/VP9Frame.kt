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

import org.jitsi.nlj.codec.vp8.Vp8Utils.Companion.applyExtendedPictureIdDelta
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.rtp.util.RtpUtils.Companion.isNewerSequenceNumberThan
import org.jitsi.rtp.util.RtpUtils.Companion.isOlderSequenceNumberThan

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
class VP9Frame(packet: Vp9Packet) {
    /**
     * The RTP SSRC of the incoming frame that this instance refers to
     * (RFC3550).
     */
    val ssrc: Long = packet.ssrc

    /**
     * The RTP timestamp of the incoming frame that this instance refers to
     * (RFC3550).
     */
    val timestamp: Long = packet.timestamp

    /**
     * The earliest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    var earliestKnownSequenceNumber: Int = packet.sequenceNumber
        private set

    /**
     * The latest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    var latestKnownSequenceNumber: Int = packet.sequenceNumber
        private set

    /**
     * A boolean that indicates whether or not we've seen the first packet of the frame.
     * If so, its sequence is earliestKnownSequenceNumber.
     */
    var seenStartOfFrame: Boolean = packet.isStartOfFrame
        private set

    /**
     * A boolean that indicates whether or not we've seen the last packet of the frame.
     * If so, its sequence is latestKnownSequenceNumber.
     */
    var seenEndOfFrame: Boolean = packet.isEndOfFrame
        private set

    /**
     * The temporal layer of this frame.
     */
    val temporalLayer: Int = packet.temporalLayerIndex

    /**
     * The VP9 PictureID of the incoming VP9 frame that this instance refers to.
     */
    val pictureId: Int = packet.pictureId

    /**
     * The VP9 TL0PICIDX of the incoming VP9 frame that this instance refers to
     * (RFC7741).
     */
    val tl0PICIDX: Int = packet.TL0PICIDX

    /**
     * A boolean that indicates whether the incoming VP9 frame that this
     * instance refers to is a keyframe.
     */
    var isKeyframe: Boolean = packet.isKeyframe

    /**
     * A record of how this frame was projected, or null if not.
     */
    var projection: VP9FrameProjection? = null

    /**
     * A boolean that records whether this frame was accepted.
     */
    var isAccepted = false

    /**
     * Remember another packet of this frame.
     * Note: this assumes every packet is received only once, i.e. a filter
     * like [org.jitsi.nlj.transform.node.incoming.PaddingTermination] is in use.
     * @param packet The packet to remember.  This should be a packet which
     * has tested true with [.matchesFrame].
     */
    fun addPacket(packet: Vp9Packet) {
        require(matchesFrame(packet)) { "Non-matching packet added to frame" }
        val seq = packet.sequenceNumber
        if (isOlderSequenceNumberThan(seq, earliestKnownSequenceNumber)) {
            earliestKnownSequenceNumber = seq
        }
        if (isNewerSequenceNumberThan(seq, latestKnownSequenceNumber)) {
            latestKnownSequenceNumber = seq
        }
        if (packet.isStartOfFrame) {
            seenStartOfFrame = true
        }
        if (packet.isEndOfFrame) {
            seenEndOfFrame = true
        }
    }

    /**
     * @return true if this is a base temporal layer frame, false otherwise
     * @note We treat unknown temporal layer frames as TL0.
     */
    val isTL0: Boolean
        get() = temporalLayer <= 0

    /**
     * Small utility method that checks whether the [VP9Frame] that is
     * specified as a parameter belongs to the same RTP stream as the frame that
     * this instance refers to.
     *
     * @param vp9Frame the [VP9Frame] to check whether it belongs to the
     * same RTP stream as the frame that this instance refers to.
     * @return true if the [VP9Frame] that is specified as a parameter
     * belongs to the same RTP stream as the frame that this instance refers to,
     * false otherwise.
     */
    fun matchesSSRC(vp9Frame: VP9Frame): Boolean {
        return ssrc == vp9Frame.ssrc
    }

    /**
     * Determines whether the [VideoRtpPacket] that is specified as an
     * argument is part of the VP9 picture that is represented by this
     * [VP9Frame] instance.
     *
     * @param pkt the [VideoRtpPacket] instance to check whether it's part
     * of the VP9 picture that is represented by this [VP9Frame]
     * instance.
     * @return true if the [VideoRtpPacket] that is specified as an
     * argument is part of the VP9 picture that is represented by this
     * [VP9Frame] instance, false otherwise.
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
        return matchesSSRC(pkt) && timestamp == pkt.timestamp
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
    fun validateConsistent(pkt: Vp9Packet) {
        if (temporalLayer == pkt.temporalLayerIndex && tl0PICIDX == pkt.TL0PICIDX && pictureId == pkt.pictureId) /* TODO: also check start, end, seq nums? */ {
            return
        }
        throw RuntimeException(StringBuilder().apply {
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
            }
        }.toString())
    }

    /**
     * Check whether this frame is immediately after another one, according
     * to their extended picture IDs.
     */
    fun isImmediatelyAfter(otherFrame: VP9Frame): Boolean {
        return pictureId ==
            applyExtendedPictureIdDelta(otherFrame.pictureId, 1)
    }
}
