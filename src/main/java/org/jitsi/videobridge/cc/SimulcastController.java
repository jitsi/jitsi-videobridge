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
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
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
class SimulcastController
    implements PaddingParams
{
    /**
     * A {@link WeakReference} to the {@link MediaStreamTrackDesc} that feeds
     * this instance with RTP/RTCP packets.
     */
    private final WeakReference<MediaStreamTrackDesc> weakSource;

    /**
     * The SSRC to protect when probing for bandwidth ({@see PaddingParams}) and
     * for RTP/RTCP packet rewritting.
     */
    private final long targetSSRC;

    /**
     * The {@link BitstreamController} for the currently forwarded RTP stream.
     */
    private final BitstreamController bitstreamController;

    /**
     * Ctor.
     *
     * @param source the source {@link MediaStreamTrackDesc}
     */
    public SimulcastController(MediaStreamTrackDesc source)
    {
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

        bitstreamController = new BitstreamController(weakSource.get());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Bitrates getBitrates()
    {
        long currentBps = 0;
        MediaStreamTrackDesc source = weakSource.get();
        if (source == null)
        {
            return Bitrates.EMPTY;
        }

        RTPEncodingDesc[] sourceEncodings = source.getRTPEncodings();
        if (ArrayUtils.isNullOrEmpty(sourceEncodings))
        {
            return Bitrates.EMPTY;
        }

        int currentIdx = bitstreamController.getCurrentIndex();
        if (currentIdx > -1 && sourceEncodings[currentIdx].isActive())
        {
            currentBps = sourceEncodings[currentIdx].getLastStableBitrateBps();
        }

        long optimalBps = 0;
        int optimalIdx = bitstreamController.getOptimalIndex();
        if (optimalIdx > -1)
        {
            for (int i = optimalIdx; i > -1; i--)
            {
                if (!sourceEncodings[i].isActive())
                {
                    continue;
                }

                long bps = sourceEncodings[i].getLastStableBitrateBps();
                if (bps > 0)
                {
                    optimalBps = bps;
                    break;
                }
            }
        }

        return new Bitrates(currentBps, optimalBps);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getTargetSSRC()
    {
        return targetSSRC;
    }

    /**
     * Defines a packet filter that controls which packets to be written into
     * some arbitrary target/receiver that owns this {@link SimulcastController}.
     *
     * @param buf the <tt>byte</tt> array that holds the packet.
     * @param off the offset in <tt>buffer</tt> at which the actual data begins.
     * @param len the number of <tt>byte</tt>s in <tt>buffer</tt> which
     * constitute the actual data.
     * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt> to be
     * written into the arbitrary target/receiver that owns this
     * {@link SimulcastController} ; otherwise, <tt>false</tt>
     */
    public boolean accept(byte[] buf, int off, int len)
    {
        int targetIndex = bitstreamController.getTargetIndex()
            , currentIndex = bitstreamController.getCurrentIndex();

        if (targetIndex < 0 && currentIndex > -1)
        {
            bitstreamController.setTL0Idx(-1);
            return false;
        }

        MediaStreamTrackDesc sourceTrack = weakSource.get();
        assert sourceTrack != null;
        FrameDesc sourceFrameDesc = sourceTrack.findFrameDesc(buf, off, len);

        if (sourceFrameDesc == null || buf == null || off < 0
            || len < RawPacket.FIXED_HEADER_SIZE || buf.length < off + len)
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

            currentTL0IsActive = sourceEncodings[currentTL0Idx].isActive();
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
            return bitstreamController.accept(sourceFrameDesc, buf, off, len);
        }

        // An intra-codec/simulcast switch pending.

        boolean sourceTL0IsActive = false;
        int sourceTL0Idx = sourceFrameDesc.getRTPEncoding().getIndex();
        if (sourceTL0Idx > -1)
        {
            sourceTL0Idx
                = sourceEncodings[sourceTL0Idx].getBaseLayer().getIndex();

            sourceTL0IsActive = sourceEncodings[sourceTL0Idx].isActive();
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
            return bitstreamController.accept(sourceFrameDesc, buf, off, len);
        }

        if ((targetTL0Idx <= sourceTL0Idx && sourceTL0Idx < currentTL0Idx)
            || (currentTL0Idx < sourceTL0Idx && sourceTL0Idx <= targetTL0Idx)
            || (!currentTL0IsActive && sourceTL0Idx <= targetTL0Idx))
        {
            bitstreamController.setTL0Idx(sourceTL0Idx);

            // Give the bitstream filter a chance to drop the packet.
            return bitstreamController.accept(sourceFrameDesc, buf, off, len);
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
    public void setTargetIndex(int newTargetIdx)
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

        // Something lower than the current must be streaming, so we're able
        // to make a switch, so ask for a key frame.

        boolean sendFIR = targetTL0Idx < currentTL0Idx;
        if (!sendFIR && targetTL0Idx > currentTL0Idx)
        {
            // otherwise, check if anything higher is streaming.
            for (int i = currentTL0Idx + 1; i < targetTL0Idx + 1; i++)
            {
                if (sourceEncodings[i].isActive())
                {
                    sendFIR = true;
                    break;
                }
            }
        }

        if (sendFIR)
        {
            ((RTPTranslatorImpl) sourceTrack.getMediaStreamTrackReceiver()
                .getStream().getRTPTranslator())
                .getRtcpFeedbackMessageSender().sendFIR(
                (int) targetSSRC);
        }
    }

    /**
     * Update the optimal subjective quality index for this instance.
     *
     * @param optimalIndex new optimal subjective quality index.
     */
    public void setOptimalIndex(int optimalIndex)
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
    public RawPacket[] rtpTransform(RawPacket pktIn)
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
    public RawPacket rtcpTransform(RawPacket pktIn)
    {
        if (!RTCPPacketPredicate.INSTANCE.test(pktIn))
        {
            return pktIn;
        }

        BitstreamController bitstreamController = this.bitstreamController;

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

    class BitstreamController
    {
        /**
         * The available subjective quality indexes that this RTP stream offers.
         */
        private int[] availableIdx;

        /**
         * The sequence number offset that this bitstream started.
         */
        private int seqNumOff;

        /**
         * The timestamp offset that this bitstream started.
         */
        private long tsOff;

        /**
         * The SSRC of the TL0 of the RTP stream that is currently being
         * forwarded. This is useful for simulcast and RTCP SR rewriting.
         */
        private long tl0SSRC;

        /**
         * The subjective quality index for of the TL0 of this instance.
         */
        private int tl0Idx = -2;

        /**
         * A weak reference to the {@link MediaStreamTrackDesc} that this controller
         * is associated to.
         */
        private final WeakReference<MediaStreamTrackDesc> weakSource;

        /**
         * The target subjective quality index for this instance. This instance
         * switches between the available RTP streams and sub-encodings until it
         * reaches this target. -1 effectively means that the stream is suspended.
         */
        private int targetIdx;

        /**
         * The optimal subjective quality index for this instance.
         */
        private int optimalIdx;

        /**
         * The current subjective quality index for this instance. If this is
         * different than the target, then a switch is pending.
         */
        private int currentIdx;

        /**
         * The number of transmitted bytes.
         */
        private long transmittedBytes;

        /**
         * The number of transmitted packets.
         */
        private long transmittedPackets;

        /**
         * The max (biggest timestamp) frame that we've sent out.
         */
        private SeenFrame maxSentFrame;

        /**
         * At 60fps, this holds 5 seconds worth of frames.
         * At 30fps, this holds 10 seconds worth of frames.
         */
        private final Map<Long, SeenFrame> seenFrames
            = Collections.synchronizedMap(new LRUCache<Long, SeenFrame>(300));

        /**
         * Ctor. The ctor sets all of the indices to -1.
         *
         * @param source the source {@link MediaStreamTrackDesc} for this instance.
         */
        BitstreamController(MediaStreamTrackDesc source)
        {
            this(new WeakReference<>(source), -1, -1, 0, 0, -1, -1, -1);
        }

        /**
         * A private ctor that initializes all the fields of this instance.
         *
         * @param weakSource a weak reference to the source
         * {@link MediaStreamTrackDesc} for this instance.
         * @param seqNumOff the sequence number offset (rewritten sequence numbers
         * will start from seqNumOff + 1).
         * @param tsOff the timestamp offset (rewritten timestamps will start from
         * tsOff + 1).
         * @param transmittedBytesOff the transmitted bytes offset (the bytes that
         * this instance sends will be added to this value).
         * @param transmittedPacketsOff the transmitted packets offset (the packets
         * that this instance sends will be added to this value).
         * @param tl0Idx the TL0 of the RTP stream that this controller filters
         * traffic from.
         * @param targetIdx the target subjective quality index for this instance.
         * @param optimalIdx the optimal subjective quality index for this instance.
         */
        private BitstreamController(
            WeakReference<MediaStreamTrackDesc> weakSource,
            int seqNumOff, long tsOff,
            long transmittedBytesOff, long transmittedPacketsOff,
            int tl0Idx, int targetIdx, int optimalIdx)
        {
            this.seqNumOff = seqNumOff;
            this.tsOff = tsOff;
            this.transmittedBytes = transmittedBytesOff;
            this.transmittedPackets = transmittedPacketsOff;
            this.optimalIdx = optimalIdx;
            this.targetIdx = targetIdx;
            // a stream always starts suspended (and resumes with a key frame).
            this.currentIdx = -1;
            this.weakSource = weakSource;
            setTL0Idx(tl0Idx);
        }

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

            this.seenFrames.clear();

            if (maxSentFrame != null)
            {
                this.tsOff = getMaxTs();
                this.seqNumOff = getMaxSeqNum();
                this.maxSentFrame = null;
            }

            this.tl0Idx = newTL0Idx;

            MediaStreamTrackDesc source = weakSource.get();
            assert source != null;
            RTPEncodingDesc[] rtpEncodings = source.getRTPEncodings();
            if (ArrayUtils.isNullOrEmpty(rtpEncodings))
            {
                this.availableIdx = null;
                this.tl0SSRC = -1;
            }
            else
            {
                if (tl0Idx > -1)
                {
                    tl0Idx = rtpEncodings[tl0Idx].getBaseLayer().getIndex();
                }

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

                availableIdx = new int[availableQualities.size()];
                Iterator<Integer> iterator = availableQualities.iterator();
                for (int i = 0; i < availableIdx.length; i++)
                {
                    availableIdx[i] = iterator.next();
                }

                if (tl0Idx > -1)
                {
                    tl0SSRC = rtpEncodings[tl0Idx].getPrimarySSRC();
                }
                else
                {
                    tl0SSRC = -1;
                }
            }
        }

        /**
         * Defines an RTP packet filter that controls which packets to be written
         * into some arbitrary target/receiver that owns this
         * {@link BitstreamController}.
         *
         * @param sourceFrameDesc the {@link FrameDesc} that the RTP packet belongs
         * to.
         * @param buf the <tt>byte</tt> array that holds the packet.
         * @param off the offset in <tt>buffer</tt> at which the actual data begins.
         * @param len the number of <tt>byte</tt>s in <tt>buffer</tt> which
         * constitute the actual data.
         * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt> to be
         * written into the arbitrary target/receiver that owns this
         * {@link BitstreamController} ; otherwise, <tt>false</tt>
         */
        public boolean accept(
            FrameDesc sourceFrameDesc, byte[] buf, int off, int len)
        {
            if (sourceFrameDesc.getStart() == -1)
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

                if (availableIdx != null && availableIdx.length != 0)
                {
                    currentIdx = availableIdx[0];
                    for (int i = 1; i < availableIdx.length; i++)
                    {
                        if (availableIdx[i] <= targetIdx)
                        {
                            currentIdx = availableIdx[i];
                        }
                        else
                        {
                            break;
                        }
                    }

                    this.currentIdx = currentIdx;
                }

                if (currentIdx > -1 && (maxSentFrame == null
                    || TimeUtils.rtpDiff(srcTs, maxSentFrame.srcTs) > 0))
                {
                    // the stream is not suspended and we're not dealing with a late
                    // frame.

                    RTPEncodingDesc sourceEncodings[] = sourceFrameDesc
                        .getRTPEncoding().getMediaStreamTrack().getRTPEncodings();

                    // TODO ask for independent frame if we're skipping a TL0.

                    int sourceIdx = sourceFrameDesc.getRTPEncoding().getIndex();
                    if (sourceEncodings[currentIdx].requires(sourceIdx)
                        && (maxSentFrame == null
                        || maxSentFrame.effectivelyComplete))
                    {
                        // the quality of the frame is a dependency of the
                        // forwarded quality and the max frame is effectively
                        // complete.

                        SeqNumTranslation seqNumTranslation;
                        if (maxSentFrame == null
                            || (availableIdx != null && availableIdx.length > 1))
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
                            // translation from the maxSentFrame.
                            seqNumTranslation = maxSentFrame.seqNumTranslation;
                        }

                        TimestampTranslation tsTranslation;
                        if (maxSentFrame == null)
                        {
                            if (tsOff > -1)
                            {
                                long tsDelta = (tsOff + 3000
                                    - sourceFrameDesc.getTimestamp()) & 0xFFFFFFFFL;

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
                            tsTranslation = maxSentFrame.tsTranslation;
                        }

                        destFrame = new SeenFrame(
                            srcTs, seqNumTranslation, tsTranslation);
                        seenFrames.put(srcTs, destFrame);
                        maxSentFrame = destFrame;
                    }
                    else
                    {
                        destFrame = new SeenFrame(srcTs, null, null);
                        seenFrames.put(srcTs, destFrame);
                    }
                }
                else
                {
                    // TODO ask for independent frame if we're filtering a TL0.

                    destFrame = new SeenFrame(srcTs, null, null);
                    seenFrames.put(srcTs, destFrame);
                }
            }

            boolean accept = destFrame.accept(sourceFrameDesc, buf, off, len);

            if (accept)
            {
                transmittedPackets++;
                transmittedBytes += len;
            }

            return accept;
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
            SeenFrame maxSentFrame = this.maxSentFrame;

            // Rewrite timestamp.
            if (maxSentFrame != null && maxSentFrame.tsTranslation != null)
            {
                long srcTs = RTCPSenderInfoUtils.getTimestamp(baf);
                long dstTs = maxSentFrame.tsTranslation.apply(srcTs);

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
            return maxSentFrame == null ? seqNumOff : maxSentFrame.getMaxSeqNum();
        }

        /**
         * Gets the maximum timestamp that this instance has accepted.
         */
        private long getMaxTs()
        {
            return maxSentFrame == null ? tsOff : maxSentFrame.getTs();
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
         * Utility class that holds information about seen frames.
         */
        class SeenFrame
        {
            /**
             * The sequence number translation to apply to accepted RTP packets.
             */
            private final SeqNumTranslation seqNumTranslation;

            /**
             * The RTP timestamp translation to apply to accepted RTP/RTCP packets.
             */
            private final TimestampTranslation tsTranslation;

            /**
             * A boolean that indicates whether or not the transform thread should
             * try to piggyback missed packets from the initial key frame.
             */
            private boolean maybeFixInitialIndependentFrame = true;

            /**
             * A boolean that determines whether or not this seen frame is
             * "effectively" complete. Effectively complete means that we've either
             * seen its end sequence number (so we know its size), or it's not a
             * TL0. This is important to know with temporal scalability because
             * we want to be able to corrupt an effectively complete frame.
             */
            private boolean effectivelyComplete = false;

            /**
             * The source timestamp of this frame.
             */
            private final long srcTs;

            /**
             * The maximum source sequence number to accept. -1 means drop.
             */
            private int srcSeqNumLimit = -1;

            /**
             * The start source sequence number of this frame.
             */
            private int srcSeqNumStart = -1;

            /**
             * Ctor.
             *
             * @param ts the timestamp of the seen frame.
             * @param seqNumTranslation the {@link SeqNumTranslation} to apply to
             * RTP packets of this frame.
             * @param tsTranslation the {@link TimestampTranslation} to apply to
             * RTP packets of this frame.
             */
            SeenFrame(long ts, SeqNumTranslation seqNumTranslation,
                      TimestampTranslation tsTranslation)
            {
                this.srcTs = ts;
                this.seqNumTranslation = seqNumTranslation;
                this.tsTranslation = tsTranslation;
            }

            /**
             * Defines an RTP packet filter that controls which packets to be
             * written into some arbitrary target/receiver that owns this
             * {@link SeenFrame}.
             *
             * @param source the {@link FrameDesc} that the RTP packet belongs
             * to.
             * @param buf the <tt>byte</tt> array that holds the packet.
             * @param off the offset in <tt>buffer</tt> at which the actual data
             * begins.
             * @param len the number of <tt>byte</tt>s in <tt>buffer</tt> which
             * constitute the actual data.
             * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt>
             * to be written into the arbitrary target/receiver that owns this
             * {@link SeenFrame} ; otherwise, <tt>false</tt>
             */
            boolean accept(FrameDesc source, byte[] buf, int off, int len)
            {
                // non TL0s (which are frames with dependencies) can be "corrupted",
                // so they're effectively complete.
                effectivelyComplete = !ArrayUtils.isNullOrEmpty(
                    source.getRTPEncoding().getDependencyEncodings());

                if (this == maxSentFrame /* the max frame can expand */
                    || availableIdx == null || availableIdx.length < 2)
                {
                    if (srcSeqNumLimit == -1 || RTPUtils.sequenceNumberDiff(
                        source.getMaxSeen(), srcSeqNumLimit) > 0)
                    {
                        srcSeqNumLimit = source.getMaxSeen();
                    }

                    if (!effectivelyComplete
                        && source.getMaxSeen() == source.getEnd())
                    {
                        effectivelyComplete = true;
                    }
                }

                if (srcSeqNumLimit == -1)
                {
                    return false;
                }

                if (srcSeqNumStart == -1 || RTPUtils.sequenceNumberDiff(
                    srcSeqNumStart, source.getMinSeen()) > 0)
                {
                    srcSeqNumStart = source.getMinSeen();
                }

                int seqNum = RawPacket.getSequenceNumber(buf, off, len);

                return RTPUtils.sequenceNumberDiff(seqNum, srcSeqNumLimit) <= 0;
            }

            /**
             * Translates accepted packets and drops packets that are not accepted
             * (by this instance).
             *
             * @param pktIn the packet to translate or drop.
             *
             * @return the translated packet (along with any piggy backed packets),
             * if the packet is accepted, null otherwise.
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

                for (RawPacket pktOut : pktsOut)
                {
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
                        long srcTs = pktOut.getTimestamp();
                        long dstTs = tsTranslation.apply(srcTs);

                        if (dstTs != srcTs)
                        {
                            pktOut.setTimestamp(dstTs);
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
        }
    }
}
