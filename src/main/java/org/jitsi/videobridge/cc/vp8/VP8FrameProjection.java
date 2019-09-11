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
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.*;

import java.util.*;

/**
 * Represents a VP8 frame projection. It puts together all the necessary bits
 * and pieces that are useful when projecting an accepted VP8 frame. A
 * projection is responsible for rewriting a VP8 packet. Instances of this class
 * are thread-safe.
 *
 * @author George Politis
 */
public class VP8FrameProjection
{
    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The parent logger, so we can pass it to new instances of {@link VP8FrameProjection}
     */
    private final Logger parentLogger;

    /**
     * The time series logger for this instance.
     */
    private static final TimeSeriesLogger timeSeriesLogger
        = TimeSeriesLogger.getTimeSeriesLogger(VP8FrameProjection.class);

    /**
     * An empty packet array.
     */
    private static final Vp8Packet[] EMPTY_PACKET_ARR = new Vp8Packet[0];

    /**
     * The diagnostic context for this instance.
     */
    private final DiagnosticContext diagnosticContext;

    /**
     * The time (in millis) to wait before considering that this frame is fully
     * projected (see {@link #isFullyProjected(long)}).
     */
    private final long WAIT_MS = 5000;

    /**
     * A timestamp of when this instance was created. It's used to compute how
     * long this instance has lived. That's used to determines when it's time to
     * forget about this frame projection (see {@link #isFullyProjected(long)}).
     */
    private final long createdMs;

    /**
     * The projected {@link VP8Frame}.
     */
    private final VP8Frame vp8Frame;

    /**
     * The RTP SSRC of the projection (RFC7667, RFC3550).
     */
    private final long ssrc;

    /**
     * The RTP timestamp of the projection (RFC7667, RFC3550).
     */
    private final long timestamp;

    /**
     * The starting RTP sequence number of projection (RFC7667, RFC3550).
     */
    private final int startingSequenceNumber;

    /**
     * The VP8 picture ID of the projection (RFC7667, RFC7741).
     */
    private final int extendedPictureId;

    /**
     * The VP8 TL0PICIDX of the projection (RFC7741).
     */
    private final int tl0PICIDX;

    /**
     * True if this is the "last" accepted {@link VP8Frame} instance. Last here
     * means with the "highest extended picture id" and not, for instance, the
     * last one received by the bridge.
     */
    private boolean isLast = true;

    /**
     * Ctor.
     *
     * @param ssrc the SSRC of the destination VP8 picture.
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param startingSequenceNumber The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     */
    VP8FrameProjection(
        @NotNull DiagnosticContext diagnosticContext,
        @NotNull Logger parentLogger,
        long ssrc, int startingSequenceNumber, long timestamp)
    {
        this(diagnosticContext, parentLogger, null /* vp8Frame */, ssrc, timestamp,
            startingSequenceNumber, 0 /* extendedPictureId */,
            0 /* tl0PICIDX */, 0 /* createdMs */);
    }

    /**
     * Ctor.
     *
     * @param vp8Frame The {@link VP8Frame} that's projected.
     * @param ssrc The RTP SSRC of the projectd frame that this instance refers
     * to (RFC3550).
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param startingSequenceNumber The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     * @param extendedPictureId The VP8 extended picture ID of the projected VP8
     * frame that this instance refers to (RFC7741).
     * @param tl0PICIDX The VP8 TL0PICIDX of the projected VP8 frame that this
     * instance refers to (RFC7741).
     */
    private VP8FrameProjection(
        @NotNull DiagnosticContext diagnosticContext,
        @NotNull Logger parentLogger,
        VP8Frame vp8Frame,
        long ssrc, long timestamp, int startingSequenceNumber,
        int extendedPictureId, int tl0PICIDX, long createdMs)
    {
        this.diagnosticContext = diagnosticContext;
        this.parentLogger = parentLogger;
        this.logger = parentLogger.createChildLogger(VP8FrameProjection.class.getName());
        this.ssrc = ssrc;
        this.timestamp = timestamp;
        this.startingSequenceNumber = startingSequenceNumber;
        this.extendedPictureId = extendedPictureId;
        this.tl0PICIDX = tl0PICIDX;
        this.vp8Frame = vp8Frame;
        this.createdMs = createdMs;
    }

