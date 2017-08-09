/*
 * Copyright @ 2017 Atlassian Pty Ltd
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
package org.jitsi.videobridge.cc;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.codec.*;
import org.jitsi.impl.neomedia.codec.video.vp8.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.util.*;
import org.jitsi.util.function.*;

import java.util.*;

/**
 * Filters packets coming from a specific RTP stream (which may or may not
 * be a scalable stream) according to its:
 * a) ability to forward the bitstream correctly with regards to sequence
 * numbers, timestamps, and (potentially) codec-specific information
 * b) its desire to forward a given quality layer
 *
 * @author George Politis
 * @author Brian Baldino
 */
public class BitstreamController
{
    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger = Logger.getLogger(BitstreamController.class);

    /**
     * The ConfigurationService to get config values from.
     */
    private static final ConfigurationService
        cfg = LibJitsi.getConfigurationService();

    /**
     * The property name of the number of seen frames to keep track of.
     */
    public static final String SEEN_FRAME_HISTORY_SIZE_PNAME
        = "org.jitsi.videobridge.SEEN_FRAME_HISTORY_SIZE";

    /**
     * The default number of seen frames to keep track of.
     */
    private static final int SEEN_FRAME_HISTORY_SIZE_DEFAULT = 120;

    /**
     * The number of seen frames to keep track of.
     */
    private static final int SEEN_FRAME_HISTORY_SIZE =
        cfg != null ? cfg.getInt(SEEN_FRAME_HISTORY_SIZE_PNAME,
            SEEN_FRAME_HISTORY_SIZE_DEFAULT) : SEEN_FRAME_HISTORY_SIZE_DEFAULT;

    /**
     *
     */
    public static final String ENABLE_VP8_PICID_REWRITING_PNAME
        = "org.jitsi.videobridge.ENABLE_VP8_PICID_REWRITING";

    /**
     * A boolean that indicates whether or not to activate VP8 picture ID
     * rewriting.
     */
    private static final boolean ENABLE_VP8_PICID_REWRITING =
        cfg != null && cfg.getBoolean(ENABLE_VP8_PICID_REWRITING_PNAME, false);

    /**
     * The running index for the temporal base layer frames, i.e., the frames
     * with TID set to 0. Introduced for VP8 PictureID rewriting. The initial
     * value is chosen so that it matches the TL0PICIDX of the black VP8 key
     * frames injected by the lipsync hack; so the code assumes a packet of TID
     * 0 has already been sent.
     */
    private int tl0PicIdx = 0;

    /**
     * The available subjective quality indexes that this RTP stream offers.
     * NOTE: this refers to the available qualities for the current stream
     * only.  a non-adaptive stream would only have 1, an adaptive stream
     * would have multiple for each of its layers.
     */
    private int[] availableQualityIndices;

    /**
     * A boolean that indicates whether or not the current TL0 is adaptive
     * (meaning it has multiple quality layers within the same rtp stream) or
     * not.
     */
    private boolean isAdaptive;

    /**
     * The sequence number offset that this bitstream started. The initial
     * value -1 is an indication to use the first accepted packet seqnum
     * as the offset.
     */
    private int seqNumOff = -1;

    /**
     * The timestamp offset that this bitstream started. The initial
     * value -1 is an indication to use the first accepted packet timestamp
     * as the offset.
     */
    private long tsOff = -1;

    /**
     * The running index offset of the frames that are sent out. Introduced
     * for VP8 PictureID rewriting. The initial value is chosen so that it
     * matches the pictureID of the black VP8 key frames injected by the
     * lipsync hack, so the code assumes a packet with picture ID 1 has
     * already been sent.
     */
    private int pidOff = 1;

    /**
     * The running index delta to apply to the frames that are sent out.
     * Introduced for VP8 PictureID rewriting. The initial value is chosen
     * so that it matches the pictureID of the black VP8 key frames injected
     * by the lipsync hack, so the code assumes a packet with picture ID 1
     * has already been sent.
     */
    private int pidDelta = -1;

    /**
     * The SSRC of the TL0 of the RTP stream that is currently being
     * forwarded. This is useful for simulcast and RTCP SR rewriting.
     *
     * The special value -1 indicates that we're not accepting (and hence
     * not forwarding) anything.
     */
    private long tl0Ssrc = -1;

