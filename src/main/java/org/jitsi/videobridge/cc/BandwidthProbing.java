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

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * @author George Politis
 */
public class BandwidthProbing
    extends PeriodicRunnable
{
    /**
     * The system property name that holds a boolean that determines whether or
     * not to activate the RTX bandwidth probing mechanism that implements
     * stream protection.
     */
    public static final String
        DISABLE_RTX_PROBING_PNAME = "org.jitsi.videobridge.DISABLE_RTX_PROBING";

    /**
     * The system property name that holds the interval/period in milliseconds
     * at which {@link #run()} is to be invoked.
     */
    public static final String
        PADDING_PERIOD_MS_PNAME = "org.jitsi.videobridge.PADDING_PERIOD_MS";

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private static final Logger logger
        = Logger.getLogger(BandwidthProbing.class);

    /**
     * The {@link TimeSeriesLogger} to be used by this instance to print time
     * series.
     */
    private static final TimeSeriesLogger timeSeriesLogger
        = TimeSeriesLogger.getTimeSeriesLogger(BandwidthProbing.class);

    /**
     * The ConfigurationService to get config values from.
     */
    private static final ConfigurationService
        cfg = LibJitsi.getConfigurationService();

    /**
     * the interval/period in milliseconds at which {@link #run()} is to be
     * invoked.
     */
    private static final long PADDING_PERIOD_MS =
        cfg != null ? cfg.getInt(PADDING_PERIOD_MS_PNAME, 15) : 15;

    /**
     * A boolean that determines whether or not to activate the RTX bandwidth
     * probing mechanism that implements stream protection.
     */
    private static final boolean DISABLE_RTX_PROBING =
        cfg != null && cfg.getBoolean(DISABLE_RTX_PROBING_PNAME, false);

    /**
     * The {@link VideoChannel} to probe for available send bandwidth.
     */
    private final VideoChannel dest;

    /**
     * The VP8 payload type to use when probing with the SSRC of the bridge.
     */
    private int vp8PT = -1;

    /**
     * The sequence number to use if probing with the JVB's SSRC.
     */
    private int seqNum = new Random().nextInt(0xFFFF);

    /**
     * The RTP timestamp to use if probing with the JVB's SSRC.
     */
    private long ts = new Random().nextInt() & 0xFFFFFFFFL;

    /**
     * Ctor.
     *
     * @param dest the {@link VideoChannel} to probe for available send
     * bandwidth.
     */
    public BandwidthProbing(VideoChannel dest)
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

        MediaStream destStream = dest.getStream();
        if (destStream == null
            || (destStream.getDirection() != null
                && !destStream.getDirection().allowsSending())
            || !(destStream instanceof VideoMediaStreamImpl))
        {
            return;
        }

        VideoMediaStreamImpl videoStreamImpl
            = (VideoMediaStreamImpl) destStream;

        List<AdaptiveTrackProjection> adaptiveTrackProjectionList
            = dest.getBitrateController().getAdaptiveTrackProjections();

        if (adaptiveTrackProjectionList == null || adaptiveTrackProjectionList.isEmpty())
        {
            return;
        }

        // We calculate how much to probe for based on the total target bps
        // (what we're able to reach), the total ideal bps (what we want to
        // be able to reach) and the total current bps (what we currently send).

        long totalTargetBps = 0, totalIdealBps = 0;

        List<Long> ssrcsToProtect = new ArrayList<>();
        for (AdaptiveTrackProjection
            adaptiveTrackProjection : adaptiveTrackProjectionList)
        {
            MediaStreamTrackDesc sourceTrack
                = adaptiveTrackProjection.getSource();
            if (sourceTrack == null)
            {
                continue;
            }

            long targetBps = sourceTrack.getBps(
                adaptiveTrackProjection.getTargetIndex());
            if (targetBps > 0)
            {
                // Do not protect SSRC if it's not streaming.
                long ssrc = adaptiveTrackProjection.getSSRC();
                if (ssrc > -1)
                {
                    ssrcsToProtect.add(ssrc);
                }
            }

            totalTargetBps += targetBps;
            totalIdealBps += sourceTrack.getBps(
                    adaptiveTrackProjection.getIdealIndex());
        }

        // How much padding do we need?
        long totalNeededBps = totalIdealBps - totalTargetBps;
        if (totalNeededBps < 1)
        {
            // Not much.
            return;
        }

        long bweBps = videoStreamImpl
            .getOrCreateBandwidthEstimator().getLatestEstimate();

        if (totalIdealBps <= bweBps)
        {
            // it seems like the ideal bps fits in the bandwidth estimation,
            // let's update the bitrate controller.
            dest.getBitrateController().update(bweBps);
            return;
        }

        // How much padding can we afford?
        long maxPaddingBps = bweBps - totalTargetBps;
        long paddingBps = Math.min(totalNeededBps, maxPaddingBps);

        if (timeSeriesLogger.isTraceEnabled())
        {
            DiagnosticContext diagnosticContext
                = videoStreamImpl.getDiagnosticContext();
            timeSeriesLogger.trace(diagnosticContext
                    .makeTimeSeriesPoint("sent_padding")
                    .addField("padding_bps", paddingBps)
                    .addField("total_ideal_bps", totalIdealBps)
                    .addField("total_target_bps", totalTargetBps)
                    .addField("needed_bps", totalNeededBps)
                    .addField("max_padding_bps", maxPaddingBps)
                    .addField("bwe_bps", bweBps));
        }

        if (paddingBps < 1)
        {
            // Not much.
            return;
        }


        MediaStreamImpl stream = (MediaStreamImpl) destStream;

        // XXX a signed int is practically sufficient, as it can represent up to
        // ~ 2GB
        int bytes = (int) (PADDING_PERIOD_MS * paddingBps / 1000 / 8);
        RtxTransformer rtxTransformer = stream.getRtxTransformer();

        if (!DISABLE_RTX_PROBING)
        {
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
        }

        // Send crap with the JVB's SSRC.
        long mediaSSRC = getSenderSSRC();
        if (vp8PT == -1)
        {
            vp8PT = stream.getDynamicRTPPayloadType(Constants.VP8);
            if (vp8PT == -1)
            {
                logger.warn("The VP8 payload type is undefined. Failed to "
                        + "probe with the SSRC of the bridge.");
                return;
            }
        }

        ts += 3000;

        int pktLen = RawPacket.FIXED_HEADER_SIZE + 0xFF;
        int len = (bytes / pktLen) + 1 /* account for the mod */;

        for (int i = 0; i < len; i++)
        {
            try
            {
                // These packets should not be cached.
                RawPacket pkt
                    = RawPacket.makeRTP(mediaSSRC, vp8PT, seqNum++, ts, pktLen);

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
     * @return get the local SSRC that will be used in the media sender SSRC
     * field of the RTCP reports.
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
