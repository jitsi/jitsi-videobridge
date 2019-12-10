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
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.nlj.rtp.codec.vp8.*;
import org.jitsi.nlj.util.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.rtp.util.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.cc.*;
import org.jitsi_modified.impl.neomedia.codec.video.vp8.*;
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
     * In this context, last here means with the highest SSRC number
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
            ssrc -> new VP8FrameMap(diagnosticContext, logger));
        /* TODO: add more context (ssrc?) to frame map's logger or diagnosticContext? */

        return frameMap.insertPacket(vp8Packet);
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
     * allowing collapsing for unprojected frames.
     */
    private int seqGap(@NotNull VP8Frame frame1, boolean frame1projected,
        @NotNull VP8Frame frame2, boolean frame2projected)
    {
        int seqGap = RtpUtils.getSequenceNumberDelta(frame2.getEarliestKnownSequenceNumber(), frame1.getLatestKnownSequenceNumber());

        if (false)
            return seqGap;

        if (!frame1projected && !frame2projected &&
            frame2.getPictureId() == ((frame1.getPictureId() + 1) &
                DePacketizer.VP8PayloadDescriptor.EXTENDED_PICTURE_ID_MASK))
        {
            /* If neither frame is being projected, and they have consecutive
               picture IDs, we don't need to leave any gap. */
            seqGap = 0;
        }
        else
        {
            if (!frame1projected && !frame1.hasSeenEndOfFrame() && seqGap > 1)
            {
                seqGap--;
            }
            if (!frame2projected && !frame2.hasSeenStartOfFrame() && seqGap > 1)
            {
                seqGap--;
            }
        }

        return seqGap;
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

        if (!result.isNewFrame())
        {
            VP8FrameProjection projection = result.getFrame().getProjection();
            boolean accept = (projection != null && projection.accept(vp8Packet));

            if (timeSeriesLogger.isTraceEnabled())
            {
                if (accept)
                {
                    DiagnosticContext.TimeSeriesPoint point =
                        diagnosticContext.makeTimeSeriesPoint("rtp_vp8_existing_projection")
                            .addField("proj.rtp.seq", projection
                                .rewriteSeqNo(vp8Packet.getSequenceNumber()));
                    addPacketToPoint(vp8Packet, point);
                    addProjectionToPoint(projection, point);
                    addFrameToPoint(result.getFrame(), point);
                    timeSeriesLogger.trace(point);
                }
                else
                {
                    VP8ProjectionRecord rec =
                        result.getFrame().getProjectionRecord();
                    DiagnosticContext.TimeSeriesPoint point =
                        diagnosticContext.makeTimeSeriesPoint("rtp_vp8_existing_unprojected");
                    addPacketToPoint(vp8Packet, point);
                    addProjectionRecordToPoint(rec, point);
                    addFrameToPoint(result.getFrame(), point);
                    timeSeriesLogger.trace(point);
                }
            }
            return accept;
        }

        long nowMs = System.currentTimeMillis();

        if (vp8Packet.isKeyframe() &&
            ((lastVP8FrameProjection.getVP8Frame() == null ||
             lastVP8FrameProjection.getVP8Frame().getSsrc() != frame.getSsrc())))
        {
            /* If we're not currently projecting this SSRC, make sure we haven't
               already decided not to project a subsequent TL0 frame of this SSRC.
               If we have, we can't turn on the encoding starting from this
               packet, so treat this frame as though it weren't a keyframe.
             */
            VP8Frame f = findNextTl0(frame);
            if (f != null && f.getProjection() == null)
            {
                frame.setKeyframe(false);
            }
        }

        /* This is a new frame.  Check whether it should be accepted. */
        boolean accept = vp8QualityFilter.acceptFrame(frame,
            incomingIndex, targetIndex, nowMs);

        int projectedSeq;
        long projectedTs;
        int projectedPicId;

        if ((lastVP8FrameProjection.getVP8Frame() == null ||
            lastVP8FrameProjection.getVP8Frame().getSsrc() != frame.getSsrc()) &&
            accept)
        {
            /* We're switching to a new encoding. */
            assert(frame.isKeyframe());
            /* Calculate the sequence number gap. */
            /* Because we can only recognize VP8 keyframes from their first packet,
               we don't need to do elaborate math here.
             */

            int projectedSeqGap = 1;

            if (lastVP8FrameProjection.getVP8Frame() != null &&
                !lastVP8FrameProjection.getVP8Frame().hasSeenEndOfFrame())
            {
                /* Leave a gap for the unfinished end of the previously routed
                   frame.
                 */
                projectedSeqGap++;

                /* Make sure subsequent packets of the previous projection won't
                   overlap the new one.
                 */
                lastVP8FrameProjection.close();
            }

            projectedSeq = RtpUtils.applySequenceNumberDelta(lastVP8FrameProjection.getLatestProjectedSequence(), projectedSeqGap);

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
            projectedTs = RtpUtils.applyTimestampDelta(lastVP8FrameProjection.getTimestamp(), tsDelta);
        }
        else if (result.getNextFrame() != null)
        {
            /* This frame is old, slotted in after an earlier frame. */
            VP8Frame nextFrame = result.getNextFrame();
            int seqGap = seqGap(frame, accept, nextFrame, nextFrame.getProjection() != null);

            projectedSeq = RtpUtils.applySequenceNumberDelta(nextFrame.getProjectionRecord().getEarliestProjectedSequence(), -seqGap);

            long tsGap = RtpUtils.getTimestampDiff(vp8Packet.getTimestamp(), nextFrame.getTimestamp());
            projectedTs = RtpUtils.applyTimestampDelta(nextFrame.getProjectionRecord().getTimestamp(), tsGap);
        }
        else if (result.getPrevFrame() != null)
        {
            /* This frame is the newest frame. */
            VP8Frame prevFrame = result.getPrevFrame();

            int seqGap = seqGap(prevFrame, prevFrame.getProjection() != null, frame, accept);

            if (prevFrame.getProjection() == null && seqGap > 0)
            {
                seqGap--;
            }

            projectedSeq = RtpUtils.applySequenceNumberDelta(prevFrame.getProjectionRecord().getLatestProjectedSequence(), seqGap);

            long tsGap = RtpUtils.getTimestampDiff(vp8Packet.getTimestamp(), prevFrame.getTimestamp());
            projectedTs = RtpUtils.applyTimestampDelta(prevFrame.getProjectionRecord().getTimestamp(), tsGap);
        }
        else
        {
            /* This frame is the first frame we've seen on this encoding. */

            assert(!accept); /* We can only get to this clause if this frame hasn't been accepted, so these values don't matter much. */
            projectedSeq = RtpUtils.applySequenceNumberDelta(lastVP8FrameProjection.getLatestProjectedSequence(), 1);
            projectedTs = lastVP8FrameProjection.getTimestamp() + 3000;
        }

        if (accept) {
            VP8FrameProjection projection =
                new VP8FrameProjection(diagnosticContext, logger,
                    frame, lastVP8FrameProjection.getSSRC(), projectedTs,
                    RtpUtils.getSequenceNumberDelta(projectedSeq, vp8Packet.getSequenceNumber()),
                    1 /* TODO: pic id */, 1 /* TODO: tl0picidx */, nowMs
                    );
            lastVP8FrameProjection = projection;
            frame.setProjectionRecord(projection);

            if (timeSeriesLogger.isTraceEnabled())
            {
                DiagnosticContext.TimeSeriesPoint point =
                    diagnosticContext.makeTimeSeriesPoint("rtp_vp8_new_projection")
                        .addField("proj.rtp.seq", projectedSeq);
                addPacketToPoint(vp8Packet, point);
                addProjectionToPoint(projection, point);
                addFrameToPoint(result.getFrame(), point);
                addPrevAndNextToPoint(result.getPrevFrame(), result.getNextFrame(), point);
                timeSeriesLogger.trace(point);
            }
        }
        else {
            VP8ProjectionRecord rec = new VP8UnprojectedFrame(projectedSeq, projectedTs);
            frame.setProjectionRecord(rec);

            if (timeSeriesLogger.isTraceEnabled())
            {
                DiagnosticContext.TimeSeriesPoint point =
                    diagnosticContext.makeTimeSeriesPoint("rtp_vp8_unprojected");
                addPacketToPoint(vp8Packet, point);
                addProjectionRecordToPoint(rec, point);
                addFrameToPoint(result.getFrame(), point);
                addPrevAndNextToPoint(result.getPrevFrame(), result.getNextFrame(), point);
                timeSeriesLogger.trace(point);
            }
        }

        /* Sanity check */
        if (result.getPrevFrame() != null)
        {
            assert (!RtpUtils.isOlderSequenceNumberThan(projectedSeq,
                result.getPrevFrame().getProjectionRecord()
                    .getLatestProjectedSequence()));
            if (accept && result.getPrevFrame().getProjection() != null) {
                assert (RtpUtils.isNewerSequenceNumberThan(projectedSeq,
                    result.getPrevFrame().getProjectionRecord()
                        .getLatestProjectedSequence()));
            }
        }
        if (result.getNextFrame() != null)
        {
            assert (!RtpUtils.isNewerSequenceNumberThan(projectedSeq,
                result.getNextFrame().getProjectionRecord()
                    .getEarliestProjectedSequence()));
            if (accept)
            {
                assert (RtpUtils.isOlderSequenceNumberThan(projectedSeq,
                    result.getNextFrame().getProjectionRecord()
                        .getEarliestProjectedSequence()));
            }
        }

        return accept;
    }

    private static void addPacketToPoint(Vp8Packet vp8Packet, DiagnosticContext.TimeSeriesPoint point)
    {
        point.addField("orig.rtp.ssrc", vp8Packet.getSsrc())
            .addField("orig.rtp.timestamp", vp8Packet.getTimestamp())
            .addField("orig.rtp.seq", vp8Packet.getSequenceNumber())
            .addField("orig.vp8.pictureid", vp8Packet.getPictureId())
            .addField("orig.vp8.tl0picidx", vp8Packet.getTL0PICIDX())
            .addField("orig.vp8.tid", vp8Packet.getTemporalLayerIndex())
            .addField("orig.vp8.start", vp8Packet.isStartOfFrame())
            .addField("orig.vp8.end", vp8Packet.isEndOfFrame());
    }

    private static void addFrameToPoint(VP8Frame frame, DiagnosticContext.TimeSeriesPoint point)
    {
        point.addField("frame", describeFrameForPoint(frame));
    }

    private static void addProjectionToPoint(VP8FrameProjection projection, DiagnosticContext.TimeSeriesPoint point)
    {
        point.addField("proj.rtp.ssrc", projection.getSSRC())
        .addField("proj.rtp.timestamp", projection.getTimestamp())
        .addField("proj.vp8.pictureid", projection.getPictureId())
        .addField("proj.vp8.tl0picidx", projection.getTl0PICIDX());
    }

    private static void addProjectionRecordToPoint(VP8ProjectionRecord rec, DiagnosticContext.TimeSeriesPoint point)
    {
        point.addField("unproj.rtp.timestamp", rec.getTimestamp())
        .addField("unproj.rtp.seq", rec.getEarliestProjectedSequence());
    }

    private static String describeFrameForPoint(VP8Frame frame)
    {
        StringBuilder b = new StringBuilder();

        b.append(frame.hasSeenStartOfFrame() ? '[' : '(')
            .append(frame.getEarliestKnownSequenceNumber())
            .append('-')
            .append(frame.getLatestKnownSequenceNumber())
            .append(frame.hasSeenEndOfFrame() ? ']' : ')');

        VP8FrameProjection proj = frame.getProjection();
        if (proj != null)
        {
            b.append('→')
                .append(frame.hasSeenStartOfFrame() ? '[' : '(')
                .append(proj.getEarliestProjectedSequence())
                .append('-')
                .append(proj.getLatestProjectedSequence())
                .append(frame.hasSeenEndOfFrame() ? ']' : ')');
        }
        else {
            b.append('↝')
                .append(frame.getProjectionRecord().getEarliestProjectedSequence());
        }

        return b.toString();
    }

    private static void addPrevAndNextToPoint(VP8Frame prevFrame, VP8Frame nextFrame, DiagnosticContext.TimeSeriesPoint point)
    {
        if (prevFrame != null)
        {
            point.addField("prevFrame", describeFrameForPoint(prevFrame));
        }
        else
        {
            point.addField("prevFrame", null);
        }

        if (nextFrame != null)
        {
            point.addField("nextFrame", describeFrameForPoint(nextFrame));
        }
        else
        {
            point.addField("nextFrame", null);
        }
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
        if (vp8Frame == null || vp8Frame.getProjection() == null)
        {
            // This packet does not belong to a projected frame.
            // Possibly it aged off the frame map since accept was called?
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