    /**
     * Makes a VP8 frame projection from the source VP8 frame that is specified
     * as a parameter.
     *
     * @param firstPacketOfFrame the first RTP packet of the frame that we want
     * to project.
     * @param maxSequenceNumberSeenBeforeFirstPacket the max sequence number
     * that was seen before the arrival of the first packet of this frame. This
     * is useful for piggybacking any mis-ordered packets.
     * @param nowMs the current time in millis
     * @return the VP8 frame projection, if the VP8 frame that is specified as
     * an argument is decodable.
     */
    VP8FrameProjection makeNext(
        @NotNull VideoRtpPacket firstPacketOfFrame,
        int maxSequenceNumberSeenBeforeFirstPacket,
        long nowMs)
    {
        // If it's not (a partially transmitted frame) and this is a quality
        // that we want to forward, it is now time to make a new source frame
        // descriptor.
        VP8Frame nextVP8Frame = new VP8Frame(
            firstPacketOfFrame, maxSequenceNumberSeenBeforeFirstPacket);

        // vp8Frame == null is the starting condition: the first
        // VP8FrameProjection does not have an attached VP8Frame.
        if (vp8Frame == null)
        {
            // we're getting frames but it's pointless to forward anything
            // without having sent a keyframe first.
            if (nextVP8Frame.isKeyframe())
            {
                close();
                return new VP8FrameProjection(diagnosticContext, parentLogger,
                    nextVP8Frame, ssrc, timestamp,
                    startingSequenceNumber, extendedPictureId, tl0PICIDX,
                    nowMs);
            }
            else
            {
                return null;
            }
        }
        else if (!vp8Frame.decodes(nextVP8Frame))
        {
            // Check whether accepting this picture will result into a decodable
            // VP8 stream.
            return null;
        }
        else
        {
            // We synchronize on the VP8 frame because the max sequence number
            // can be updated from other threads and the isLast field can be
            // read by other threads.
            close();
            return new VP8FrameProjection(diagnosticContext, parentLogger,
                nextVP8Frame, ssrc, nextTimestamp(nextVP8Frame, nowMs),
                nextStartingSequenceNumber(), nextExtendedPictureId(),
                nextTL0PICIDX(nextVP8Frame), nowMs);
        }
    }

    /**
     * Small utility method that computes and returns the TL0PICIDX to use in
     * the projection of the frame that is specified as an argument. The
     * specified frame is assumed to be the frame that will be sent immediately
     * after the {@link #vp8Frame}.
     *
     * @param nextVP8Frame the frame that will be sent immediately after
     * {@link #vp8Frame}
     * @return the TL0PICIDX to use in the projection of the frame that is
     * specified as an argument.
     */
    private int nextTL0PICIDX(@NotNull VP8Frame nextVP8Frame)
    {
        return nextVP8Frame.isTL0()
            ? VP8Frame.nextTL0PICIDX(tl0PICIDX) : tl0PICIDX;
    }

    /**
     * Small utility method that computes and returns the RTP timestamp to use
     * in the projection of the frame that is specified as an argument. The
     * specified frame is assumed to be the frame that will be sent immediately
     * after the {@link #vp8Frame}.
     *
     * @param nextVP8Frame the frame that will be sent immediately after
     * {@link #vp8Frame}
     * @return the TL0PICIDX to use in the projection of the frame that is
     * specified as an argument.
     */
    private long nextTimestamp(@NotNull VP8Frame nextVP8Frame, long nowMs)
    {
        long delta;
        if (!vp8Frame.matchesSSRC(nextVP8Frame))
        {
            // this is a simulcast switch. The typical incremental value =
            // 90kHz / 30 = 90,000Hz / 30 = 3000 per frame or per 33ms
            delta = 3000 * Math.max(1, (nowMs - createdMs) / 33);
        }
        else
        {
            // compute and apply a delta
            delta = RtpUtils.getTimestampDiff(
                nextVP8Frame.getTimestamp(), vp8Frame.getTimestamp());
        }

        return RtpUtils.applyTimestampDelta(timestamp, delta);
    }

    /**
     * Small utility method that computes and returns the starting sequence
     * number to use in the projection of the frame that will be sent
     * immediately after the {@link #vp8Frame}.
     *
     * @return the starting sequence number to use in the projection of the
     * frame that will be sent immediately after the {@link #vp8Frame}.
     */
    private int nextStartingSequenceNumber()
    {
        return RtpUtils.applySequenceNumberDelta(maxSequenceNumber(), 1);
    }