    /**
     * The subjective quality index for of the TL0 of this instance.
     *
     * The special value -1 indicates that we're not accepting (and hence
     * not forwarding) anything.
     */
    private int tl0Idx = -1;

    /**
     * The target subjective quality index for this instance. This instance
     * switches between the available RTP streams and sub-encodings until it
     * reaches this target. -1 effectively means that the stream is
     * suspended.
     */
    private int targetIdx = -1;

    /**
     * The optimal subjective quality index for this instance.
     */
    private int optimalIdx = -1;

    /**
     * The current subjective quality index for this instance. If this is
     * different than the target, then a switch is pending.
     *
     * When SVC is enabled, currentIdx >= tl0Idx, otherwise
     * currentIdx == tl0Idx.
     */
    private int currentIdx = -1;

    /**
     * The number of transmitted bytes.
     */
    private long transmittedBytes = 0;

    /**
     * The number of transmitted packets.
     */
    private long transmittedPackets = 0;

    /**
     * The most recent key frame that we've sent out. Anything that's
     * accepted by this controller needs to be newer than this.
     */
    private SeenFrame mostRecentSentKeyFrame;

    /**
     * The max (biggest timestamp) frame that we've sent out.
     */
    private SeenFrame mostRecentSentFrame;

    /**
     * The {@link SeenFrameAllocator} for this {@link BitstreamController}.
     */
    private final SeenFrameAllocator seenFrameAllocator;
        //= new SeenFrameAllocator();

    public BitstreamController()
    {
        this.seenFrameAllocator = new SeenFrameAllocator();
    }

    /**
     * Only to be used for testing
     * @param seenFrameAllocator
     */
    public BitstreamController(SeenFrameAllocator seenFrameAllocator)
    {
        this.seenFrameAllocator = seenFrameAllocator;
    }

    /**
     * At 60fps, this holds 2 seconds worth of frames.
     * At 30fps, this holds 4 seconds worth of frames.
     */
    private final Map<Long, SeenFrame> seenFrames
        = Collections.synchronizedMap(
        new LRUCache<Long, SeenFrame>(SEEN_FRAME_HISTORY_SIZE)
        {
            /**
             * {@inheritDoc}
             */
            protected boolean removeEldestEntry(Map.Entry<Long, SeenFrame> eldest)
            {
                boolean removeEldestEntry = super.removeEldestEntry(eldest);

                if (removeEldestEntry)
                {
                    seenFrameAllocator.returnSeenFrame(eldest.getValue());
                }

                return removeEldestEntry;
            }
        });

    void suspend()
    {
        setTL0Idx(-1, null);
    }

    /**
     * Set the tl0 index this {@link BitstreamController} will be tracking and
     * increment/decrement the amount of receivers on the new/old
     * {@link RTPEncodingDesc}'s this {@link BitstreamController} will be/was
     * forwarding
     *
     * @param newTL0Idx the new tl0 index this {@link BitstreamController}
     * will be tracking
     * @param currSourceRtpEncodings the current set of all RTP encodings
     * for the track to which this {@link BitstreamController} tracks
     */
    void setTL0Idx(int newTL0Idx, RTPEncodingDesc[] currSourceRtpEncodings)
    {
        if (this.tl0Idx == newTL0Idx)
        {
            return;
        }

        if (logger.isDebugEnabled())
        {
            logger.debug("tl0_changed,hash="
                + this.hashCode()
                + " old_tl0=" + this.tl0Idx
                + ",new_tl0=" + newTL0Idx);
        }

        this.seenFrames.clear();

        this.mostRecentSentKeyFrame = null;
        if (mostRecentSentFrame != null)
        {
            this.tsOff = getMaxTs();
            this.seqNumOff = getMaxSeqNum();
            this.pidOff = getMaxPictureID();
            this.mostRecentSentFrame = null;
        }

        this.pidDelta = -1;

        int oldTL0Idx = this.tl0Idx;
        this.tl0Idx = newTL0Idx;

        // a stream always starts suspended (and resumes with a key frame).
        this.currentIdx = -1;

        if (!ArrayUtils.isNullOrEmpty(currSourceRtpEncodings))
        {
            if (oldTL0Idx > -1)
            {
                currSourceRtpEncodings[currSourceRtpEncodings[oldTL0Idx].getBaseLayer().getIndex()].decrReceivers();
            }

            if (newTL0Idx > -1)
            {
                RTPEncodingDesc d = currSourceRtpEncodings[newTL0Idx];
                RTPEncodingDesc b = d.getBaseLayer();
                currSourceRtpEncodings[currSourceRtpEncodings[newTL0Idx].getBaseLayer().getIndex()].incrReceivers();
            }
        }
        if (ArrayUtils.isNullOrEmpty(currSourceRtpEncodings) || tl0Idx < 0)
        {
            this.availableQualityIndices = null;
            this.tl0Ssrc = -1;
            this.isAdaptive = false;
        }
        else
        {
            tl0Idx = currSourceRtpEncodings[tl0Idx].getBaseLayer().getIndex();
            tl0Ssrc = currSourceRtpEncodings[tl0Idx].getPrimarySSRC();

            // find the available qualities in this bitstream.

            // TODO optimize if we have a single quality
            List<Integer> availableQualities = new ArrayList<>();
            for (int i = 0; i < currSourceRtpEncodings.length; i++)
            {
                if (currSourceRtpEncodings[i].requires(tl0Idx))
                {
                    availableQualities.add(i);
                }
            }

            availableQualityIndices = new int[availableQualities.size()];
            Iterator<Integer> iterator = availableQualities.iterator();
            for (int i = 0; i < availableQualityIndices.length; i++)
            {
                availableQualityIndices[i] = iterator.next();
            }

            this.isAdaptive = availableQualityIndices.length > 1;
        }
    }

