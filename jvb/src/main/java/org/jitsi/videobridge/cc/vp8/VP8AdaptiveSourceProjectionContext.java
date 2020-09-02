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
import org.jitsi.nlj.codec.vpx.*;
import org.jitsi.nlj.format.*;
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
public class VP8AdaptiveSourceProjectionContext
    implements AdaptiveSourceProjectionContext
{
    private final Logger logger;

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
     * case we have to do a context switch (see {@link AdaptiveSourceProjection}),
     * in order to avoid having to resolve the format.
     */
    private final PayloadType payloadType;

    /**
     * Ctor.
     *
     * @param payloadType the VP8 media format.
     * @param rtpState the RTP state to begin with.
     */
    public VP8AdaptiveSourceProjectionContext(
            @NotNull DiagnosticContext diagnosticContext,
            @NotNull PayloadType payloadType,
            @NotNull RtpState rtpState,
            @NotNull Logger parentLogger)
    {
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(
            VP8AdaptiveSourceProjectionContext.class.getName());
        this.payloadType = payloadType;
        this.vp8QualityFilter = new VP8QualityFilter(parentLogger);

        lastVP8FrameProjection = new VP8FrameProjection(diagnosticContext,
            rtpState.ssrc, rtpState.maxSequenceNumber, rtpState.maxTimestamp);
    }

    /** Lookup a Vp8Frame for a packet. */
    private VP8Frame lookupVP8Frame(@NotNull Vp8Packet vp8Packet)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(vp8Packet.getSsrc());
        if (frameMap == null)
            return null;

        return frameMap.findFrame(vp8Packet);
    }

    /**
     * Insert a packet in the appropriate Vp8FrameMap.
     */
    private VP8FrameMap.FrameInsertionResult insertPacketInMap(
        @NotNull Vp8Packet vp8Packet)
    {
        VP8FrameMap frameMap = vp8FrameMaps.computeIfAbsent(vp8Packet.getSsrc(),
            ssrc -> new VP8FrameMap(logger));
        /* TODO: add more context (ssrc?) to frame map's logger? */

        return frameMap.insertPacket(vp8Packet);
    }

    /**
     * Find the previous frame before the given one.
     */
    @Nullable
    private synchronized VP8Frame prevFrame(@NotNull VP8Frame frame)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(frame.getSsrc());
        if (frameMap == null)
        {
            return null;
        }

        return frameMap.prevFrame(frame);
    }

    /**
     * Find the next frame after the given one.
     */
    @Nullable
    private synchronized VP8Frame nextFrame(@NotNull VP8Frame frame)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(frame.getSsrc());
        if (frameMap == null)
        {
            return null;
        }

        return frameMap.nextFrame(frame);
    }

    /**
     * Find the previous accepted frame before the given one.
     */
    @Nullable
    private VP8Frame findPrevAcceptedFrame(@NotNull VP8Frame frame)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(frame.getSsrc());
        if (frameMap == null)
        {
            return null;
        }

        return frameMap.findPrevAcceptedFrame(frame);
    }

    /**
     * Find the next accepted frame after the given one.
     */
    @Nullable
    private VP8Frame findNextAcceptedFrame(@NotNull VP8Frame frame)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(frame.getSsrc());
        if (frameMap == null)
        {
            return null;
        }

        return frameMap.findNextAcceptedFrame(frame);
    }

    /**
     * Find a subsequent Tid==0 frame after the given frame
     * @param frame The frame to query
     * @return A subsequent TL0 frame, or null
     */
    @Nullable
    private VP8Frame findNextTl0(@NotNull VP8Frame frame)
    {
        VP8FrameMap frameMap = vp8FrameMaps.get(frame.getSsrc());
        if (frameMap == null)
        {
            return null;
        }

        return frameMap.findNextTl0(frame);
    }

    /**
     * Calculate the projected sequence number gap between two frames (of the same encoding),
     * allowing collapsing for unaccepted frames.
     */
    private int seqGap(@NotNull VP8Frame frame1, @NotNull VP8Frame frame2)
    {
        int seqGap = RtpUtils.getSequenceNumberDelta(
            frame2.getEarliestKnownSequenceNumber(),
            frame1.getLatestKnownSequenceNumber()
        );

        if (!frame1.isAccepted() && !frame2.isAccepted() &&
            frame2.isImmediatelyAfter(frame1))
        {
            /* If neither frame is being projected, and they have consecutive
               picture IDs, we don't need to leave any gap. */
            seqGap = 0;
        }
        else
        {
            /* If the earlier frame wasn't projected, and we haven't seen its
             * final packet, we know it has to consume at least one more sequence number. */
            if (!frame1.isAccepted() && !frame1.hasSeenEndOfFrame() && seqGap > 1)
            {
                seqGap--;
            }
            /* Similarly, if the later frame wasn't projected and we haven't seen
             * its first packet. */
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
        int picGap = VpxUtils.getExtendedPictureIdDelta(frame2.getPictureId(), frame1.getPictureId());

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
     * @param packetInfo the RTP packet to determine whether to project or not.
     * @param incomingIndex the quality index of the incoming RTP packet
     * @param targetIndex the target quality index we want to achieve
     * @return true if the packet should be accepted, false otherwise.
     */
    @Override
    public synchronized boolean accept(
        @NotNull PacketInfo packetInfo, int incomingIndex, int targetIndex)
    {
        if (!(packetInfo.getPacket() instanceof Vp8Packet))
        {
            logger.warn("Packet is not VP8 packet");
            return false;
        }
        Vp8Packet vp8Packet = packetInfo.packetAs();

        VP8FrameMap.FrameInsertionResult result = insertPacketInMap(vp8Packet);

        if (result == null)
        {
            /* Very old frame, more than Vp8FrameMap.FRAME_MAP_SIZE old,
               or something wrong with the stream. */
            return false;
        }

        VP8Frame frame = result.getFrame();

        if (result.isNewFrame())
        {
            if (vp8Packet.isKeyframe() && frameIsNewSsrc(frame))
            {
            /* If we're not currently projecting this SSRC, check if we've
               already decided to drop a subsequent TL0 frame of this SSRC.
               If we have, we can't turn on the encoding starting from this
               packet, so treat this frame as though it weren't a keyframe.
             */
                VP8Frame f = findNextTl0(frame);
                if (f != null && !f.isAccepted())
                {
                    frame.setKeyframe(false);
                }
            }

            long receivedMs = packetInfo.getReceivedTime();
            boolean accepted = vp8QualityFilter
                .acceptFrame(frame, incomingIndex, targetIndex, receivedMs);

            if (accepted)
            {
                accepted = checkDecodability(frame);
            }

            frame.setAccepted(accepted);

            if (accepted)
            {
                VP8FrameProjection projection;
                try
                {
                    projection = createProjection(frame, vp8Packet, result.isReset(),
                        receivedMs);
                }
                catch (Exception e)
                {
                    logger.warn("Failed to create frame projection", e);
                    /* Make sure we don't have an accepted frame without a projection in the map. */
                    frame.setAccepted(false);
                    return false;
                }
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
     * For a frame that's been accepted by the quality filter, verify that
     * it's decodable given the projection decisions about previous frames
     * (in case the targetIndex has changed).
     */
    private boolean checkDecodability(@NotNull VP8Frame frame)
    {
        if (frame.isKeyframe() || frame.getTemporalLayer() <= 0)
        {
            /* We'll always project all TL0 or unknown-TL frames, and TL0PICIDX lets the
             * decoder know if it's missed something, so no need to check.
             */
            return true;
        }

        VP8Frame f = frame, prev;

        while ((prev = prevFrame(f)) != null)
        {
            if (!f.isImmediatelyAfter(prev))
            {
                /* If we have a gap in the projection history, we don't know
                 * what will be sent. Default to assuming it'll be decodable.
                 */
                return true;
            }
            if (prev.isKeyframe() || prev.getTemporalLayer() <= frame.getTemporalLayer())
            {
                /* Assume temporal nesting - if the previous frame of a lower
                 * or equal layer was accepted, this frame is decodable, otherwise
                 * it probably isn't.
                 */
                return prev.isAccepted();
            }

            f = prev;
        }
        /* If we ran off the beginning of our history, we don't know what was
         * sent before; assume it'll be decodable. */
        return true;
    }

    /**
     * Create a projection for this frame.
     */
    @NotNull
    private VP8FrameProjection createProjection(
        @NotNull VP8Frame frame,
        @NotNull Vp8Packet initialPacket,
        boolean isReset,
        long receivedMs)
    {
        if (frameIsNewSsrc(frame))
        {
            return createLayerSwitchProjection(frame, initialPacket, receivedMs);
        }

        else if (isReset)
        {
            return createResetProjection(frame, initialPacket, receivedMs);
        }

        return createInLayerProjection(frame, initialPacket, receivedMs);
    }

    /**
     * Create a projection for this frame. It is the first frame sent for a layer.
     */
    @NotNull
    private VP8FrameProjection createLayerSwitchProjection(
        @NotNull VP8Frame frame,
        @NotNull Vp8Packet initialPacket,
        long receivedMs)
    {
        assert(frame.isKeyframe());
        assert(initialPacket.isStartOfFrame());

        int projectedSeqGap = 1;

        if (lastVP8FrameProjection.getVP8Frame() != null &&
            !lastVP8FrameProjection.getVP8Frame().hasSeenEndOfFrame())
        {
            /* Leave a gap to signal to the decoder that the previously routed
               frame was incomplete. */
            projectedSeqGap++;

            /* Make sure subsequent packets of the previous projection won't
               overlap the new one.  (This means the gap, above, will never be
               filled in.)
             */
            lastVP8FrameProjection.close();
        }

        int projectedSeq =
            RtpUtils.applySequenceNumberDelta(lastVP8FrameProjection.getLatestProjectedSequence(), projectedSeqGap);

        // this is a simulcast switch. The typical incremental value =
        // 90kHz / 30 = 90,000Hz / 30 = 3000 per frame or per 33ms
        long tsDelta;
        if (lastVP8FrameProjection.getCreatedMs() != 0)
        {
            tsDelta = 3000 * Math.max(1, (receivedMs - lastVP8FrameProjection.getCreatedMs()) / 33);
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
            picId = VpxUtils.applyExtendedPictureIdDelta(lastVP8FrameProjection.getPictureId(),
                1);
            tl0PicIdx = VpxUtils.applyTl0PicIdxDelta(lastVP8FrameProjection.getTl0PICIDX(),
                1);
        }
        else {
            picId = frame.getPictureId();
            tl0PicIdx = frame.getTl0PICIDX();
        }

        VP8FrameProjection projection =
            new VP8FrameProjection(diagnosticContext,
                frame, lastVP8FrameProjection.getSSRC(), projectedTs,
                RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.getSequenceNumber()),
                picId, tl0PicIdx, receivedMs
            );

        return projection;
    }

    /**
     * Create a projection for this frame.  It follows a large gap in the stream's projected frames.
     */
    @NotNull
    private VP8FrameProjection createResetProjection(@NotNull VP8Frame frame,
        @NotNull Vp8Packet initialPacket, long receivedMs)
    {
        VP8Frame lastFrame = lastVP8FrameProjection.getVP8Frame();

        /* Apply the latest projected frame's projections out, linearly. */
        int seqDelta = RtpUtils.getSequenceNumberDelta(lastVP8FrameProjection.getLatestProjectedSequence(),
            lastFrame.getLatestKnownSequenceNumber());
        long tsDelta = RtpUtils.getTimestampDiff(lastVP8FrameProjection.getTimestamp(),
            lastFrame.getTimestamp());
        int picIdDelta = VpxUtils.getExtendedPictureIdDelta(lastVP8FrameProjection.getPictureId(),
            lastFrame.getPictureId());
        int tl0PicIdxDelta = VpxUtils.getTl0PicIdxDelta(lastVP8FrameProjection.getTl0PICIDX(),
            lastFrame.getTl0PICIDX());

        long projectedTs = RtpUtils.applyTimestampDelta(frame.getTimestamp(), tsDelta);
        int projectedPicId = VpxUtils.applyExtendedPictureIdDelta(frame.getPictureId(), picIdDelta);
        int projectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(frame.getTl0PICIDX(), tl0PicIdxDelta);

        VP8FrameProjection projection =
            new VP8FrameProjection(diagnosticContext,
                frame, lastVP8FrameProjection.getSSRC(), projectedTs,
                seqDelta,
                projectedPicId, projectedTl0PicIdx, receivedMs
            );

        return projection;
    }

    /**
     * Create a projection for this frame. It is being sent subsequent to other projected frames
     * of this layer.
     */
    @NotNull
    private VP8FrameProjection createInLayerProjection(@NotNull VP8Frame frame,
        @NotNull VP8Frame refFrame, @NotNull Vp8Packet initialPacket,
        long receivedMs)
    {
        long tsGap = RtpUtils.getTimestampDiff(frame.getTimestamp(), refFrame.getTimestamp());
        int tl0Gap = VpxUtils.getTl0PicIdxDelta(frame.getTl0PICIDX(), refFrame.getTl0PICIDX());
        int seqGap = 0;
        int picGap = 0;

        VP8Frame f1 = refFrame, f2;
        int refSeq;
        int picIdDelta = VpxUtils.getExtendedPictureIdDelta(refFrame.getPictureId(), frame.getPictureId());
        if (picIdDelta < 0)
        {
            do
            {
                f2 = nextFrame(f1);
                if (f2 == null)
                {
                    throw new IllegalStateException("No next frame found after frame with picId " + f1.getPictureId() +
                        ", even though refFrame " + refFrame.getPictureId() + " is before frame " +
                        frame.getPictureId() + "!");
                }
                seqGap += seqGap(f1, f2);
                picGap += picGap(f1, f2);
                f1 = f2;
            }
            while (f2 != frame);
            refSeq = refFrame.getProjection().getLatestProjectedSequence();
        }
        else
        {
            do
            {
                f2 = prevFrame(f1);
                if (f2 == null)
                {
                    throw new IllegalStateException("No previous frame found before frame with picId " +
                        f1.getPictureId() + ", even though refFrame " + refFrame.getPictureId() + " is after frame " +
                        frame.getPictureId() + "!");
                }
                seqGap += -seqGap(f2, f1);
                picGap += -picGap(f2, f1);
                f1 = f2;
            }
            while (f2 != frame);
            refSeq = refFrame.getProjection().getEarliestProjectedSequence();
        }

        int projectedSeq = RtpUtils.applySequenceNumberDelta(refSeq, seqGap);
        long projectedTs = RtpUtils.applyTimestampDelta(refFrame.getProjection().getTimestamp(), tsGap);
        int projectedPicId = VpxUtils.applyExtendedPictureIdDelta(refFrame.getProjection().getPictureId(), picGap);
        int projectedTl0PicIdx = VpxUtils.applyTl0PicIdxDelta(refFrame.getProjection().getTl0PICIDX(), tl0Gap);

        VP8FrameProjection projection =
            new VP8FrameProjection(diagnosticContext,
                frame, lastVP8FrameProjection.getSSRC(), projectedTs,
                RtpUtils.getSequenceNumberDelta(projectedSeq, initialPacket.getSequenceNumber()),
                projectedPicId, projectedTl0PicIdx, receivedMs
            );

        return projection;
    }

    /**
     * Create a projection for this frame. It is being sent subsequent to other projected frames
     * of this layer.
     */
    @NotNull
    private VP8FrameProjection createInLayerProjection(
        @NotNull VP8Frame frame,
        @NotNull Vp8Packet initialPacket,
        long receivedMs)
    {
        VP8Frame prevFrame = findPrevAcceptedFrame(frame);
        if (prevFrame != null)
        {
            return createInLayerProjection(frame, prevFrame, initialPacket, receivedMs);
        }
        /* prev frame has rolled off beginning of frame map, try next frame */
        VP8Frame nextFrame = findNextAcceptedFrame(frame);
        if (nextFrame != null)
        {
            return createInLayerProjection(frame, nextFrame, initialPacket, receivedMs);
        }

        /* Neither previous or next is found. Very big frame? Use previous projected.
           (This must be valid because we don't execute this function unless
           frameIsNewSsrc has returned false.)
         */
        return createInLayerProjection(frame, lastVP8FrameProjection.getVP8Frame(),
            initialPacket, receivedMs);
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
            || rtcpSrPacket.getSenderSsrc() != lastVP8FrameProjectionCopy.getVP8Frame().getSsrc())
        {
            return false;
        }

        rtcpSrPacket.setSenderSsrc(lastVP8FrameProjectionCopy.getSSRC());

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
     * @param packetInfo the packet info for the RTP packet to rewrite.
     * @throws RewriteException if a VP8 frame projection is not found
     * for the RTP packet that is specified as a parameter.
     */
    @Override
    public void rewriteRtp(
        @NotNull PacketInfo packetInfo)
        throws RewriteException
    {
        if (!(packetInfo.getPacket() instanceof Vp8Packet))
        {
            logger.info("Got a non-VP8 packet.");
            throw new RewriteException("Non-VP8 packet in VP8 source projection");
        }
        Vp8Packet vp8Packet = packetInfo.packetAs();

        if (vp8Packet.getPictureId() == -1)
        {
            /* Should have been rejected in accept(). */
            logger.info("VP8 packet does not have picture ID, cannot track in frame map.");
            throw new RewriteException("VP8 packet without picture ID in VP8 source projection");
        }

        VP8Frame vp8Frame = lookupVP8Frame(vp8Packet);
        if (vp8Frame == null)
        {
            // This packet does not belong to an accepted frame.
            // Possibly it aged off the frame map since accept was called?
            throw new RewriteException("Frame not in tracker (aged off?)");
        }

        if (vp8Frame.getProjection() == null) {
            /* Shouldn't happen for an accepted packet whose frame is still known? */
            throw new RewriteException("Frame does not have projection?");
        }

        vp8Frame.getProjection().rewriteRtp(vp8Packet);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @SuppressWarnings("unchecked")
    public synchronized JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put(
                "class",
                VP8AdaptiveSourceProjectionContext.class.getSimpleName());

        JSONArray mapSizes = new JSONArray();
        for (Map.Entry<Long, VP8FrameMap> entry: vp8FrameMaps.entrySet())
        {
            JSONObject sizeInfo = new JSONObject();
            sizeInfo.put("ssrc", entry.getKey());
            sizeInfo.put("size", entry.getValue().size());
            mapSizes.add(sizeInfo);
        }
        debugState.put(
                "vp8FrameMaps", mapSizes);
        debugState.put("vp8QualityFilter", vp8QualityFilter.getDebugState());
        debugState.put("payloadType", payloadType.toString());

        return debugState;
    }
}