    /**
     * Small utility method that computes and returns the max sequence number of
     * this frame.
     *
     * @return the max sequence number of this frame.
     */
    int maxSequenceNumber()
    {
        // assert !isLast; otherwise the maxSequenceNumber is not guaranteed to
        // not change. So, prior to calling this method the caller needs to have
        // called the close method.
        if (vp8Frame != null)
        {
            int vp8FrameLength
                    = RtpUtils.getSequenceNumberDelta(
                        vp8Frame.getMaxSequenceNumber(),
                        vp8Frame.getStartingSequenceNumber());

            return RtpUtils.applySequenceNumberDelta(startingSequenceNumber, vp8FrameLength);
        }
        else
        {
            return RtpUtils.applySequenceNumberDelta(startingSequenceNumber, -1);
        }
    }

    /**
     * Small utility method that computes and returns the extended picture id to
     * use in the projection of the frame that will be sent immediately after
     * the {@link #vp8Frame}.
     *
     * @return the extended picture id to use in the projection of the frame
     * that will be sent immediately after the {@link #vp8Frame}.
     */
    private int nextExtendedPictureId()
    {
        return (extendedPictureId + 1)
            & DePacketizer.VP8PayloadDescriptor.EXTENDED_PICTURE_ID_MASK;
    }

    /**
     * Rewrites an RTP packet that belongs to {@link #vp8Frame}. If this is the
     * first packet of a frame and if a packet cache has been specified, we
     * piggy-backing any missed packets.
     *
     * @param cache the cache to pull piggy-backed packets from.
     * @param rtpPacket the RTP packet to project.
     */
    Vp8Packet[] rewriteRtp(@NotNull Vp8Packet rtpPacket, PacketCache cache)
    {
        int originalSequenceNumber = rtpPacket.getSequenceNumber();

        rewriteRtpInternal(rtpPacket);

        int piggyBackUntilSequenceNumber
            = vp8Frame.getMaxSequenceNumberSeenBeforeFirstPacket();

        if (piggyBackUntilSequenceNumber < 0
            || originalSequenceNumber != vp8Frame.getStartingSequenceNumber()
            || cache == null)
        {
            return EMPTY_PACKET_ARR;
        }

        // We piggy-back any re-ordered packets of this frame.
        long vp8FrameSSRC = vp8Frame.getSSRCAsLong();

        List<Vp8Packet> piggyBackedPackets = new ArrayList<>();
        int len = RtpUtils.getSequenceNumberDelta(
            piggyBackUntilSequenceNumber, originalSequenceNumber) + 1;

        logger.debug(() -> "Piggybacking " + len + " missed packets from "
            + originalSequenceNumber
            + " until " + piggyBackUntilSequenceNumber);

        for (int i = 0; i < len; i++)
        {
            int piggyBackedPacketSequenceNumber
                = RtpUtils.applySequenceNumberDelta(originalSequenceNumber, i);

            ArrayCache.Container container
                    = cache.get(vp8FrameSSRC, piggyBackedPacketSequenceNumber);
            Vp8Packet lastPacket
                    = container == null ? null : (Vp8Packet) container.getItem();

            // the call to accept (synchronized) may update the
            // maxSequenceNumber.
            //
            // XXX Calling accept here might seem bizarre so it merits a
            // small explanation. This call takes place in the transform
            // thread, so by the time we get to rewrite the accepted first
            // packet of a frame, the first packet of another frame may have
            // already been accepted, which means there's no longer space to
            // piggyback anything.
            if (lastPacket != null && accept(lastPacket))
            {
                piggyBackedPackets.add(lastPacket);
            }
        }

        if (piggyBackedPackets.size() > 0)
        {
            logger.debug(() -> "Sending " + piggyBackedPackets.size()
                    + " piggybacked packets");
            for (Vp8Packet pktOut : piggyBackedPackets)
            {
                rewriteRtpInternal(pktOut);
            }

            return piggyBackedPackets.toArray(new Vp8Packet[0]);
        }
        else
        {
            return EMPTY_PACKET_ARR;
        }
    }

