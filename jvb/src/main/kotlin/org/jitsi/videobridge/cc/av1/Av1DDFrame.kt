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
import org.jitsi.rtp.rtp.header_extensions.FrameInfo
import org.jitsi.rtp.util.RtpUtils.Companion.applySequenceNumberDelta
import org.jitsi.rtp.util.isNewerThan
import org.jitsi.rtp.util.isOlderThan

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
    val frameInfo: FrameInfo?,

    /**
     * The AV1 DD Frame Number of this frame.
     */
    val frameNumber: Int,

    /**
     * The FrameID index (FrameID plus cycles) of this frame.
     */
    val index: Int,

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
    init { assert((index and 0xffff) == frameNumber) }

    constructor(packet: Av1DDPacket, index: Int) : this(
        ssrc = packet.ssrc,
        timestamp = packet.timestamp,
        earliestKnownSequenceNumber = packet.sequenceNumber,
        latestKnownSequenceNumber = packet.sequenceNumber,
        seenStartOfFrame = packet.isStartOfFrame,
        seenEndOfFrame = packet.isEndOfFrame,
        seenMarker = packet.isMarked,
        frameInfo = packet.frameInfo,
        frameNumber = packet.statelessDescriptor.frameNumber,
        index
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
        if (frameInfo == pkt.frameInfo) {
            return
        }

        throw RuntimeException(
            buildString {
                with(pkt) {
                    append("Packet ssrc $ssrc, seq $sequenceNumber, frame number $frameNumber, timestamp $timestamp ")
                }
                append("is not consistent with picture $ssrc, ")
                append("seq $earliestKnownSequenceNumber-$latestKnownSequenceNumber ")
                append("frame number $frameNumber, timestamp $timestamp: ")

                append("frame info $frameInfo != packet frame info ${pkt.frameInfo}")
            }
        )
    }
    fun isImmediatelyAfter(otherFrame: Av1DDFrame): Boolean {
        return frameNumber ==
            applySequenceNumberDelta(otherFrame.frameNumber, 1)
    }
}
