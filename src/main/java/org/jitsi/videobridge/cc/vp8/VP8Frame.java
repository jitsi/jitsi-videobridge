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
package org.jitsi.videobridge.cc.vp8;

import org.jetbrains.annotations.*;
import org.jitsi.impl.neomedia.codec.video.vp8.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.rtp.util.*;

/**
 * Groups together some RTP/VP8 fields that refer to a specific incoming VP8
 * frame. Most of those fields are final and cannot be changed, with the
 * exception of the ending and max sequence number that may be unknown at the
 * time of the creation of this instance.
 *
 * Instances of this class are *NOT* thread safe. While most internal state of
 * this class instances is final, the maxSequenceNumber and the
 * endingSequenceNumberIsKnown fields are not and it is therefore necessary
 * to synchronize reading/writing to those fields on this instance.
 *
 * Consider, for example, creating a new frame projection (that happens in the
 * translator thread and reads the maxSequenceNumber field) while packets are
 * being piggy-backed (that happens in the egress thread and could potentially
 * write the maxSequenceNumber field).
 *
 * @author George Politis
 */
class VP8Frame
{
    /**
     * The RTP SSRC of the incoming frame that this instance refers to
     * (RFC3550).
     */
    private final long ssrc;

    /**
     * The RTP timestamp of the incoming frame that this instance refers to
     * (RFC3550).
     */
    private final long timestamp;

    /**
     * The starting RTP sequence number of the incoming frame that this instance
     * refers to (RFC3550).
     */
    private final int startingSequenceNumber;

    /**
     * True if this is a base temporal layer frame, false otherwise.
     */
    private final boolean isTL0;

    /**
     * The VP8 TL0PICIDX of the incoming VP8 frame that this instance refers to
     * (RFC7741).
     */
    private final int tl0PICIDX;

    /**
     * A boolean that indicates whether the incoming VP8 frame that this
     * instance refers to is a keyframe (RFC7741).
     */
    private final boolean isKeyframe;

    /**
     * A boolean that indicates whether the incoming VP8 frame that this
     * instance refers to is a reference frame (RFC7741).
     */
    private final boolean isReference;

    /**
     * Becomes true if we have skipped a TL0 frame. In such an event, we need a
     * keyframe to become un-stuck.
     */
    private boolean needsKeyframe = false;

    /**
     * A boolean that indicates whether or not the ending sequence number of
     * this frame is known. if so, it's the maxSequenceNumber
     */
    private boolean endingSequenceNumberIsKnown = false;

    /**
     * The max sequence number that was seen before the arrival of the first
     * packet of this frame. This is useful for piggybacking any mis-ordered
     * packets. For example, if a frame comprised of packets 1,2,3 is received
     * as 2,1,3 then this field would be equal to 2.
     */
    private final int maxSequenceNumberSeenBeforeFirstPacket;

    /**
     * The max sequence number that will be forwarded for this frame. Note that
     * this is not necessarily the ending sequence number.
     */
    private int maxSequenceNumber = -1;

    /**
     * Ctor.
     *
     * @param firstPacketOfFrame The first VP8 packet of the frame that the
     * packet refers to.
     * @param maxSequenceNumberSeenBeforeFirstPacket the max sequence number
     * that was seen before the arrival of the first packet of this frame. This
     * is useful for piggybacking any mis-ordered packets.
     */
    VP8Frame(@NotNull VideoRtpPacket firstPacketOfFrame,
             int maxSequenceNumberSeenBeforeFirstPacket)
    {
        this.ssrc = firstPacketOfFrame.getSsrc();
        this.timestamp = firstPacketOfFrame.getTimestamp();
        this.startingSequenceNumber = firstPacketOfFrame.getSequenceNumber();
        this.maxSequenceNumberSeenBeforeFirstPacket
            = maxSequenceNumberSeenBeforeFirstPacket;

        byte[] buf = firstPacketOfFrame.getBuffer();
        int payloadOffset = firstPacketOfFrame.getPayloadOffset(),
            payloadLen = firstPacketOfFrame.getPayloadLength();

        this.tl0PICIDX = DePacketizer
            .VP8PayloadDescriptor.getTL0PICIDX(buf, payloadOffset, payloadLen);
        this.isKeyframe = DePacketizer
            .isKeyFrame(buf, payloadOffset, payloadLen);
        this.isReference = DePacketizer
            .VP8PayloadDescriptor.isReference(buf, payloadOffset, payloadLen);
        this.isTL0 = DePacketizer.VP8PayloadDescriptor
            .getTemporalLayerIndex(buf, payloadOffset, payloadLen) == 0;
    }