    /**
     * Rewrites a single RTP packet.
     *
     * @param pkt the RTP packet to rewrite.
     */
    private void rewriteRtpInternal(@NotNull Vp8Packet pkt)
    {
        // update ssrc, sequence number, timestamp, pictureId and tl0picidx
        pkt.setSsrc(ssrc);
        pkt.setTimestamp(timestamp);

        int sequenceNumberDelta
                = RtpUtils.getSequenceNumberDelta(
                    pkt.getSequenceNumber(),
                    vp8Frame.getStartingSequenceNumber());

        int sequenceNumber
            = RtpUtils.applySequenceNumberDelta(startingSequenceNumber, sequenceNumberDelta);
        pkt.setSequenceNumber(sequenceNumber);

        pkt.setTL0PICIDX(tl0PICIDX);
        pkt.setPictureId(extendedPictureId);

        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                    .makeTimeSeriesPoint("rtp_vp8_rewrite")
                    .addField("rtp.ssrc", ssrc)
                    .addField("rtp.timestamp", timestamp)
                    .addField("rtp.seq", sequenceNumber)
                    .addField("vp8.pictureid", extendedPictureId)
                    .addField("vp8.tl0picidx", tl0PICIDX));
        }
    }

    /**
     * Determines whether a packet can be forwarded as part of this
     * {@link VP8FrameProjection} instance. The check is based on the sequence
     * of the incoming packet and whether or not the {@link VP8FrameProjection}
     * instance is the "last" {@link VP8FrameProjection} or not.
     *
     * @param rtpPacket the {@link VideoRtpPacket} that will be examined .
     * @return true if the packet can be forwarded as part of this
     * {@link VP8FrameProjection}, false otherwise.
     */
    public boolean accept(@NotNull VideoRtpPacket rtpPacket)
    {
        if (vp8Frame == null || !vp8Frame.matchesFrame(rtpPacket))
        {
            // The packet does not belong to this VP8 picture.
            return false;
        }

        synchronized (vp8Frame)
        {
            int sequenceNumber = rtpPacket.getSequenceNumber();
            int deltaFromMax
                = RtpUtils.getSequenceNumberDelta(
                        vp8Frame.getMaxSequenceNumber(),
                        sequenceNumber);

            boolean isGreaterThanMax
                = vp8Frame.getMaxSequenceNumber() == -1 || deltaFromMax < 0;

            if (isLast)
            {
                if (isGreaterThanMax)
                {
                    vp8Frame.setMaxSequenceNumber(
                        sequenceNumber, rtpPacket.isMarked());
                }

                return true;
            }
            else
            {
                return !isGreaterThanMax;
            }
        }
    }

    /**
     * @return The projected {@link VP8Frame}.
     */
    VP8Frame getVP8Frame()
    {
        return vp8Frame;
    }

    /**
     * @return The RTP SSRC of this projection.
     */
    public long getSSRC()
    {
        return ssrc;
    }

    /**
     * @return true if we have projected all the packets from starting sequence
     * number to max sequence number.
     */
    boolean isFullyProjected(long nowMs)
    {
        // XXX The idea is that after we've fully projected all the packets of a
        // frame, we no longer need the frame projection instance and it can be
        // expired/discarded to prevent the map that stores these projections
        // (found in VP8AdaptiveTrackProjectionContext) from growing too big.
        // Any re-transmissions of that frame can be safely dropped and,
        // basically, the frame projection instance becomes completely useless.
        //
        // To implement this correctly we need 1) a check that determines if all
        // the packets of the projected frame have been projected and 2nd) an
        // upper bound of the time that we're willing to wait for the packets to
        // arrive and be projected (keep in  the sender may decide to never send
        // some packets).
        //
        // Due to lack of time in this method we've only implemented the 2nd,
        // easier part, so we assume a frame is fully projected after WAIT_MS.
        // The first approach would require a packet loss bitmap of the received
        // packets of a frame.
        return nowMs - createdMs > WAIT_MS;
    }

    /**
     * @return The RTP timestamp of this projection.
     */
    long getTimestamp()
    {
        return timestamp;
    }

    /**
     * Prevents the max sequence number of this frame to grow any further.
     */
    public void close()
    {
        if (vp8Frame != null)
        {
            synchronized (vp8Frame)
            {
                isLast = false;
            }
        }
        else
        {
            isLast = false;
        }
    }
}
