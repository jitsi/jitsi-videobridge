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
import org.jitsi.nlj.codec.vp8.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.rtp.util.*;

/**
 * Groups together some RTP/VP8 fields that refer to a specific incoming VP8
 * frame. Most of those fields are final and cannot be changed, with the
 * exception of the ending and max sequence number that may be unknown at the
 * time of the creation of this instance.
 *
 * Instances of this class are *NOT* thread safe. While most internal state of
 * this class instances is final, the sequence number ranges, haveStart/haveEnd,
 * and isKeyframe are not.
 *
 * @author George Politis
 * @author Jonathan Lennox
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
     * The earliest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    private int earliestKnownSequenceNumber;

    /**
     * The latest RTP sequence number seen of the incoming frame that this instance
     * refers to (RFC3550).
     */
    private int latestKnownSequenceNumber;

    /**
     * A boolean that indicates whether or not we've seen the first packet of the frame.
     * If so, its sequence is earliestKnownSequenceNumber.
     */
    private boolean seenStartOfFrame;

    /**
     * A boolean that indicates whether or not we've seen the last packet of the frame.
     * If so, its sequence is latestKnownSequenceNumber.
     */
    private boolean seenEndOfFrame;

    /**
     * The temporal layer of this frame.
     */
    private final int temporalLayer;

     /**
     * The VP8 PictureID of the incoming VP8 frame that this instance refers to
     * (RFC7741).
     */
    private final int pictureId;

   /**
     * The VP8 TL0PICIDX of the incoming VP8 frame that this instance refers to
     * (RFC7741).
     */
    private final int tl0PICIDX;

    /**
     * A boolean that indicates whether the incoming VP8 frame that this
     * instance refers to is a keyframe (RFC7741).
     */
    private boolean isKeyframe;

    /**
     * A record of how this frame was projected, or null if not.
     */
    private VP8FrameProjection projection;

    /**
     * A boolean that records whether this frame was accepted.
     */
    private boolean accepted;

    /**
     * Ctor.
     *
     * @param packet A packet from the frame to be constructed.
     */
    VP8Frame(@NotNull Vp8Packet packet)
    {
        this.ssrc = packet.getSsrc();
        this.timestamp = packet.getTimestamp();
        this.earliestKnownSequenceNumber = packet.getSequenceNumber();
        this.latestKnownSequenceNumber = packet.getSequenceNumber();
        this.seenStartOfFrame = packet.isStartOfFrame();
        this.seenEndOfFrame = packet.isEndOfFrame();

        this.tl0PICIDX = packet.getTL0PICIDX();
        this.isKeyframe = packet.isKeyframe();
        this.pictureId = packet.getPictureId();
        this.temporalLayer = packet.getTemporalLayerIndex();
    }

    /**
     * Remember another packet of this frame.
     * Note: this assumes every packet is received only once, i.e. a filter
     * like {@link org.jitsi.nlj.transform.node.incoming.PaddingTermination} is in use.
     * @param packet The packet to remember.  This should be a packet which
     *               has tested true with {@link #matchesFrame(Vp8Packet)}.
     */
    void addPacket(@NotNull Vp8Packet packet)
    {
        if (!matchesFrame(packet))
        {
            throw new IllegalArgumentException("Non-matching packet added to frame");
        }
        int seq = packet.getSequenceNumber();
        if (RtpUtils.isOlderSequenceNumberThan(seq, earliestKnownSequenceNumber))
        {
            earliestKnownSequenceNumber = seq;
        }
        if (RtpUtils.isNewerSequenceNumberThan(seq, latestKnownSequenceNumber))
        {
            latestKnownSequenceNumber = seq;
        }
        if (packet.isStartOfFrame())
        {
            seenStartOfFrame = true;
        }
        if (packet.isEndOfFrame())
        {
            seenEndOfFrame = true;
        }
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
     * Update a frame's notion of whether it is a keyframe.
     */
    void setKeyframe(boolean k)
    {
        isKeyframe = k;
    }

    /**
     * @return the temporal layer of this frame.
     */
    int getTemporalLayer()
    {
        return temporalLayer;
    }

    /**
     * @return true if this is a base temporal layer frame, false otherwise
     */
    boolean isTL0()
    {
        return temporalLayer == 0;
    }

    /**
     * Gets the SSRC.
     */
    long getSsrc()
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
     * Gets the earliest sequence number seen for this frame.
     */
    public int getEarliestKnownSequenceNumber()
    {
        return earliestKnownSequenceNumber;
    }

    /**
     * Gets the latest sequence number seen for this frame.
     */
    public int getLatestKnownSequenceNumber()
    {
        return latestKnownSequenceNumber;
    }

    /**
     * Whether the start of this frame has been seen.
     */
    public boolean hasSeenStartOfFrame()
    {
        return seenStartOfFrame;
    }

    /**
     * Whether the end of this frame has been seen.
     */
    public boolean hasSeenEndOfFrame()
    {
        return seenEndOfFrame;
    }

    /**
     * Get the projection of this frame, or null.
     */
    public VP8FrameProjection getProjection()
    {
        return projection;
    }

    /**
     * Get whether this frame has been accepted.
     */
    public boolean isAccepted()
    {
        return accepted;
    }

    /**
     * Set whether this frame has been accepted.
     */
    public void setAccepted(boolean a)
    {
        accepted = a;
    }

    /**
     * Set the projection record of this frame.
     */
    public void setProjection(VP8FrameProjection projection)
    {
        this.projection = projection;
    }

    /**
     * Get the picture ID of this frame.
     */
    public int getPictureId()
    {
        return pictureId;
    }

    /**
     * Get the tl0picidx of this frame.
     */
    public int getTl0PICIDX()
    {
        return tl0PICIDX;
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
     * Checks whether the specified RTP packet is part of this frame.
     *
     * @param pkt the RTP packet to check whether it's part of this frame.
     * @return true if the specified RTP packet is part of this frame, false
     * otherwise.
     */
    boolean matchesFrame(@NotNull Vp8Packet pkt)
    {
        return matchesSSRC(pkt) && timestamp == pkt.getTimestamp();
    }

    /**
     * Validates that the specified RTP packet consistently matches all the
     * parameters of this frame.
     *
     * This can be useful for diagnosing invalid streams if this fails when
     * {@link #matchesFrame(Vp8Packet)} is true.
     *
     * @param pkt the RTP packet to check whether its parameters match this frame.
     * @throws RuntimeException if the specified RTP packet is inconsistent with this frame
     */
    void validateConsistent(@NotNull Vp8Packet pkt)
    {
        if (temporalLayer == pkt.getTemporalLayerIndex() &&
            tl0PICIDX == pkt.getTL0PICIDX() &&
            pictureId == pkt.getPictureId())
            /* TODO: also check start, end, seq nums? */
        {
            return;
        }

        StringBuilder s = new StringBuilder().append("Packet ")
            .append(pkt.getSequenceNumber())
            .append(" is not consistent with frame with timestamp ")
            .append(timestamp)
            .append(":");

        boolean complained = false;

        if (temporalLayer != pkt.getTemporalLayerIndex())
        {
            s.append("packet temporal layer")
                .append(pkt.getTemporalLayerIndex())
                .append(" != frame temporal layer ")
                .append(temporalLayer);
            complained = true;
        }
        if (tl0PICIDX != pkt.getTL0PICIDX())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet TL0PICIDX")
                .append(pkt.getTL0PICIDX())
                .append(" != frame TL0PICIDX ")
                .append(tl0PICIDX);
            complained = true;
        }
        if (pictureId != pkt.getPictureId())
        {
            if (complained)
            {
                s.append("; ");
            }
            s.append("packet PictureID")
                .append(pkt.getPictureId())
                .append(" != frame PictureID ")
                .append(pictureId);
        }
        throw new RuntimeException(s.toString());
    }

    /**
     * Check whether this frame is immediately after another one, according
     * to their extended picture IDs.
     */
    boolean isImmediatelyAfter(@NotNull VP8Frame otherFrame)
    {
        return pictureId ==
            Vp8Utils.applyExtendedPictureIdDelta(otherFrame.getPictureId(), 1);
    }
}
