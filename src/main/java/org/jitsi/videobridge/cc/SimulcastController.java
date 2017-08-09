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
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import java.lang.ref.*;

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
     * The ConfigurationService to get config values from.
     */
    private static final ConfigurationService
        cfg = LibJitsi.getConfigurationService();

    /**
     * Index used to represent that forwarding is suspended.
     */
    private static final int SUSPENDED_INDEX = -1;

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
    private final long targetSsrc;

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
        BitrateController bitrateController, MediaStreamTrackDesc source,
        BitstreamController bitstreamController)
    {
        this.bitrateController = bitrateController;
        weakSource = new WeakReference<>(source);

        RTPEncodingDesc[] rtpEncodings = source.getRTPEncodings();
        if (ArrayUtils.isNullOrEmpty(rtpEncodings))
        {
            targetSsrc = -1;
        }
        else
        {
            targetSsrc = rtpEncodings[0].getPrimarySSRC();
        }

        this.bitstreamController = bitstreamController;
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
        return targetSsrc;
    }

    /**
     * Given the current base layer index, the target base layer index and the
     * base layer index of an incoming frame, returns whether the incoming
     * frame's base layer represents a step closer (or all the way to) the
     * target index we're trying to reach.  In either case (downscale or
     * upscale), 'incomingFrameBaseLayerIndex' will be desirable if it falls
     * in the range (currentBaseLayerIndex, targetBaseLayerIndex] OR
     * [targetBaseLayerIndex, currentBaseLayerIndex) e.g.:
     *
     * currentBaseLayerIndex = 0, incomingFrameBaseLayerIndex = 2,
     * targetBaseLayerIndex = 7 -> we're trying to upscale and incomingFrameBaseLayerIndex
     * represents a quality higher than what we currently have (but not the final
     * target) so forwarding it is a step in the right direction.
     * @param currentBaseLayerIndex the base layer index of the stream currently
     * being forwarded
     * @param incomingFrameBaseLayerIndex the base layer index to which the
     * current incoming frame belongs
     * @param targetBaseLayerIndex the base layer index of the stream we want to forward
     * @return true if the stream represented by incomingFrameBaseLayerIndex
     * represents a step closer (or all the way) to the targetBaseLayerIndex
     */
    private boolean shouldSwitchToBaseLayer(
        int currentBaseLayerIndex, int incomingFrameBaseLayerIndex, int targetBaseLayerIndex)
    {
        if ((currentBaseLayerIndex < incomingFrameBaseLayerIndex) &&
            incomingFrameBaseLayerIndex <= targetBaseLayerIndex)
        {
            // upscale case
            return true;
        }
        else if ((currentBaseLayerIndex > incomingFrameBaseLayerIndex) &&
            (incomingFrameBaseLayerIndex >= targetBaseLayerIndex))
        {
            // downscale case
            return true;
        }
        return false;
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
        if (pkt.isInvalid())
        {
            return false;
        }

        int targetIndex = bitstreamController.getTargetIndex(),
            currentIndex = bitstreamController.getCurrentIndex();

        // If we're suspended, we won't forward anything
        if (targetIndex == SUSPENDED_INDEX)
        {
            // Update the bitstreamController if it hasn't suspended yet
            if (currentIndex != SUSPENDED_INDEX)
            {
                bitstreamController.suspend();
            }
            return false;
        }
        // At this point we know we *want* to be forwarding something

        MediaStreamTrackDesc sourceTrack = weakSource.get();
        assert sourceTrack != null;
        FrameDesc sourceFrameDesc =
            sourceTrack.findFrameDesc(pkt.getSSRCAsLong(), pkt.getTimestamp());

        if (sourceFrameDesc == null)
        {
            return false;
        }

        RTPEncodingDesc[] sourceEncodings = sourceTrack.getRTPEncodings();

        if (ArrayUtils.isNullOrEmpty(sourceEncodings))
        {
            return false;
        }

        int sourceLayerIndex = sourceFrameDesc.getRTPEncoding().getIndex();

        // At this point we know we *want* to be forwarding something, but the
        // current layer we're forwarding is still sending, so we're not "desperate"
        int currentBaseLayerIndex;
        if (currentIndex == SUSPENDED_INDEX ||
            !sourceEncodings[currentIndex].getBaseLayer().isActive(true))
        {
            currentBaseLayerIndex = SUSPENDED_INDEX;
        }
        else
        {
            currentBaseLayerIndex = sourceEncodings[currentIndex].getBaseLayer().getIndex();
        }
        int sourceBaseLayerIndex = sourceEncodings[sourceLayerIndex].getBaseLayer().getIndex();
        if (sourceBaseLayerIndex == currentBaseLayerIndex)
        {
            // Regardless of whether a switch is pending or not, if an incoming
            // frame belongs to the current stream being forwarded, we'll
            // accept it (if the bitstreamController lets it through)
            return bitstreamController.accept(sourceFrameDesc, pkt,
                bitrateController.getVideoChannel().getStream());
        }

        // At this point we know that we want to be forwarding something and
        // that the incoming frame doesn't belong to the one we're currently
        // forwarding, so we need to check if there is a layer switch
        // pending and this frame brings us to (or closer to) the stream we want
        if (!sourceFrameDesc.isIndependent())
        {
            // If it's not a keyframe we can't switch to it anyway
            return false;
        }
        int targetBaseLayerIndex = sourceEncodings[targetIndex].getBaseLayer().getIndex();

        if (currentBaseLayerIndex != targetBaseLayerIndex)
        {
            // We do want to switch to another layer
            if (shouldSwitchToBaseLayer(currentBaseLayerIndex, sourceBaseLayerIndex, targetBaseLayerIndex))
            {
                // This frame represents, at least, a step in the right direction
                // towards the stream we want
                MediaStreamTrackDesc source = weakSource.get();
                assert source != null;
                bitstreamController.setTL0Idx(sourceBaseLayerIndex, source.getRTPEncodings());
                return bitstreamController.accept(sourceFrameDesc, pkt,
                    bitrateController.getVideoChannel().getStream());
            }
        }
        return false;
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
                (int) targetSsrc);
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

        MediaStreamTrackDesc source = weakSource.get();
        assert source != null;
        RawPacket[] pktsOut =
            bitstreamController.rtpTransform(pktIn,
                source.getMediaStreamTrackReceiver().getStream());

        if (!ArrayUtils.isNullOrEmpty(pktsOut)
            && pktIn.getSSRCAsLong() != targetSsrc)
        {
            // Rewrite the SSRC of the output RTP stream.
            for (RawPacket pktOut : pktsOut)
            {
                if (pktOut != null)
                {
                    pktOut.setSSRC((int) targetSsrc);
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
                    RTCPHeaderUtils.setSenderSSRC(baf, (int) targetSsrc);
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
        bitstreamController.suspend();
    }
}