    /**
     * Given a list of available quality indices for the current stream
     * and a target index, find the highest available quality index <= targetIdx.
     * Note that targetIdx may be pointing at a different stream, i.e.
     * availableQualityIndices may be {7, 8, 9} and targetIdx could be 3.
     * We won't change the stream we're sending, but will find the max index
     * that is both in the current stream and is less than the targetIdx,
     * if it exists
     * @param availableQualityIndices the available quality indices
     * @param targetIdx the target index
     * @return the highest quality index <= targetIdx
     */
    private static int findMaximumQualityIndex(int[] availableQualityIndices, int targetIdx)
    {
        int maxIndex = -1;
        if (availableQualityIndices != null && availableQualityIndices.length > 0)
        {
            // At the very least we will send the lowest quality layer of
            // the current stream.  If there happens to be an additional,
            // higher index of this stream that is also <= targetIdx, we will
            // upgrade to that
            maxIndex = availableQualityIndices[0];
            for (int i = 1; i < availableQualityIndices.length; ++i)
            {
                if (availableQualityIndices[i] <= targetIdx)
                {
                    maxIndex = availableQualityIndices[i];
                }
                else
                {
                    break;
                }
            }

        }
        return maxIndex;
    }

    /**
     * Defines an RTP packet filter that controls which packets to be written
     * into some arbitrary target/receiver that owns this
     * {@link BitstreamController}.
     *
     * @param sourceFrameDesc the {@link FrameDesc} that the RTP packet belongs
     * to.
     * @param pkt the packet for which to decide to accept
     * @param sourceStream the MediaStream to which the incoming packet belongs
     * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt> to be
     * written into the arbitrary target/receiver that owns this
     * {@link BitstreamController} ; otherwise, <tt>false</tt>
     */
    public boolean accept(
        FrameDesc sourceFrameDesc, RawPacket pkt, MediaStream sourceStream)
    {
        // BitstreamController should never be given a frame that doesn't belong
        // to its configured tl0idx
        assert sourceFrameDesc.getRTPEncoding().getBaseLayer().getIndex() == tl0Idx;

        if (isAdaptive && !sourceFrameDesc.firstSequenceNumberKnown())
        {
            return false;
        }

        long srcTs = sourceFrameDesc.getTimestamp();
        SeenFrame destFrame = seenFrames.get(srcTs);

        if (destFrame == null)
        {
            // An unseen frame has arrived. We forward it iff all of the
            // following conditions hold:
            //
            // 1. it's a dependency of the currentIdx (obviously).
            // 2. it's newer than max frame (we can't modify the distances of
            // past frames).
            // 2. we know the boundaries of the max frame OR if the max frame is
            // *not* a TL0 (this allows for non-TL0 to be corrupt).
            //
            // Given the above conditions, we might decide to drop a TL0 frame.
            // This can happen when the max frame is a TL0 and we don't know its
            // boundaries. Then the stream will be broken an we should ask for a
            // key frame.
            {
                int newCurrentIdx = findMaximumQualityIndex(availableQualityIndices, targetIdx);
                if (newCurrentIdx != this.currentIdx && logger.isDebugEnabled())
                {
                    logger.debug("current_idx_changed,hash="
                        + this.hashCode()
                        + " old_idx=" + this.currentIdx
                        + ",new_idx=" + newCurrentIdx);
                }
                this.currentIdx = newCurrentIdx;
            }

            boolean isNewerThanMostRecentFrame = !haveSentFrame()
                || RTPUtils.isNewerTimestampThan(srcTs, mostRecentSentFrame.srcTs);

            boolean isNewerThanMostRecentKeyFrame = !haveSentKeyFrame()
                || RTPUtils.isNewerTimestampThan(srcTs, mostRecentSentKeyFrame.srcTs);

            if (!isSuspended()
                // we haven't seen anything yet and this is an independent
                // frame.
                && (!haveSentFrame() && sourceFrameDesc.isIndependent()
                // frames from non-adaptive streams need to be newer than
                // the most recent independent frame
                || (haveSentFrame() && !isAdaptive
                && isNewerThanMostRecentKeyFrame)
                // frames from adaptive streams need to be newer than the
                // max
                || (haveSentFrame() && isAdaptive && isNewerThanMostRecentFrame)))
            {
                // the stream is not suspended and we're not dealing with a
                // late frame or the stream is not adaptive.

                RTPEncodingDesc sourceEncodings[] =
                    sourceFrameDesc.getTrackRTPEncodings();

                // TODO ask for independent frame if we're skipping a TL0.

                int sourceIdx = sourceFrameDesc.getRTPEncoding().getIndex();
                if (sourceEncodings[this.currentIdx].requires(sourceIdx))
                {
                    // the quality of the frame is a dependency of the
                    // forwarded quality and the max frame is effectively
                    // complete.
                    SeqNumTranslation seqNumTranslation;
                    if (!haveSentFrame() || isAdaptive)
                    {
                        int maxSeqNum = getMaxSeqNum();
                        if (maxSeqNum > -1)
                        {
                            int seqNumDelta = (maxSeqNum + 1
                                - sourceFrameDesc.getStart()) & 0xFFFF;

                            seqNumTranslation
                                = new SeqNumTranslation(seqNumDelta);
                        }
                        else
                        {
                            seqNumTranslation = null;
                        }
                    }
                    else
                    {
                        // If the current bitstream only offers a single
                        // quality, then we're not filtering anything thus
                        // the sequence number distances between the frames
                        // are fixed so we can reuse the sequence number
                        // translation from the mostRecentSentFrame.
                        seqNumTranslation = mostRecentSentFrame.seqNumTranslation;
                    }

                    TimestampTranslation tsTranslation;
                    if (!haveSentFrame())
                    {
                        if (tsOff > -1)
                        {
                            long tsDelta = (tsOff + 3000 - srcTs) & 0xFFFFFFFFL;

                            tsTranslation = new TimestampTranslation(tsDelta);
                        }
                        else
                        {
                            tsTranslation = null;
                        }
                    }
                    else
                    {
                        // The timestamp delta doesn't change for a bitstream,
                        // so, if we've sent out a frame already, reuse its
                        // timestamp translation.
                        tsTranslation = mostRecentSentFrame.tsTranslation;
                    }

                    int dstPictureID = -1, dstTL0PICIDX = -1;
                    if (ENABLE_VP8_PICID_REWRITING)
                    {
                        byte vp8PT =
                            sourceStream.getDynamicRTPPayloadType(Constants.VP8);

                        boolean isVP8 = vp8PT == pkt.getPayloadType();

                        if (isVP8)
                        {
                            dstPictureID = calculatePictureID(pkt, sourceStream);
                        }

                        int tid =
                            ((MediaStreamImpl)sourceStream).getTemporalID(pkt);

                        if (tid > -1)
                        {
                            if (tid == 0)
                            {
                                tl0PicIdx++;
                            }

                            dstTL0PICIDX = tl0PicIdx;
                        }
                    }

                    destFrame = seenFrameAllocator.getOrCreate();
                    destFrame.reset(srcTs, seqNumTranslation, tsTranslation,
                        dstPictureID, dstTL0PICIDX, sourceFrameDesc.isIndependent(),
                        isAdaptive);
                    seenFrames.put(srcTs, destFrame);

                    if (isNewerThanMostRecentFrame)
                    {
                        mostRecentSentFrame = destFrame;
                    }

                    if (isNewerThanMostRecentKeyFrame
                        && sourceFrameDesc.isIndependent())
                    {
                        mostRecentSentKeyFrame = destFrame;
                    }
                }
                else
                {
                    // The quality we're forwarding does not depend on this
                    // frame
                    //PR-NOTE(brian): is there any reason not to return
                    // false after doing this?  why call accept on the frame?
                    destFrame = seenFrameAllocator.getOrCreate();
                    destFrame.reset(srcTs);
                    seenFrames.put(srcTs, destFrame);
                }
            }
            else
            {
                // Either we are suspended, or we haven't sent anything
                // yet but this isn't a keyframe, or this is an old frame
                // we can't use
                // TODO ask for independent frame if we're filtering a TL0.
                //PR-NOTE(brian): is there any reason not to return
                // false after doing this?  why call accept on the frame?
                destFrame = seenFrameAllocator.getOrCreate();
                destFrame.reset(srcTs);
                seenFrames.put(srcTs, destFrame);
            }
        }

        boolean isMostRecentFrame = destFrame == mostRecentSentFrame;
        boolean accept = destFrame.accept(sourceFrameDesc, pkt, isMostRecentFrame);

        if (accept)
        {
            transmittedPackets++;
            transmittedBytes += pkt.getLength();
        }

        return accept;
    }

