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
}
