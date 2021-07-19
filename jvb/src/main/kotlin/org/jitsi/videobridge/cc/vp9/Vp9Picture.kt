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

import org.jitsi.nlj.codec.vpx.VpxUtils.Companion.applyExtendedPictureIdDelta
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.rtp.codec.vp9.Vp9Packet
import org.jitsi.nlj.util.setAndExtend
import kotlin.collections.ArrayList

/**
 * Groups together some RTP/VP9 fields that refer to a specific incoming VP9
 * picture, which may consist of multiple frames (of different spatial layers).
 *
 * Instances of this class are *NOT* thread safe.
 *
 * @author Jonathan Lennox
 */
class Vp9Picture(packet: Vp9Packet, index: Int) {
    val frames = ArrayList<Vp9Frame?>()

    init {
        val sid = packet.effectiveSpatialLayerIndex

        setFrameAtSid(Vp9Frame(packet, index), sid)
    }

    fun frame(sid: Int) = frames.getOrNull(sid)

    fun frame(packet: Vp9Packet) = frame(packet.effectiveSpatialLayerIndex)

    private fun setFrameAtSid(frame: Vp9Frame, sid: Int) =
        frames.setAndExtend(sid, frame, null)

    /**
     * Return the first (lowest-sid, earliest in decoding order) frame that we've received so far.
     * A valid picture must have at least one frame, so this will always return one.
     */
    private fun firstFrame(): Vp9Frame {
        val f = frames.find { f -> f != null }
        check(f != null) { "Picture must have at least one frame" }
        return f
    }

    /**
     * Return the last (highest-sid, earliest in decoding order) frame that we've received so far.
     * A valid picture must have at least one frame, so this will always return one.
     */
    private fun lastFrame(): Vp9Frame {
        val f = frames.findLast { f -> f != null }
        check(f != null) { "Picture must have at least one frame" }
        return f
    }

    val ssrc: Long
        get() = firstFrame().ssrc

    val timestamp: Long
        get() = firstFrame().timestamp

    val temporalLayer: Int
        get() = firstFrame().temporalLayer

    val earliestKnownSequenceNumber: Int
        get() = firstFrame().earliestKnownSequenceNumber

    val latestKnownSequenceNumber: Int
        get() = lastFrame().latestKnownSequenceNumber

    val pictureId: Int
        get() = firstFrame().pictureId

    val index: Int
        get() = firstFrame().index

    val tl0PICIDX: Int
        get() = firstFrame().tl0PICIDX

    /**
     * Remember another packet of this frame.
     * Note: this assumes every packet is received only once, i.e. a filter
     * like [org.jitsi.nlj.transform.node.incoming.PaddingTermination] is in use.
     * @param packet The packet to remember.  This should be a packet which
     * has tested true with [matchesPicture].
     */
    fun addPacket(packet: Vp9Packet): PacketInsertionResult {
        require(matchesPicture(packet)) { "Non-matching packet added to picture" }

        val sid = packet.effectiveSpatialLayerIndex

        val f = frame(packet)

        if (f != null) {
            f.addPacket(packet)
            return PacketInsertionResult(f, this, false)
        }

        val newF = Vp9Frame(packet, index)

        setFrameAtSid(newF, sid)

        return PacketInsertionResult(newF, this, true)
    }

    /**
     * Determines whether the [VideoRtpPacket] that is specified as an
     * argument is part of the VP9 picture that is represented by this
     * [Vp9Picture] instance.
     *
     * @param pkt the [VideoRtpPacket] instance to check whether it's part
     * of the VP9 picture that is represented by this [Vp9Picture]
     * instance.
     * @return true if the [VideoRtpPacket] that is specified as an
     * argument is part of the VP9 picture that is represented by this
     * [Vp9Picture] instance, false otherwise.
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
    fun matchesPicture(pkt: Vp9Packet): Boolean {
        return matchesSSRC(pkt) && timestamp == pkt.timestamp
    }

    /**
     * Validates that the specified RTP packet consistently matches all the
     * parameters of this picture (or the appropriate frame).
     *
     * This can be useful for diagnosing invalid streams if this fails when
     * [matchesPicture] is true.
     *
     * @param pkt the RTP packet to check whether its parameters match this frame.
     * @throws RuntimeException if the specified RTP packet is inconsistent with this frame
     */
    fun validateConsistency(pkt: Vp9Packet) {

        val f = frame(pkt)
        if (f != null) {
            f.validateConsistency(pkt)
            return
        }

        if (temporalLayer == pkt.temporalLayerIndex && tl0PICIDX == pkt.TL0PICIDX && pictureId == pkt.pictureId) {
            /* TODO: also check start, end, seq nums? */
            return
        }
        throw RuntimeException(
            buildString {
                with(pkt) {
                    append("Packet ssrc $ssrc, seq $sequenceNumber, picture id $pictureId, timestamp $timestamp ")
                }
                append("is not consistent with picture $ssrc, ")
                append("seq $earliestKnownSequenceNumber-$latestKnownSequenceNumber ")
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
            }
        )
    }

    /**
     * Check whether this picture is immediately after another one, according
     * to their extended picture IDs.
     */
    fun isImmediatelyAfter(otherPicture: Vp9Picture): Boolean {
        return pictureId ==
            applyExtendedPictureIdDelta(otherPicture.pictureId, 1)
    }
}

/**
 * The result of calling [insertPacket]
 */
class PacketInsertionResult(
    /** The frame corresponding to the packet that was inserted. */
    val frame: Vp9Frame,
    /** The picture corresponding to the packet that was inserted.  */
    val picture: Vp9Picture,
    /** Whether inserting the packet created a new frame.  */
    val isNewFrame: Boolean,
    /** Whether inserting the packet caused a reset  */
    val isReset: Boolean = false
)