    /**
     * Calculates the destination picture ID of an incoming VP8 frame.
     *
     * @param pkt the packet from which to calculate the vp8 picture id
     * @param sourceStream the MediaStream to which the incoming packet belongs
     * @return the destination picture ID of the VP8 frame that is specified
     * in the parameter.
     * FIXME(brian): feels like this should be in some vp8-specific class
     */
    private int calculatePictureID(RawPacket pkt, MediaStream sourceStream)
    {
        REDBlock redBlock = sourceStream.getPrimaryREDBlock(pkt);

        int srcPID = DePacketizer
            .VP8PayloadDescriptor.getPictureId(
                redBlock.getBuffer(),
                redBlock.getOffset());

        int dstPictureID;
        if (srcPID > -1)
        {
            if (pidDelta == -1)
            {
                pidDelta = (pidOff + 1 - srcPID) & 0x7FFF;
            }

            dstPictureID = (srcPID + pidDelta) & 0x7FFF;

            if (((dstPictureID - getMaxPictureID()) & 0x7FFF) > 200
                || ((dstPictureID - getMaxPictureID()) & 0x7FFF) > 0x7F00)
            {
                pidDelta = (getMaxPictureID() + 1 - srcPID) & 0x7FFF;
                dstPictureID = (srcPID + pidDelta) & 0x7FFF;
                logger.warn("A jump was detected in the picture IDs.");
            }
        }
        else
        {
            dstPictureID = -1;
        }

        return dstPictureID;
    }