    /**
     * @return The starting RTP sequence number of the incoming frame that this
     * instance refers to (RFC3550).
     */
    int getStartingSequenceNumber()
    {
        return startingSequenceNumber;
    }

    /**
     * @return true if we have skipped a TL0 frame. In such an event, we need a
     * keyframe to become un-stuck.
     */
    boolean needsKeyframe()
    {
        return needsKeyframe;
    }

    /**
     * @return true if the incoming VP8 frame that this instance refers to is a
     * keyframe (RFC7741), false otherwise.
     */
    boolean isKeyframe()
    {
        return isKeyframe;
    }

    /**
     * @return true if this is a base temporal layer frame, false otherwise
     */
    boolean isTL0()
    {
        return isTL0;
    }

    /**
     * Gets the SSRC.
     */
    long getSSRCAsLong()
    {
        return ssrc;
    }

    /**
     * Gets the timestamp.
     */
    long getTimestamp()
    {
        return timestamp;
    }

    /**
     * Gets the max sequence number.
     */
    int getMaxSequenceNumber()
    {
        return maxSequenceNumber;
    }

    /**
     * Tiny utility method that returns true if the ending sequence number for
     * this frame is known, false otherwise.
     *
     * @return true if the ending sequence number for this frame is known, false
     * otherwise.
     */
    private boolean endingSequenceNumberIsKnown()
    {
        return endingSequenceNumberIsKnown;
    }

    /**
     * Tiny utility method that returns the next TL0PICIDX as an int.
     *
     * @return the next TL0PICIDX as an int.
     */
    static int nextTL0PICIDX(int tl0picidx)
    {
        // XXX The TL0PICIDX field is a 8-bit unsigned (ref. RFC7741).
        return (tl0picidx + 1)
            & DePacketizer.VP8PayloadDescriptor.TL0PICIDX_MASK;
    }

    /**
     * Small utility method that checks whether the {@link VP8Frame} that is
     * specified as a parameter is the next (with respect to the frame that this
     * instance represents) VP8 base temporal layer (TL0) picture.
     *
     * @param vp8Frame the {@link VP8Frame} to check whether it's the next TL0.
     * @return true if the VP8 frame is the next TL0.
     */
    private boolean isNextTemporalBaseLayerFrame(@NotNull VP8Frame vp8Frame)
    {
        return matchesSSRC(vp8Frame) && vp8Frame.isTL0
            && nextTL0PICIDX(tl0PICIDX) == vp8Frame.tl0PICIDX;
    }

    /**
     * Small utility method that determines whether the {@link VP8Frame} that is
     * specified as a parameter refers to the same VP8 base temporal layer (TL0)
     * picture as this instance.
     *
     * @param vp8Frame the {@link VP8Frame} to check whether it refers to the
     * same VP8 base temporal layer (TL0) picture as this instance.
     * @return true if the VP8 frame refers to the same TL0, false otherwise.
     */
    private boolean
    dependsOnSameTemporalBaseLayerFrame(@NotNull VP8Frame vp8Frame)
    {
        return matchesSSRC(vp8Frame) && vp8Frame.tl0PICIDX == tl0PICIDX;
    }

    /**
     * Small utility method that checks whether the {@link VP8Frame} that is
     * specified as a parameter belongs to the same RTP stream as the frame that
     * this instance refers to.
     *
     * @param vp8Frame the {@link VP8Frame} to check whether it belongs to the
     * same RTP stream as the frame that this instance refers to.
     * @return true if the {@link VP8Frame} that is specified as a parameter
     * belongs to the same RTP stream as the frame that this instance refers to,
     * false otherwise.
     */
    boolean matchesSSRC(@NotNull VP8Frame vp8Frame)
    {
        return ssrc == vp8Frame.ssrc;
    }

    /**
     * Determines whether the {@link VideoRtpPacket} that is specified as an
     * argument is part of the VP8 picture that is represented by this
     * {@link VP8Frame} instance.
     *
     * @param pkt the {@link VideoRtpPacket} instance to check whether it's part
     * of the VP8 picture that is represented by this {@link VP8Frame}
     * instance.
     * @return true if the {@link VideoRtpPacket} that is specified as an
     * argument is part of the VP8 picture that is represented by this
     * {@link VP8Frame} instance, false otherwise.
     */
    private boolean matchesSSRC(@NotNull VideoRtpPacket pkt)
    {
        return ssrc == pkt.getSsrc();
    }

