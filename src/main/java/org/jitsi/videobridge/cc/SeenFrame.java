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
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.function.*;

/**
 * Utility class that holds information about seen frames.
 *
 * @author George Politis
 * @author Brian Baldino
 */
class SeenFrame
{
    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger = Logger.getLogger(SeenFrame.class);

    /**
     * The sequence number translation to apply to accepted RTP packets.
     */
    SeqNumTranslation seqNumTranslation;

    /**
     * The RTP timestamp translation to apply to accepted RTP/RTCP packets.
     */
    TimestampTranslation tsTranslation;

    /**
     * A boolean that indicates whether or not the transform thread should
     * try to piggyback missed packets from the initial key frame.
     */
    private boolean maybeFixInitialIndependentFrame = true;

    /**
     * The source timestamp of this frame.
     */
    long srcTs;

    /**
     * The VP8 picture id to set to outgoing packets that belong to this
     * frame.
     */
    int dstPictureID = -1;

    /**
     * The VP8 TL0PICIDX to set to outgoing packets that belong to this
     * frame.
     * https://tools.ietf.org/html/rfc7741#section-4.2
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
     * Whether or not the stream to which this frame belongs is adaptive
     */
    private boolean isAdaptive = false;

    /**
     * Defines an RTP packet filter that controls which packets to be
     * written into some arbitrary target/receiver that owns this
     * {@link SeenFrame}.
     *
     * @param source the {@link FrameDesc} that the RTP packet belongs
     * to.
     * @param pkt the packet for which to decide to accept
     * @param isMostRecentFrame whether or not this {@link SeenFrame} object
     * corresponds to the most recent frame
     * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt>
     * to be written into the arbitrary target/receiver that owns this
     * {@link SeenFrame} ; otherwise, <tt>false</tt>
     */
    boolean accept(FrameDesc source, RawPacket pkt, boolean isMostRecentFrame)
    {
        if (isAdaptive && !isMostRecentFrame)
        {
            // Old frames for an adaptive stream can't be forwarded since
            // we can't rewrite the sequence numbers/timestamps correctly
            return false;
        }
        srcSeqNumLimit = source.getMaxSeen();
        srcSeqNumStart = source.getMinSeen();

        int seqNum = pkt.getSequenceNumber();

        // We'll accept the packet if it's no newer than our srcSeqNumLimit
        boolean accept =
            RTPUtils.isOlderSequenceNumberThan(
                seqNum,
                RTPUtils.applySequenceNumberDelta(srcSeqNumLimit, 1));

        if (!accept && logger.isDebugEnabled())
        {
            logger.debug("frame_corruption seq=" + seqNum
                + ",seq_start=" + srcSeqNumStart
                + ",seq_limit=" + srcSeqNumLimit);
        }

        return accept;
    }

    /**
     * Rewrite the vp8 picture id
     * @param pktOut the packet in which to rewrite the picture id
     * @return a RawPacket matching the given one in all ways but
     * with the picture id appropriately rewritten (may not be the same
     * RawPacket instance as pktOut)
     */
    private RawPacket handleVp8PictureIdRewriting(RawPacket pktOut,
                                                  MediaStream sourceStream)
    {
        REDBlock redBlock = sourceStream.getPrimaryREDBlock(pktOut);

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
        // We may have re-assigned pktOut to a new RawPacket
        return pktOut;
    }

    /**
     * Handles setting the appropriate TL0PICIDX on the given packet
     * @param pktOut the packet in which to set the TL0PICIDX (in place)
     *
     */
    private void handleSettingVp8TL0PICIDX(RawPacket pktOut,
                                           MediaStream sourceStream)
    {
        REDBlock redBlock = sourceStream.getPrimaryREDBlock(pktOut);

        if (!DePacketizer.VP8PayloadDescriptor.setTL0PICIDX(
            redBlock.getBuffer(), redBlock.getOffset(),
            redBlock.getLength(), dstTL0PICIDX))
        {
            logger.warn("Failed to set the VP8 TL0PICIDX.");
        }
    }

    /**
     * Translates accepted packets and drops packets that are not
     * accepted (by this instance).
     *
     * @param pktIn the packet to translate or drop.
     * @param sourceStream the {@link MediaStream} to which this frame
     * belongs
     * @param performVp8PicIdRewriting whether or not to perform vp8
     * picture id rewriting
     *
     * @return the translated packet (along with any piggy backed
     * packets), if the packet is accepted, null otherwise.
     */
    RawPacket[] rtpTransform(RawPacket pktIn, MediaStream sourceStream,
                             boolean performVp8PicIdRewriting)
    {
        RawPacket[] pktsOut;
        long srcSsrc = pktIn.getSSRCAsLong();

        if (maybeFixInitialIndependentFrame)
        {
            maybeFixInitialIndependentFrame = false;

            if (srcSeqNumStart != pktIn.getSequenceNumber())
            {
                // Piggy back till max seen.
                RawPacketCache inCache = ((MediaStreamImpl)sourceStream)
                    .getCachingTransformer()
                    .getIncomingRawPacketCache();

                int len = RTPUtils.getSequenceNumberDelta(
                    srcSeqNumLimit, srcSeqNumStart) + 1;
                pktsOut = new RawPacket[len];
                for (int i = 0; i < pktsOut.length; i++)
                {
                    // Note that the ingress cache might not have the desired
                    // packet.
                    pktsOut[i] = inCache.get(srcSsrc, (srcSeqNumStart + i) & 0xFFFF);
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

            if (performVp8PicIdRewriting && dstPictureID > -1)
            {
                pktsOut[i] = handleVp8PictureIdRewriting(pktOut, sourceStream);
            }

            if (dstTL0PICIDX > -1)
            {
                handleSettingVp8TL0PICIDX(pktOut, sourceStream);
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
     * @param isAdaptive whether or not the stream to which this frame
     * belongs is adaptive
     */
    void reset(
        long srcTs,
        SeqNumTranslation seqNumTranslation,
        TimestampTranslation tsTranslation,
        int dstPictureID, int dstTL0PICIDX,
        boolean maybeFixInitialIndependentFrame,
        boolean isAdaptive)
    {
        this.maybeFixInitialIndependentFrame = maybeFixInitialIndependentFrame;
        this.srcSeqNumLimit = -1;
        this.srcSeqNumStart = -1;
        this.srcTs = srcTs;
        this.seqNumTranslation = seqNumTranslation;
        this.tsTranslation = tsTranslation;
        this.dstPictureID = dstPictureID;
        this.dstTL0PICIDX = dstTL0PICIDX;
        this.isAdaptive = isAdaptive;
    }

    /**
     * Resets this seen frame.
     *
     * @param srcTs the timestamp of the seen frame.
     */
    void reset(long srcTs)
    {
        reset(srcTs,
            null,
            null,
            -1,
            -1,
            false,
            false);
    }
}