    /**
     * Gets the optimal subjective quality index for this instance.
     */
    int getOptimalIndex()
    {
        return optimalIdx;
    }

    /**
     * Gets the current subjective quality index for this instance.
     */
    int getCurrentIndex()
    {
        return currentIdx;
    }

    /**
     * Gets the target subjective quality index for this instance.
     */
    int getTargetIndex()
    {
        return targetIdx;
    }

    /**
     * Sets the target subjective quality index for this instance.
     *
     * @param newTargetIdx the new target subjective quality index for this
     * instance.
     */
    void setTargetIndex(int newTargetIdx)
    {
        this.targetIdx = newTargetIdx;
    }

    /**
     * Sets the optimal subjective quality index for this instance.
     *
     * @param newOptimalIdx the new optimal subjective quality index for this
     * instance.
     */
    void setOptimalIndex(int newOptimalIdx)
    {
        this.optimalIdx = newOptimalIdx;
    }

    /**
     * Translates accepted packets and drops packets that are not accepted (by
     * this instance).
     *
     * @param pktIn the packet to translate or drop.
     *
     * @return the translated packet (along with any piggy backed packets), if
     * the packet is accepted, null otherwise.
     */
    RawPacket[] rtpTransform(RawPacket pktIn, MediaStream sourceStream)
    {
        if (pktIn.getSSRCAsLong() != tl0Ssrc)
        {
            return null;
        }

        SeenFrame destFrame = seenFrames.get(pktIn.getTimestamp());
        if (destFrame == null)
        {
            return null;
        }

        return destFrame.rtpTransform(pktIn, sourceStream, ENABLE_VP8_PICID_REWRITING);
    }

