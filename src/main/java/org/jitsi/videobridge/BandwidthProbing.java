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
package org.jitsi.videobridge;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.rtp.translator.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;

import java.util.*;

/**
 * @author George Politis
 */
public class BandwidthProbing
    extends PeriodicRunnable
{
    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger = Logger.getLogger(BandwidthProbing.class);

    /**
     * the interval/period in milliseconds at which {@link #run()} is to be
     * invoked.
     */
    private static final long PADDING_PERIOD_MS = 15;

    /**
     *
     */
    private final VideoChannel dest;

    /**
     * The sequence number to use if probing with the JVB's SSRC.
     */
    int seqNum = new Random().nextInt(0xFFFF);

    /**
     * The RTP timestamp to use if probing with the JVB's SSRC.
     */
    long ts = new Random().nextInt() & 0xFFFFFFFFL;

    /**
     *
     * @param dest
     */
    BandwidthProbing(VideoChannel dest)
    {
        super(PADDING_PERIOD_MS);
        this.dest = dest;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run()
    {
        super.run();

        List<PaddingParams> paddingParamsList
            = dest.getBitrateController().getPaddingParamsList();

        if (paddingParamsList == null || paddingParamsList.isEmpty())
        {
            return;
        }

        long totalCurrentBps = 0, totalOptimalBps = 0;

        List<Long> ssrcsToProtect = new ArrayList<>();
        for (PaddingParams paddingParams : paddingParamsList)
        {
            long currentBps = paddingParams.getCurrentBps();
            if (currentBps > 0)
            {
                // Do not protect SSRC if it's not streaming.
                totalCurrentBps += currentBps;
                long ssrc = paddingParams.getTargetSSRC();
                if (ssrc > -1)
                {
                    ssrcsToProtect.add(ssrc);
                }
            }

            totalOptimalBps += paddingParams.getOptimalBps();
        }

        // How much padding do we need?
        long totalNeededBps = totalOptimalBps - totalCurrentBps;
        if (totalNeededBps < 1)
        {
            // Not much.
            return;
        }

        // How much padding can we afford?
        long bweBps = ((VideoMediaStream) dest.getStream())
            .getOrCreateBandwidthEstimator().getLatestEstimate();
        long maxPaddingBps = bweBps - totalCurrentBps;
        long paddingBps = Math.min(totalNeededBps, maxPaddingBps);

        if (logger.isDebugEnabled())
        {
            logger.debug("padding,stream="+ dest.getStream().hashCode()
                + " padding_bps=" + paddingBps
                + ",optimal_bps=" + totalOptimalBps
                + ",current_bps=" + totalCurrentBps
                + ",needed_bps=" + totalNeededBps
                + ",max_padding_bps=" + maxPaddingBps
                + ",bwe_bps=" + bweBps);
        }

        if (paddingBps < 1)
        {
            // Not much.
            return;
        }


        MediaStreamImpl stream = (MediaStreamImpl) dest.getStream();
        long bytes = PADDING_PERIOD_MS * paddingBps / 1000 / 8;
        RtxTransformer rtxTransformer = stream.getRtxTransformer();

        if (!ssrcsToProtect.isEmpty())
        {
            // stream protection with padding.
            for (Long ssrc : ssrcsToProtect)
            {
                bytes = rtxTransformer.sendPadding(ssrc, bytes);
                if (bytes < 1)
                {
                    // We're done.
                    return;
                }
            }
        }

        // Send crap with the JVB's SSRC.
        long mediaSSRC = getSenderSSRC();
        int pt = 100; // VP8 pt.
        ts += 3000;

        int pktLen = RawPacket.FIXED_HEADER_SIZE + 0xFF;
        int len = (int) (bytes / pktLen) + 1 /* account for the mod */;

        for (int i = 0; i < len; i++)
        {
            try
            {
                // These packets should not be cached.
                RawPacket pkt
                    = RawPacket.makeRTP(mediaSSRC, pt, seqNum++, ts, pktLen);

                stream.injectPacket(pkt, /* data */ true, rtxTransformer);
            }
            catch (TransmissionFailedException tfe)
            {
                logger.warn("Failed to retransmit a packet.");
            }
        }
    }

    /**
     * (attempts) to get the local SSRC that will be used in the media sender
     * SSRC field of the RTCP reports. TAG(cat4-local-ssrc-hurricane)
     *
     * @return
     */
    private long getSenderSSRC()
    {
        StreamRTPManager streamRTPManager = dest.getStream().getStreamRTPManager();
        if (streamRTPManager == null)
        {
            return -1;
        }

        return dest.getStream().getStreamRTPManager().getLocalSSRC();
    }
}
