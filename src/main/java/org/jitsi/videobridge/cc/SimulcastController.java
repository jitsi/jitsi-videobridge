/*
 * Copyright @ 2015 Atlassian Pty Ltd
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

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.codec.*;
import org.jitsi.impl.neomedia.codec.video.vp8.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.util.*;
import org.jitsi.util.function.*;

import java.lang.ref.*;
import java.util.*;

/**
 * Filters the packets coming from a specific {@link MediaStreamTrackDesc}
 * based on the currently forwarded subjective quality index. It's also taking
 * care of upscaling and downscaling. As a {@link PacketTransformer}, it
 * rewrites the forwarded packets so that the gaps as a result of the drops are
 * hidden.
 *
 * @author George Politis
 */
public class SimulcastController
    implements AutoCloseable
{
    /**
     * The property name of the number of seen frames to keep track of.
     */
    public static final String SEEN_FRAME_HISTORY_SIZE_PNAME
        = "org.jitsi.videobridge.SEEN_FRAME_HISTORY_SIZE";

    /**
     *
     */
    public static final String ENABLE_VP8_PICID_REWRITING_PNAME
        = "org.jitsi.videobridge.ENABLE_VP8_PICID_REWRITING";

    /**
     * The ConfigurationService to get config values from.
     */
    private static final ConfigurationService
        cfg = LibJitsi.getConfigurationService();

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
     * A boolean that indicates whether or not to activate VP8 picture ID
     * rewriting.
     */
    private static final boolean ENABLE_VP8_PICID_REWRITING =
        cfg != null && cfg.getBoolean(ENABLE_VP8_PICID_REWRITING_PNAME, false);

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger = Logger.getLogger(SimulcastController.class);

    /**
     * A {@link WeakReference} to the {@link MediaStreamTrackDesc} that feeds
     * this instance with RTP/RTCP packets.
     */
    private final WeakReference<MediaStreamTrackDesc> weakSource;

    /**
     * The {@link BitrateController} that owns this instance.
     */
    private final BitrateController bitrateController;

    /**
     * The SSRC to protect when probing for bandwidth and for RTP/RTCP packet
     * rewritting.
     */
    private final long targetSSRC;

    /**
     * The running index for the temporal base layer frames, i.e., the frames
     * with TID set to 0. Introduced for VP8 PictureID rewriting. The initial
     * value is chosen so that it matches the TL0PICIDX of the black VP8 key
     * frames injected by the lipsync hack; so the code assumes a packet of TID
     * 0 has already been sent.
     */
    private int tl0PicIdx = 0;

    /**
     * The {@link BitstreamController} for the currently forwarded RTP stream.
     */
    private final BitstreamController bitstreamController;

    /**
     * Ctor.
     *
     * @param bitrateController the {@link BitrateController} that owns this
     * instance.
     * @param source the source {@link MediaStreamTrackDesc}
     */
    SimulcastController(
        BitrateController bitrateController, MediaStreamTrackDesc source)
    {
        this.bitrateController = bitrateController;
        weakSource = new WeakReference<>(source);

        RTPEncodingDesc[] rtpEncodings = source.getRTPEncodings();
        if (ArrayUtils.isNullOrEmpty(rtpEncodings))
        {
            targetSSRC = -1;
        }
        else
        {
            targetSSRC = rtpEncodings[0].getPrimarySSRC();
        }

        bitstreamController = new BitstreamController();
    }

    /**
     * Gets the SSRC to protect with RTX, in case the padding budget is
     * positive.
     *
     * @return the SSRC to protect with RTX, in case the padding budget is
     * positive.
     */
    long getTargetSSRC()
    {
        return targetSSRC;
    }

    /**
     * Defines a packet filter that controls which packets to be written into
     * some arbitrary target/receiver that owns this {@link SimulcastController}.
     *
     * @param pkt the packet to decide whether or not to accept
     * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt> to be
     * written into the arbitrary target/receiver that owns this
     * {@link SimulcastController} ; otherwise, <tt>false</tt>
     */
    public boolean accept(RawPacket pkt)
    {
        if (pkt.getBuffer() == null || pkt.getOffset() < 0 ||
            pkt.getLength() < RawPacket.FIXED_HEADER_SIZE ||
            pkt.getBuffer().length < pkt.getOffset() + pkt.getLength())
        {
            return false;
        }
        int targetIndex = bitstreamController.getTargetIndex()
            , currentIndex = bitstreamController.getCurrentIndex();

        // if the target is set to suspend and it's not already suspended, then
        // suspend the stream.
        if (targetIndex < 0 && currentIndex > -1)
        {
            bitstreamController.setTL0Idx(-1);
            return false;
        }

        MediaStreamTrackDesc sourceTrack = weakSource.get();
        assert sourceTrack != null;
        FrameDesc sourceFrameDesc =
            sourceTrack.findFrameDesc(pkt.getSSRCAsLong(), pkt.getTimestamp());

        if (sourceFrameDesc == null)
        {
            return false;
        }

        RTPEncodingDesc sourceEncodings[] = sourceTrack.getRTPEncodings();

        // If we're getting packets here => there must be at least 1 encoding.
        if (ArrayUtils.isNullOrEmpty(sourceEncodings))
        {
            return false;
        }

        // if the TL0 of the forwarded stream is suspended, we MUST downscale.
        boolean currentTL0IsActive = false;
        int currentTL0Idx = currentIndex;
        if (currentTL0Idx > -1)
        {
            currentTL0Idx
                = sourceEncodings[currentTL0Idx].getBaseLayer().getIndex();

            currentTL0IsActive = sourceEncodings[currentTL0Idx].isActive(true);
        }

        int targetTL0Idx = targetIndex;
        if (targetTL0Idx > -1)
        {
            targetTL0Idx
                = sourceEncodings[targetTL0Idx].getBaseLayer().getIndex();
        }

        if (currentTL0Idx == targetTL0Idx
            && (currentTL0IsActive || targetTL0Idx < 0 /* => currentIdx < 0 */))
        {
            // An intra-codec/simulcast switch is NOT pending.
            long sourceSSRC = sourceFrameDesc.getRTPEncoding().getPrimarySSRC();
            boolean accept = sourceSSRC == bitstreamController.getTL0SSRC();

            if (!accept)
            {
                return false;
            }

            // Give the bitstream filter a chance to drop the packet.
            return bitstreamController.accept(sourceFrameDesc, pkt);
        }

        // An intra-codec/simulcast switch pending.

        boolean sourceTL0IsActive = false;
        int sourceTL0Idx = sourceFrameDesc.getRTPEncoding().getIndex();
        if (sourceTL0Idx > -1)
        {
            sourceTL0Idx
                = sourceEncodings[sourceTL0Idx].getBaseLayer().getIndex();

            sourceTL0IsActive = sourceEncodings[sourceTL0Idx].isActive(true);
        }

        if (!sourceFrameDesc.isIndependent()
            || !sourceTL0IsActive // TODO this condition needs review
            || sourceTL0Idx == currentTL0Idx)
        {
            // An intra-codec switch requires a key frame.

            long sourceSSRC = sourceFrameDesc.getRTPEncoding().getPrimarySSRC();
            boolean accept = sourceSSRC == bitstreamController.getTL0SSRC();

            if (!accept)
            {
                return false;
            }

            // Give the bitstream filter a chance to drop the packet.
            return bitstreamController.accept(sourceFrameDesc, pkt);
        }

        if ((targetTL0Idx <= sourceTL0Idx && sourceTL0Idx < currentTL0Idx)
            || (currentTL0Idx < sourceTL0Idx && sourceTL0Idx <= targetTL0Idx)
            || (!currentTL0IsActive && sourceTL0Idx <= targetTL0Idx))
        {
            bitstreamController.setTL0Idx(sourceTL0Idx);

            // Give the bitstream filter a chance to drop the packet.
            return bitstreamController.accept(sourceFrameDesc, pkt);
        }
        else
        {
            return false;
        }

    }

    /**
     * Update the target subjective quality index for this instance.
     *
     * @param newTargetIdx new target subjective quality index.
     */
    void setTargetIndex(int newTargetIdx)
    {
        bitstreamController.setTargetIndex(newTargetIdx);

        if (newTargetIdx < 0)
        {
            return;
        }

        // check whether it makes sense to send an FIR or not.
        MediaStreamTrackDesc sourceTrack = weakSource.get();
        if (sourceTrack == null)
        {
            return;
        }

        RTPEncodingDesc[] sourceEncodings = sourceTrack.getRTPEncodings();

        int currentTL0Idx = bitstreamController.getCurrentIndex();
        if (currentTL0Idx > -1)
        {
            currentTL0Idx
                = sourceEncodings[currentTL0Idx].getBaseLayer().getIndex();
        }

        int targetTL0Idx
            = sourceEncodings[newTargetIdx].getBaseLayer().getIndex();

        // Make sure that something is streaming so that a FIR makes sense.

        boolean sendFIR;
        if (sourceEncodings[0].isActive(true))
        {
            // Something lower than the current must be streaming, so we're able
            // to make a switch, so ask for a key frame.
            sendFIR = targetTL0Idx < currentTL0Idx;
            if (!sendFIR && targetTL0Idx > currentTL0Idx)
            {
                // otherwise, check if anything higher is streaming.
                for (int i = currentTL0Idx + 1; i < targetTL0Idx + 1; i++)
                {
                    RTPEncodingDesc tl0 = sourceEncodings[i].getBaseLayer();
                    if (tl0.isActive(true) && tl0.getIndex() > currentTL0Idx)
                    {
                        sendFIR = true;
                        break;
                    }
                }
            }
        }
        else
        {
            sendFIR = false;
        }

        MediaStream sourceStream
            = sourceTrack.getMediaStreamTrackReceiver().getStream();
        if (sendFIR && sourceStream != null)
        {

            if (logger.isTraceEnabled())
            {
                logger.trace("send_fir,stream="
                    + sourceStream.hashCode()
                    + ",reason=target_changed"
                    + ",current_tl0=" + currentTL0Idx
                    + ",target_tl0=" + targetTL0Idx);
            }

            ((RTPTranslatorImpl) sourceStream.getRTPTranslator())
                .getRtcpFeedbackMessageSender().sendFIR(
                (int) targetSSRC);
        }
    }

    /**
     * Update the optimal subjective quality index for this instance.
     *
     * @param optimalIndex new optimal subjective quality index.
     */
    void setOptimalIndex(int optimalIndex)
    {
        bitstreamController.setOptimalIndex(optimalIndex);
    }

    /**
     * Transforms the RTP packet specified in the {@link RawPacket} that is
     * passed as an argument for the purposes of simulcast.
     *
     * @param pktIn the {@link RawPacket} to be transformed.
     * @return the transformed {@link RawPacket} or null if the packet needs
     * to be dropped.
     */
    RawPacket[] rtpTransform(RawPacket pktIn)
    {
        if (!RTPPacketPredicate.INSTANCE.test(pktIn))
        {
            return new RawPacket[] { pktIn };
        }

        RawPacket[] pktsOut = bitstreamController.rtpTransform(pktIn);

        if (!ArrayUtils.isNullOrEmpty(pktsOut)
            && pktIn.getSSRCAsLong() != targetSSRC)
        {
            // Rewrite the SSRC of the output RTP stream.
            for (RawPacket pktOut : pktsOut)
            {
                if (pktOut != null)
                {
                    pktOut.setSSRC((int) targetSSRC);
                }
            }
        }

        return pktsOut;
    }

    /**
     * Transform an RTCP {@link RawPacket} for the purposes of simulcast.
     *
     * @param pktIn the {@link RawPacket} to be transformed.
     * @return the transformed RTCP {@link RawPacket}, or null if the packet
     * needs to be dropped.
     */
    RawPacket rtcpTransform(RawPacket pktIn)
    {
        if (!RTCPPacketPredicate.INSTANCE.test(pktIn))
        {
            return pktIn;
        }

        // Drop SRs from other streams.
        boolean removed = false;
        RTCPIterator it = new RTCPIterator(pktIn);
        while (it.hasNext())
        {
            ByteArrayBuffer baf = it.next();
            switch (RTCPHeaderUtils.getPacketType(baf))
            {
            case RTCPPacket.SDES:
                if (removed)
                {
                    it.remove();
                }
                break;
            case RTCPPacket.SR:
                if (RawPacket.getRTCPSSRC(baf) != bitstreamController.getTL0SSRC())
                {
                    // SRs from other streams get axed.
                    removed = true;
                    it.remove();
                }
                else
                {
                    // Rewrite timestamp and transmission.
                    bitstreamController.rtcpTransform(baf);

                    // Rewrite senderSSRC
                    RTCPHeaderUtils.setSenderSSRC(baf, (int) targetSSRC);
                }
                break;
                case RTCPPacket.BYE:
                    // TODO rewrite SSRC.
                    break;
            }
        }

        return pktIn.getLength() > 0 ? pktIn : null;
    }

    /**
     * Gets the target subjective quality index for this instance.
     */
    int getTargetIndex()
    {
        return bitstreamController.getTargetIndex();
    }

    /**
     * Gets the optimal subjective quality index for this instance.
     */
    int getOptimalIndex()
    {
        return bitstreamController.getOptimalIndex();
    }

    /**
     * Gets the {@link MediaStreamTrackDesc} that feeds this instance with
     * RTP/RTCP packets.
     */
    MediaStreamTrackDesc getSource()
    {
        return weakSource.get();
    }

    /**
     * Gets the current subjective quality index for this instance.
     */
    int getCurrentIndex()
    {
        return bitstreamController.getCurrentIndex();
    }

    @Override
    public void close()
        throws Exception
    {
        bitstreamController.setTL0Idx(-1);
    }

    class BitstreamController
    {
        /**
         * The available subjective quality indexes that this RTP stream offers.
         */
        private int[] availableQualityIndices;

        /**
         * A boolean that indicates whether or not the current TL0 is adaptive
         * or not.
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
        private long tl0SSRC = -1;

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
        private final SeenFrameAllocator seenFrameAllocator
            = new SeenFrameAllocator();

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

        /**
         *
         * @param newTL0Idx
         */
        void setTL0Idx(int newTL0Idx)
        {
            if (this.tl0Idx == newTL0Idx)
            {
                return;
            }

            if (logger.isDebugEnabled())
            {
                logger.debug("tl0_changed,hash="
                    + SimulcastController.this.hashCode()
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

            MediaStreamTrackDesc source = weakSource.get();
            assert source != null;
            RTPEncodingDesc[] rtpEncodings = source.getRTPEncodings();
            if (!ArrayUtils.isNullOrEmpty(rtpEncodings))
            {
                if (oldTL0Idx > -1)
                {
                    rtpEncodings[rtpEncodings[oldTL0Idx].getBaseLayer().getIndex()].decrReceivers();
                }

                if (newTL0Idx > -1)
                {
                    rtpEncodings[rtpEncodings[newTL0Idx].getBaseLayer().getIndex()].incrReceivers();
                }
            }
            if (ArrayUtils.isNullOrEmpty(rtpEncodings) || tl0Idx < 0)
            {
                this.availableQualityIndices = null;
                this.tl0SSRC = -1;
                this.isAdaptive = false;
            }
            else
            {
                tl0Idx = rtpEncodings[tl0Idx].getBaseLayer().getIndex();
                tl0SSRC = rtpEncodings[tl0Idx].getPrimarySSRC();

                // find the available qualities in this bitstream.

                // TODO optimize if we have a single quality
                List<Integer> availableQualities = new ArrayList<>();
                for (int i = 0; i < rtpEncodings.length; i++)
                {
                    if (rtpEncodings[i].requires(tl0Idx))
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
         * Defines an RTP packet filter that controls which packets to be written
         * into some arbitrary target/receiver that owns this
         * {@link BitstreamController}.
         *
         * @param sourceFrameDesc the {@link FrameDesc} that the RTP packet belongs
         * to.
         * @param pkt the packet for which to decide to accept
         * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt> to be
         * written into the arbitrary target/receiver that owns this
         * {@link BitstreamController} ; otherwise, <tt>false</tt>
         */
        public boolean accept(
            FrameDesc sourceFrameDesc, RawPacket pkt)
        {
            if (isAdaptive && sourceFrameDesc.getStart() == -1)
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

                int currentIdx = this.currentIdx;

                if (availableQualityIndices != null && availableQualityIndices.length != 0)
                {
                    currentIdx = availableQualityIndices[0];
                    for (int i = 1; i < availableQualityIndices.length; i++)
                    {
                        if (availableQualityIndices[i] <= targetIdx)
                        {
                            currentIdx = availableQualityIndices[i];
                        }
                        else
                        {
                            break;
                        }
                    }

                    if (currentIdx != this.currentIdx && logger.isDebugEnabled())
                    {
                        logger.debug("current_idx_changed,hash="
                            + SimulcastController.this.hashCode()
                            + " old_idx=" + this.currentIdx
                            + ",new_idx=" + currentIdx);
                    }

                    this.currentIdx = currentIdx;
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

                    RTPEncodingDesc sourceEncodings[] = sourceFrameDesc
                        .getRTPEncoding().getMediaStreamTrack().getRTPEncodings();

                    // TODO ask for independent frame if we're skipping a TL0.

                    int sourceIdx = sourceFrameDesc.getRTPEncoding().getIndex();
                    if (sourceEncodings[currentIdx].requires(sourceIdx))
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
                            byte vp8PT = bitrateController.getVideoChannel()
                                .getStream().getDynamicRTPPayloadType(Constants.VP8);

                            boolean isVP8 = vp8PT == pkt.getPayloadType();

                            if (isVP8)
                            {
                                dstPictureID = calculatePictureID(pkt);
                            }

                            int tid = ((MediaStreamImpl) bitrateController
                                .getVideoChannel().getStream())
                                .getTemporalID(pkt);

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
                            dstPictureID, dstTL0PICIDX, sourceFrameDesc.isIndependent());
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
                        destFrame = seenFrameAllocator.getOrCreate();
                        destFrame.reset(srcTs);
                        seenFrames.put(srcTs, destFrame);
                    }
                }
                else
                {
                    // TODO ask for independent frame if we're filtering a TL0.

                    destFrame = seenFrameAllocator.getOrCreate();
                    destFrame.reset(srcTs);
                    seenFrames.put(srcTs, destFrame);
                }
            }

            boolean accept = destFrame.accept(sourceFrameDesc, pkt);

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
         * @return the destination picture ID of the VP8 frame that is specified
         * in the parameter.
         * FIXME(brian): feels like this should be in some vp8-specific class
         */
        private int calculatePictureID(RawPacket pkt)
        {
            REDBlock redBlock = ((MediaStreamImpl)
                bitrateController.getVideoChannel()
                    .getStream()).getPrimaryREDBlock(pkt);

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
        RawPacket[] rtpTransform(RawPacket pktIn)
        {
            if (pktIn.getSSRCAsLong() != tl0SSRC)
            {
                return null;
            }

            SeenFrame destFrame = seenFrames.get(pktIn.getTimestamp());
            if (destFrame == null)
            {
                return null;
            }

            return destFrame.rtpTransform(pktIn);
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
            return tl0SSRC;
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
         * Utility class that holds information about seen frames.
         */
        class SeenFrame
        {
            /**
             * The sequence number translation to apply to accepted RTP packets.
             */
            private SeqNumTranslation seqNumTranslation;

            /**
             * The RTP timestamp translation to apply to accepted RTP/RTCP packets.
             */
            private TimestampTranslation tsTranslation;

            /**
             * A boolean that indicates whether or not the transform thread should
             * try to piggyback missed packets from the initial key frame.
             */
            private boolean maybeFixInitialIndependentFrame = true;

            /**
             * The source timestamp of this frame.
             */
            private long srcTs;

            /**
             * The VP8 picture id to set to outgoing packets that belong to this
             * frame.
             */
            private int dstPictureID = -1;

            /**
             * The VP8 TL0PICIDX to set to outgoing packets that belong to this
             * frame.
             */
            private int dstTL0PICIDX = -1;

            /**
             * The maximum source sequence number to accept. -1 means drop.
             */
            private int srcSeqNumLimit = -1;

            /**
             * The start source sequence number of this frame.
             */
            private int srcSeqNumStart = -1;

            /**
             * Defines an RTP packet filter that controls which packets to be
             * written into some arbitrary target/receiver that owns this
             * {@link SeenFrame}.
             *
             * @param source the {@link FrameDesc} that the RTP packet belongs
             * to.
             * @param pkt the packet for which to decide to accept
             * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt>
             * to be written into the arbitrary target/receiver that owns this
             * {@link SeenFrame} ; otherwise, <tt>false</tt>
             */
            boolean accept(FrameDesc source, RawPacket pkt)
            {
                if (this == mostRecentSentFrame /* the max frame can expand */
                    || availableQualityIndices == null
                    || availableQualityIndices.length < 2)
                {
                    int end = source.getEnd();
                    srcSeqNumLimit
                        = end != -1 ? source.getEnd() : source.getMaxSeen() ;
                }

                if (srcSeqNumLimit == -1)
                {
                    return false;
                }

                int start = source.getStart();
                srcSeqNumStart
                    = start != -1 ? source.getStart() : source.getMinSeen();

                int seqNum = pkt.getSequenceNumber();

                boolean accept
                    = RTPUtils.sequenceNumberDiff(seqNum, srcSeqNumLimit) <= 0;

                if (!accept && logger.isDebugEnabled())
                {
                    logger.debug("frame_corruption seq=" + seqNum
                        + ",seq_start=" + srcSeqNumStart
                        + ",seq_limit=" + srcSeqNumLimit);
                }

                return accept;
            }

            /**
             * Translates accepted packets and drops packets that are not
             * accepted (by this instance).
             *
             * @param pktIn the packet to translate or drop.
             *
             * @return the translated packet (along with any piggy backed
             * packets), if the packet is accepted, null otherwise.
             */
            RawPacket[] rtpTransform(RawPacket pktIn)
            {
                RawPacket[] pktsOut;
                long srcSSRC = pktIn.getSSRCAsLong();

                if (maybeFixInitialIndependentFrame)
                {
                    maybeFixInitialIndependentFrame = false;

                    if (srcSeqNumStart != pktIn.getSequenceNumber())
                    {
                        MediaStreamTrackDesc source = weakSource.get();
                        assert source != null;
                        // Piggy back till max seen.
                        RawPacketCache inCache = source
                            .getMediaStreamTrackReceiver()
                            .getStream()
                            .getCachingTransformer()
                            .getIncomingRawPacketCache();

                        int len = RTPUtils.sequenceNumberDiff(
                            srcSeqNumLimit, srcSeqNumStart) + 1;
                        pktsOut = new RawPacket[len];
                        for (int i = 0; i < pktsOut.length; i++)
                        {
                            // Note that the ingress cache might not have the desired
                            // packet.
                            pktsOut[i] = inCache.get(srcSSRC, (srcSeqNumStart + i) & 0xFFFF);
                        }
                    }
                    else
                    {
                        pktsOut = new RawPacket[] { pktIn };
                    }
                }
                else
                {
                    pktsOut = new RawPacket[]{ pktIn };
                }

                for (int i = 0; i < pktsOut.length; i++)
                {
                    RawPacket pktOut = pktsOut[i];

                    // Note that the ingress cache might not have the desired packet.
                    if (pktOut == null)
                    {
                        continue;
                    }

                    if (seqNumTranslation != null)
                    {
                        int srcSeqNum = pktOut.getSequenceNumber();
                        int dstSeqNum = seqNumTranslation.apply(srcSeqNum);

                        if (srcSeqNum != dstSeqNum)
                        {
                            pktOut.setSequenceNumber(dstSeqNum);
                        }
                    }

                    if (tsTranslation != null)
                    {
                        long dstTs = tsTranslation.apply(srcTs);

                        if (dstTs != srcTs)
                        {
                            pktOut.setTimestamp(dstTs);
                        }
                    }

                    if (ENABLE_VP8_PICID_REWRITING && dstPictureID > -1)
                    {
                        MediaStreamTrackDesc source = weakSource.get();
                        assert source != null;

                        REDBlock redBlock = source.getMediaStreamTrackReceiver()
                            .getStream().getPrimaryREDBlock(
                                pktOut.getBuffer(),
                                pktOut.getOffset(),
                                pktOut.getLength());

                        if (!DePacketizer
                            .VP8PayloadDescriptor.hasExtendedPictureId(
                            redBlock.getBuffer(), redBlock.getOffset(),
                            redBlock.getLength()))
                        {
                            // XXX we have observed that using a non-extended
                            // picture ID makes the Chrome 58 jitter buffer to
                            // completely freak out. So here we expand the non
                            // extended picture id field and convert it to an
                            // extended one.
                            byte[] srcBuf = pktOut.getBuffer();
                            byte[] dstBuf = new byte[srcBuf.length + 1];
                            System.arraycopy(
                                srcBuf, 0, dstBuf, 0, redBlock.getOffset() + 3);
                            System.arraycopy(srcBuf, redBlock.getOffset() + 3,
                                dstBuf, redBlock.getOffset() + 4,
                                srcBuf.length - redBlock.getOffset() - 3);

                            // set the extended picture id bit.
                            dstBuf[redBlock.getOffset() + 2] |= (byte) (0x80);

                            pktOut = new RawPacket(dstBuf,
                                pktOut.getOffset(), pktOut.getLength() + 1);
                            pktsOut[i] = pktOut;

                            logger.debug("Extending the picture ID of a VP8 pkt.");
                        }

                        if (!DePacketizer
                            .VP8PayloadDescriptor.setExtendedPictureId(
                                // XXX pktOut is not a typo.
                            pktOut.getBuffer(), redBlock.getOffset(),
                            redBlock.getLength(), dstPictureID))
                        {
                            logger.warn("Failed to set the VP8 extended" +
                                " picture ID.");
                        }
                    }

                    if (dstTL0PICIDX > -1)
                    {
                        MediaStreamTrackDesc source = weakSource.get();
                        assert source != null;

                        REDBlock redBlock = source.getMediaStreamTrackReceiver()
                            .getStream().getPrimaryREDBlock(
                                pktOut.getBuffer(),
                                pktOut.getOffset(),
                                pktOut.getLength());

                        if (!DePacketizer.VP8PayloadDescriptor.setTL0PICIDX(
                            redBlock.getBuffer(), redBlock.getOffset(),
                            redBlock.getLength(), dstTL0PICIDX))
                        {
                            logger.warn("Failed to set the VP8 TL0PICIDX.");
                        }
                    }
                }
                return pktsOut;
            }

            /**
             * Gets the max sequence number that has been sent out by this instance.
             *
             * @return the max sequence number that has been sent out by this
             * instance.
             */
            int getMaxSeqNum()
            {
                return seqNumTranslation == null
                    ? srcSeqNumLimit : seqNumTranslation.apply(srcSeqNumLimit);
            }

            /**
             * Gets the max timestamp that has been sent out by this instance.
             *
             * @return the max timestamp that has been sent out by this instance.
             */
            long getTs()
            {
                return tsTranslation == null ? srcTs : tsTranslation.apply(srcTs);
            }

            /**
             * Resets this seen frame.
             *
             * @param srcTs the timestamp of the seen frame.
             * @param seqNumTranslation the {@link SeqNumTranslation} to apply to
             * RTP packets of this frame.
             * @param tsTranslation the {@link TimestampTranslation} to apply to
             * RTP packets of this frame.
             * @param dstPictureID The VP8 picture id to set to outgoing packets
             * that belong to this frame.
             * @param dstTL0PICIDX The VP8 TL0PICIDX to set to outgoing packets
             * that belong to this frame.
             */
            public void reset(
                long srcTs,
                SeqNumTranslation seqNumTranslation,
                TimestampTranslation tsTranslation,
                int dstPictureID, int dstTL0PICIDX,
                boolean maybeFixInitialIndependentFrame)
            {
                this.maybeFixInitialIndependentFrame = maybeFixInitialIndependentFrame;
                this.srcSeqNumLimit = -1;
                this.srcSeqNumStart = -1;
                this.srcTs = srcTs;
                this.seqNumTranslation = seqNumTranslation;
                this.tsTranslation = tsTranslation;
                this.dstPictureID = dstPictureID;
                this.dstTL0PICIDX = dstTL0PICIDX;
            }

            /**
             * Resets this seen frame.
             *
             * @param srcTs the timestamp of the seen frame.
             */
            void reset(long srcTs)
            {
                reset(srcTs, null, null, -1, -1, false);
            }
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
            public SeenFrame getOrCreate()
            {
                return pool.isEmpty() ? new SeenFrame() : pool.remove();
            }

            /**
             * Returns a {@link SeenFrame} to this allocator.
             *
             * @param value the {@link SeenFrame} to return.
             */
            public void returnSeenFrame(SeenFrame value)
            {
                pool.add(value);
            }
        }
    }
}