    /**
     * Updates the timestamp, transmitted bytes and transmitted packets in the
     * RTCP SR packets.
     */
    void rtcpTransform(ByteArrayBuffer baf)
    {
        // Make a local reference to this.maxSentFrame to prevent
        // it from being set to null or a different frame while we're
        // using it.  maxSentFrame.tsTranslation is conceptually final for
        // this given frame, so we don't need any extra protection
        SeenFrame localMaxSentFrameCopy = this.mostRecentSentFrame;
        // Rewrite timestamp.
        if (localMaxSentFrameCopy != null && localMaxSentFrameCopy.tsTranslation != null)
        {
            long srcTs = RTCPSenderInfoUtils.getTimestamp(baf);
            long dstTs = localMaxSentFrameCopy.tsTranslation.apply(srcTs);

            if (srcTs != dstTs)
            {
                RTCPSenderInfoUtils.setTimestamp(baf, (int) dstTs);
            }
        }

        // Rewrite packet/octet count.
        RTCPSenderInfoUtils.setOctetCount(baf, (int) transmittedBytes);
        RTCPSenderInfoUtils.setPacketCount(baf, (int) transmittedPackets);
    }

    /**
     * Gets the maximum sequence number that this instance has accepted.
     */
    private int getMaxSeqNum()
    {
        return haveSentFrame() ? mostRecentSentFrame.getMaxSeqNum() : seqNumOff;
    }

    /**
     * Gets the maximum VP8 picture ID that this instance has accepted.
     */
    private int getMaxPictureID()
    {
        return haveSentFrame() ? mostRecentSentFrame.dstPictureID : pidOff;
    }

    /**
     * Gets the maximum timestamp that this instance has accepted.
     */
    private long getMaxTs()
    {
        return haveSentFrame() ? mostRecentSentFrame.getTs() : tsOff;
    }

    /**
     * Gets the SSRC of the TL0 of the RTP stream that is currently being
     * forwarded.
     *
     * @return the SSRC of the TL0 of the RTP stream that is currently being
     * forwarded.
     */
    long getTL0SSRC()
    {
        return tl0Ssrc;
    }

    /**
     * Return whether or not this BitstreamController has sent a frame
     * from the current bitstream yet
     *
     * @return true if it has sent a frame from this bitstream, false
     * otherwise
     */
    private boolean haveSentFrame()
    {
        return mostRecentSentFrame != null;
    }

    /**
     * Return whether or not this BitstreamController has sent a key frame
     * from the current bitstream yet
     *
     * @return true if it has sent a key frame from this bitstream, false
     * otherwise
     */
    private boolean haveSentKeyFrame()
    {
        return mostRecentSentKeyFrame != null;
    }

    /**
     * Returns whether or not this bitstream is suspended
     *
     * @return true if this bitstream is suspended, false otherwise
     */
    private boolean isSuspended()
    {
        return currentIdx == -1;
    }

    /**
     * A very simple {@link SeenFrame} allocator. NOT multi-thread safe.
     */
    class SeenFrameAllocator
    {
        /**
         * The pool of available {@link SeenFrame}.
         */
        private Queue<SeenFrame> pool = new LinkedList<>();

        /**
         * Gets a {@link SeenFrame} from the pool of available seen frames,
         * or creates a new one, if none is available.
         *
         * @return a {@link SeenFrame} from the pool of available seen
         * frames, or a new one, if none is available.
         */
        SeenFrame getOrCreate()
        {
            return pool.isEmpty() ? new SeenFrame() : pool.remove();
        }

        /**
         * Returns a {@link SeenFrame} to this allocator.
         *
         * @param value the {@link SeenFrame} to return.
         */
        void returnSeenFrame(SeenFrame value)
        {
            pool.add(value);
        }
    }
}