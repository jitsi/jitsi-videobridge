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
     * A timestamp of when this instance was created. It's used to calculate
     * RTP timestamps when we switch encodings.
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
     * The sequence number delta for packets of this frame.
     */
    private final int sequenceNumberDelta;

    /**
     * The VP8 picture ID of the projection (RFC7667, RFC7741).
     */
    private final int extendedPictureId;

    /**
     * The VP8 TL0PICIDX of the projection (RFC7741).
     */
    private final int tl0PICIDX;

    /**
     * -1 if this projection is still "open" for new, later packets.
     * Projections can be closed when we switch away from their encodings.
     */
    private int closedSeq = -1;

    /**
     * Ctor.
     *
     * @param ssrc the SSRC of the destination VP8 picture.
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param sequenceNumberDelta The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     */
    VP8FrameProjection(
        @NotNull DiagnosticContext diagnosticContext,
        @NotNull Logger parentLogger,
        long ssrc, int sequenceNumberDelta, long timestamp)
    {
        this(diagnosticContext, parentLogger, null /* vp8Frame */, ssrc, timestamp,
            sequenceNumberDelta, 0 /* extendedPictureId */,
            0 /* tl0PICIDX */, 0 /* createdMs */);
    }

    /**
     * Ctor.
     *
     * @param vp8Frame The {@link VP8Frame} that's projected.
     * @param ssrc The RTP SSRC of the projected frame that this instance refers
     * to (RFC3550).
     * @param timestamp The RTP timestamp of the projected frame that this
     * instance refers to (RFC3550).
     * @param sequenceNumberDelta The starting RTP sequence number of the
     * projected frame that this instance refers to (RFC3550).
     * @param extendedPictureId The VP8 extended picture ID of the projected VP8
     * frame that this instance refers to (RFC7741).
     * @param tl0PICIDX The VP8 TL0PICIDX of the projected VP8 frame that this
     * instance refers to (RFC7741).
     */
    VP8FrameProjection(
        @NotNull DiagnosticContext diagnosticContext,
        @NotNull Logger parentLogger,
        VP8Frame vp8Frame,
        long ssrc, long timestamp, int sequenceNumberDelta,
        int extendedPictureId, int tl0PICIDX, long createdMs)
    {
        this.diagnosticContext = diagnosticContext;
        this.parentLogger = parentLogger;
        this.logger = parentLogger.createChildLogger(VP8FrameProjection.class.getName());
        this.ssrc = ssrc;
        this.timestamp = timestamp;
        this.sequenceNumberDelta = sequenceNumberDelta;
        this.extendedPictureId = extendedPictureId;
        this.tl0PICIDX = tl0PICIDX;
        this.vp8Frame = vp8Frame;
        this.createdMs = createdMs;
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

    public int rewriteSeqNo(int seq)
    {
        return RtpUtils.applySequenceNumberDelta(seq, sequenceNumberDelta);
    }

    /**
     * Rewrites an RTP packet.
     *
     * @param pkt the RTP packet to rewrite.
     */
    void rewriteRtp(@NotNull Vp8Packet pkt)
    {
        // update ssrc, sequence number, timestamp, pictureId and tl0picidx
        pkt.setSsrc(ssrc);
        pkt.setTimestamp(timestamp);

        int sequenceNumber = rewriteSeqNo(pkt.getSequenceNumber());
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
     * has been "closed" or not.
     *
     * @param rtpPacket the {@link Vp8Packet} that will be examined.
     * @return true if the packet can be forwarded as part of this
     * {@link VP8FrameProjection}, false otherwise.
     */
    public boolean accept(@NotNull Vp8Packet rtpPacket)
    {
        if (vp8Frame == null || !vp8Frame.matchesFrame(rtpPacket))
        {
            // The packet does not belong to this VP8 picture.
            return false;
        }

        synchronized (vp8Frame)
        {
            if (closedSeq < 0)
            {
                return true;
            }

            return RtpUtils
                .isOlderSequenceNumberThan(rtpPacket.getSequenceNumber(),
                    closedSeq);
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
     * @return The RTP timestamp of this projection.
     */
    public long getTimestamp()
    {
        return timestamp;
    }

    /**
     * @return The picture ID of this projection.
     */
    public int getPictureId()
    {
        return extendedPictureId;
    }

    /**
     * @return The TL0PICIDX of this projection.
     */
    public int getTl0PICIDX()
    {
        return tl0PICIDX;
    }

    /**
     * @return The system time (in ms) this projection was created.
     */
    public long getCreatedMs()
    {
        return createdMs;
    }

    public int getEarliestProjectedSequence()
    {
        if (vp8Frame == null)
        {
            return sequenceNumberDelta;
        }
        synchronized (vp8Frame)
        {
            return rewriteSeqNo(vp8Frame.getEarliestKnownSequenceNumber());
        }
    }

    public int getLatestProjectedSequence()
    {
        if (vp8Frame == null)
        {
            return sequenceNumberDelta;
        }
        synchronized (vp8Frame)
        {
            return rewriteSeqNo(vp8Frame.getLatestKnownSequenceNumber());
        }
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
                closedSeq = vp8Frame.getLatestKnownSequenceNumber();
            }
        }
    }
}
