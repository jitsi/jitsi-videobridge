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
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.*;
import org.json.simple.*;

import java.util.*;

/**
 * This class represents a projection of a VP8 RTP stream in the RFC 7667 sense
 * and it is the main entry point for VP8 simulcast/svc RTP/RTCP rewriting. Read
 * svc.md for implementation details. Instances of this class are thread-safe.
 *
 * @author George Politis
 */
public class VP8AdaptiveTrackProjectionContext
    implements AdaptiveTrackProjectionContext
{
    private final Logger logger;

    /**
     * The time series logger for this instance.
     */
    private static final TimeSeriesLogger timeSeriesLogger
        = TimeSeriesLogger.getTimeSeriesLogger(VP8AdaptiveTrackProjectionContext.class);

    /**
     * A map that stores the per-encoding VP8 frame maps.
     */
    private final Map<Long, VP8FrameMap>
        vp8FrameMaps = new HashMap<>();

    /**
     * The {@link VP8QualityFilter} instance that does quality filtering on the
     * incoming frames.
     */
    private final VP8QualityFilter vp8QualityFilter;

    /**
     * The diagnostic context of this instance.
     */
    private final DiagnosticContext diagnosticContext;

    /**
     * The "last" {@link VP8FrameProjection} that this instance has accepted.
     * In this context, last here means with the highest sequence number
     * and not, for example, the last one received by the bridge.
     */
    private VP8FrameProjection lastVP8FrameProjection;

    /**
     * The VP8 media format. No essential functionality relies on this field,
     * it's only used as a cache of the {@link PayloadType} instance for VP8 in
     * case we have to do a context switch (see {@link AdaptiveTrackProjection}),
     * in order to avoid having to resolve the format.
     */
    private final PayloadType payloadType;

    /**
     * Ctor.
     *
     * @param payloadType the VP8 media format.
     * @param rtpState the RTP state to begin with.
     */
    public VP8AdaptiveTrackProjectionContext(
            @NotNull DiagnosticContext diagnosticContext,
            @NotNull PayloadType payloadType,
            @NotNull RtpState rtpState,
            @NotNull Logger parentLogger)
    {
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(VP8AdaptiveTrackProjectionContext.class.getName());
        this.payloadType = payloadType;
        this.vp8QualityFilter = new VP8QualityFilter(parentLogger);

        lastVP8FrameProjection = new VP8FrameProjection(diagnosticContext, logger,
            rtpState.ssrc, rtpState.maxSequenceNumber, rtpState.maxTimestamp);
    }

    /** Lookup a Vp8Frame for a packet. */
    private VP8Frame lookupVP8Frame(Vp8Packet vp8Packet)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(vp8Packet.getSsrc());
        if (frameMap == null)
            return null;

        return frameMap.findFrame(vp8Packet);
    }

    /**
     * Insert a packet in the appropriate Vp8FrameMap.
     */
    private VP8FrameMap.FrameInsertionResult insertPacketInMap(Vp8Packet vp8Packet)
    {
        VP8FrameMap frameMap = vp8FrameMaps.computeIfAbsent(vp8Packet.getSsrc(),
            ssrc -> new VP8FrameMap(logger));
        /* TODO: add more context (ssrc?) to frame map's logger or diagnosticContext? */

        return frameMap.insertPacket(vp8Packet);
    }

    /**
     * Find the next frame after the given one.
     */
    public synchronized VP8Frame nextFrame(VP8Frame frame)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(frame.getSsrc());
        if (frameMap == null)
            return null;

        return frameMap.nextFrame(frame);
    }

    /**
     * Find the previous accepted frame before the given one.
     */
    public VP8Frame findPrevAcceptedFrame(VP8Frame frame)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(frame.getSsrc());
        if (frameMap == null)
            return null;

        return frameMap.findPrevAcceptedFrame(frame);
    }

    /**
     * Find a subsequent Tid==0 frame after the given frame
     * @param frame The frame to query
     * @return A subsequent TL0 frame, or null
     */
    private VP8Frame findNextTl0(VP8Frame frame)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(frame.getSsrc());
        if (frameMap == null)
            return null;

        return frameMap.findNextTl0(frame);
    }

    /**
     * Calculate the projected sequence number gap between two frames (of the same encoding),
     * allowing collapsing for unaccepted frames.
     */
    private int seqGap(@NotNull VP8Frame frame1, @NotNull VP8Frame frame2)
    {
        int seqGap = RtpUtils.getSequenceNumberDelta(frame2.getEarliestKnownSequenceNumber(), frame1.getLatestKnownSequenceNumber());

        if (!frame1.isAccepted() && !frame2.isAccepted() &&
            frame2.getPictureId() ==
                Vp8Utils.applyPictureIdDelta(frame1.getPictureId(), 1))
        {
            /* If neither frame is being projected, and they have consecutive
               picture IDs, we don't need to leave any gap. */
            seqGap = 0;
        }
        else
        {
            if (!frame1.isAccepted() && !frame1.hasSeenEndOfFrame() && seqGap > 1)
            {
                seqGap--;
            }
            if (!frame2.isAccepted() && !frame2.hasSeenStartOfFrame() && seqGap > 1)
            {
                seqGap--;
            }
            if (!frame1.isAccepted() && seqGap > 0)
            {
                seqGap--;
            }
        }

        return seqGap;
    }

    /**
     * Calculate the projected picture ID gap between two frames (of the same encoding),
     * allowing collapsing for unaccepted frames.
     */
    private int picGap(@NotNull VP8Frame frame1, @NotNull VP8Frame frame2)
    {
        int picGap = Vp8Utils.getPictureIdDelta(frame2.getPictureId(), frame1.getPictureId());

        if (!frame1.isAccepted() && picGap > 0)
        {
            picGap--;
        }

        return picGap;
    }


    private boolean frameIsNewSsrc(VP8Frame frame)
    {
        return lastVP8FrameProjection.getVP8Frame() == null ||
            !frame.matchesSSRC(lastVP8FrameProjection.getVP8Frame());
    }

    /**
     * Determines whether a packet should be accepted or not.
     *
     * @param rtpPacket the RTP packet to determine whether to project or not.
     * @param incomingIndex the quality index of the incoming RTP packet
     * @param targetIndex the target quality index we want to achieve
     * @return true if the packet should be accepted, false otherwise.
     */
    @Override
    public synchronized boolean accept(
        @NotNull VideoRtpPacket rtpPacket, int incomingIndex, int targetIndex)
    {
        if (!(rtpPacket instanceof Vp8Packet))
        {
            logger.warn("Packet is not VP8 packet");
            return false;
        }

        Vp8Packet vp8Packet = (Vp8Packet)rtpPacket;

        VP8FrameMap.FrameInsertionResult result = insertPacketInMap(vp8Packet);

        if (result == null)
        {
            /* Very old frame, more than Vp8FrameMap.FRAME_MAP_SIZE old. */
            return false;
        }

        VP8Frame frame = result.getFrame();

        if (result.isNewFrame())
        {
            if (vp8Packet.isKeyframe() && frameIsNewSsrc(frame))
            {
            /* If we're not currently projecting this SSRC, make sure we haven't
               already decided not to accept a subsequent TL0 frame of this SSRC.
               If we have, we can't turn on the encoding starting from this
               packet, so treat this frame as though it weren't a keyframe.
             */
                VP8Frame f = findNextTl0(frame);
                if (f != null && !f.isAccepted())
                {
                    frame.setKeyframe(false);
                }
            }

            long nowMs = System.currentTimeMillis();
            boolean accepted = vp8QualityFilter
                .acceptFrame(frame, incomingIndex, targetIndex, nowMs);
            frame.setAccepted(accepted);

            if (accepted)
            {
                VP8FrameProjection projection = createProjection(frame, vp8Packet);
                frame.setProjection(projection);

                if (RtpUtils.isNewerSequenceNumberThan(projection.getEarliestProjectedSequence(),
                        lastVP8FrameProjection.getLatestProjectedSequence()))
                {
                    lastVP8FrameProjection = projection;
                }
            }
        }

        return frame.isAccepted() && frame.getProjection().accept(vp8Packet);
    }

    /**
     * Create a projection for this frame.
     */
    private VP8FrameProjection createProjection(VP8Frame frame, Vp8Packet initialPacket)
    {
        if (frameIsNewSsrc(frame))
        {
            return createLayerSwitchProjection(frame, initialPacket);
        }

        return createInLayerProjection(frame, initialPacket);
    }

    /**
     * Create a projection for this frame. It is the first frame sent for a layer.
     */
    private VP8FrameProjection createLayerSwitchProjection(VP8Frame frame, Vp8Packet initialPacket)
    {
        assert(frame.isKeyframe());
        assert(initialPacket.isStartOfFrame());
        long nowMs = System.currentTimeMillis();

        int projectedSeqGap = 1;

        if (lastVP8FrameProjection.getVP8Frame() != null &&
            !lastVP8FrameProjection.getVP8Frame().hasSeenEndOfFrame())
        {
            /* Leave a gap for the unfinished end of the previously routed frame. */
            projectedSeqGap++;

            /* Make sure subsequent packets of the previous projection won't
               overlap the new one.
             */
            lastVP8FrameProjection.close();
        }

        int projectedSeq = RtpUtils.applySequenceNumberDelta(lastVP8FrameProjection.getLatestProjectedSequence(), projectedSeqGap);

        // this is a simulcast switch. The typical incremental value =
        // 90kHz / 30 = 90,000Hz / 30 = 3000 per frame or per 33ms
        long tsDelta;
        if (lastVP8FrameProjection.getCreatedMs() != 0)
        {
            tsDelta = 3000 * Math.max(1, (nowMs - lastVP8FrameProjection.getCreatedMs()) / 33);
        }
        else
        {
            tsDelta = 3000;
        }
        long projectedTs = RtpUtils.applyTimestampDelta(lastVP8FrameProjection.getTimestamp(), tsDelta);

        int picId;
        int tl0PicIdx;
        if (lastVP8FrameProjection.getVP8Frame() != null)
        {
            picId = Vp8Utils.applyPictureIdDelta(lastVP8FrameProjection.getPictureId(),
                1);
            tl0PicIdx = Vp8Utils.applyTl0PicIdxDelta(lastVP8FrameProjection.getTl0PICIDX(),
                1);
        }
        else {
            picId = frame.getPictureId();
            tl0PicIdx = frame.getTl0PICIDX();
        }

        VP8FrameProjection projection =
            new VP8FrameProjection(diagnosticContext, logger,
                frame, lastVP8FrameProjection.getSSRC(), projectedTs,
                RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.getSequenceNumber()),
                picId, tl0PicIdx, nowMs
            );

        return projection;
    }

    /**
     * Create a projection for this frame. It is being sent subsequent to other projected frames
     * of this layer.
     */
    private VP8FrameProjection createInLayerProjection(VP8Frame frame, Vp8Packet initialPacket)
    {
        long nowMs = System.currentTimeMillis();

        VP8Frame prevFrame = findPrevAcceptedFrame(frame);
        assert(prevFrame != null);

        long tsGap = RtpUtils.getTimestampDiff(frame.getTimestamp(), prevFrame.getTimestamp());
        int tl0Gap = Vp8Utils.getTl0PicIdxDelta(frame.getTl0PICIDX(), prevFrame.getTl0PICIDX());
        int seqGap = 0;
        int picGap = 0;

        VP8Frame f1 = prevFrame, f2;
        do {
            f2 = nextFrame(f1);
            seqGap += seqGap(f1, f2);
            picGap += picGap(f1, f2);
            f1 = f2;
        } while (f2 != frame);

        int projectedSeq = RtpUtils.applySequenceNumberDelta(prevFrame.getProjection().getLatestProjectedSequence(), seqGap);
        long projectedTs = RtpUtils.applyTimestampDelta(prevFrame.getProjection().getTimestamp(), tsGap);
        int projectedPicId = Vp8Utils.applyPictureIdDelta(prevFrame.getProjection().getPictureId(), picGap);
        int projectedTl0PicIdx = Vp8Utils.applyTl0PicIdxDelta(prevFrame.getProjection().getTl0PICIDX(), tl0Gap);

        VP8FrameProjection projection =
            new VP8FrameProjection(diagnosticContext, logger,
                frame, lastVP8FrameProjection.getSSRC(), projectedTs,
                RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.getSequenceNumber()),
                projectedPicId, projectedTl0PicIdx, nowMs
            );

        return projection;
    }

    @Override
    public boolean needsKeyframe()
    {
        if (vp8QualityFilter.needsKeyframe())
        {
            return true;
        }

        if (lastVP8FrameProjection.getVP8Frame() == null)
        {
            /* Never sent anything */
            return true;
        }
        return false;
    }

    /**
     * Rewrites the RTCP packet that is specified as an argument.
     *
     * @param rtcpSrPacket the RTCP packet to transform.
     * @return true if the RTCP packet is accepted, false otherwise, in which
     * case it needs to be dropped.
     */
    @Override
    public boolean rewriteRtcp(@NotNull RtcpSrPacket rtcpSrPacket)
    {
        VP8FrameProjection lastVP8FrameProjectionCopy = lastVP8FrameProjection;
        if (lastVP8FrameProjectionCopy.getVP8Frame() == null
            || rtcpSrPacket.getSenderSsrc() != lastVP8FrameProjectionCopy.getSSRC())
        {
            return false;
        }

        long srcTs = rtcpSrPacket.getSenderInfo().getRtpTimestamp();
        long delta = RtpUtils.getTimestampDiff(
            lastVP8FrameProjectionCopy.getTimestamp(),
            lastVP8FrameProjectionCopy.getVP8Frame().getTimestamp());

        long dstTs = RtpUtils.applyTimestampDelta(srcTs, delta);

        if (srcTs != dstTs)
        {
            rtcpSrPacket.getSenderInfo().setRtpTimestamp(dstTs);
        }

        return true;
    }

    @Override
    public RtpState getRtpState()
    {
        return new RtpState(
            lastVP8FrameProjection.getSSRC(),
            lastVP8FrameProjection.getLatestProjectedSequence(),
            lastVP8FrameProjection.getTimestamp());
    }

    @Override
    public PayloadType getPayloadType()
    {
        return payloadType;
    }

    /**
     * Rewrites the RTP packet that is specified as an argument.
     *
     * @param rtpPacket the RTP packet to rewrite.
     * @throws RewriteException if a VP8 frame projection is not found
     * for the RTP packet that is specified as a parameter.
     */
    @Override
    public void rewriteRtp(
        @NotNull VideoRtpPacket rtpPacket)
        throws RewriteException
    {
        if (!(rtpPacket instanceof Vp8Packet))
        {
            logger.info("Got a non-VP8 packet.");
            throw new RewriteException();
        }

        Vp8Packet vp8Packet = (Vp8Packet)rtpPacket;

        VP8Frame vp8Frame = lookupVP8Frame(vp8Packet);
        if (vp8Frame == null)
        {
            // This packet does not belong to an accepted frame.
            // Possibly it aged off the frame map since accept was called?
            throw new RewriteException();
        }

        if (vp8Frame.getProjection() == null) {
            /* Shouldn't happen for an accepted packet whose frame is still known? */
            throw new RewriteException();
        }

        vp8Frame.getProjection().rewriteRtp((Vp8Packet) rtpPacket);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put(
                "class",
                VP8AdaptiveTrackProjectionContext.class.getSimpleName());

        JSONObject[] mapSizes = new JSONObject[vp8FrameMaps.size()];
        int i = 0;
        for (long ssrc: vp8FrameMaps.keySet())
        {
            mapSizes[i] = new JSONObject();
            mapSizes[i].put("ssrc", ssrc);
            mapSizes[i].put("size", vp8FrameMaps.get(ssrc).size());
        }
        debugState.put(
                "vp8FrameMaps", mapSizes);
        debugState.put("vp8QualityFilter", vp8QualityFilter.getDebugState());
        debugState.put("payloadType", payloadType.toString());

        return debugState;
    }
}