    /**
     * Checks whether the specified RTP packet is part of an older frame.
     *
     * @param pkt the RTP packet to check whether it's part of an older frame.
     * @return true if the specified RTP packet is part of an older frame, false
     * otherwise.
     */
    boolean matchesOlderFrame(@NotNull VideoRtpPacket pkt)
    {
        if (!matchesSSRC(pkt))
        {
            // If the packet is from another (simulcast) stream, we can't check
            // whether it's newer or not. We assume it's not old.
            return false;
        }

        return RtpUtils.getTimestampDiff(pkt.getTimestamp(), timestamp) < 0;
    }

    /**
     * Checks whether the specified RTP packet is part of this frame.
     *
     * @param pkt the RTP packet to check whether it's part of this frame.
     * @return true if the specified RTP packet is part of this frame, false
     * otherwise.
     */
    boolean matchesFrame(@NotNull VideoRtpPacket pkt)
    {
        return matchesSSRC(pkt) && timestamp == pkt.getTimestamp();
    }

    /**
     * Determines whether the frame that is passed in as a parameter can be used
     * as the next frame and produce a decodable output.
     *
     * @param vp8Frame the incoming frame to project or drop. The incoming frame
     * is assumed to be newer than the instance.
     * @return true if the frame that is passed in as a parameter can be used as
     * the next frame and produce a decodable output.
     */
    boolean decodes(@NotNull VP8Frame vp8Frame)
    {
        // Keyframes CAN always be forwarded, regardless of what's last. The
        // quality filter will have the final say on whether to project or not
        // the incoming keyframe.
        if (vp8Frame.isKeyframe)
        {
            needsKeyframe = false;
            return true;
        }

        // If the new frame is not a keyframe and we haven't sent anything
        // previously, or if the packet is of another RTP stream, then there's
        // no point sending any packets because the receiving endpoint won't be
        // able to decode the stream.
        if (!matchesSSRC(vp8Frame))
        {
            return false;
        }

        if (!isReference)
        {
            // This instance is a non-reference frame, we can "massacre" it if
            // needed.

            // XXX this actually makes it possible to miss a reference frame :(
            // It would require significant effort to prevent such a case.
            return isNextTemporalBaseLayerFrame(vp8Frame)
                || dependsOnSameTemporalBaseLayerFrame(vp8Frame);
        }
        else if (isTL0)
        {
            // This instance is a TL0. TL0 reference frames MUST NOT be
            // corrupted (unless the new frame that we're processing is a
            // keyframe; this case is handled above). If the last/ending
            // sequence number of the this TL0 frame is known, we don't have to
            // worry about the 'running out of room' problem; we can therefore
            // accept the new frame.
            boolean accept = endingSequenceNumberIsKnown()
                && (isNextTemporalBaseLayerFrame(vp8Frame)
                || dependsOnSameTemporalBaseLayerFrame(vp8Frame));

            if (!accept && isNextTemporalBaseLayerFrame(vp8Frame))
            {
                // we're skipping a TL0.
                needsKeyframe = true;
            }

            return accept;
        }
        else
        {
            // This instance is a non-TL0 reference frame. non-TL0 reference
            // frames MUST NOT be corrupted, unless the new frame is a keyframe
            // or a TL0 reference frame.

            return (isNextTemporalBaseLayerFrame(vp8Frame)
                || (endingSequenceNumberIsKnown()
                && dependsOnSameTemporalBaseLayerFrame(vp8Frame)));
        }
    }

    /**
     * Sets the max sequence number that will be forwarded for this frame,
     * optionally marking it as the ending sequence number.
     *
     * @param sequenceNumber the sequence number to be set as the max
     * @param isEndingSequenceNumber true if this is the last sequence number of
     * this frame.
     */
    void
    setMaxSequenceNumber(int sequenceNumber, boolean isEndingSequenceNumber)
    {
        maxSequenceNumber = sequenceNumber;
        if (isEndingSequenceNumber)
        {
            endingSequenceNumberIsKnown = true;
        }
    }

    /**
     * @return the max sequence number that was seen before the arrival of the
     * first packet of this frame. This is useful for piggybacking any
     * mis-ordered packets.
     */
    int getMaxSequenceNumberSeenBeforeFirstPacket()
    {
        return maxSequenceNumberSeenBeforeFirstPacket;
    }
}
