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
package org.jitsi.videobridge.ratecontrol;

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import java.lang.ref.*;
import java.util.*;

/**
 * @author George Politis
 */
public class ConferenceAllocation
{
    /**
     * The {@link Logger} used by the {@link ConferenceAllocation} class to
     * print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(ConferenceAllocation.class);

    /**
     * The {@link VideoChannel} which owns this {@link LastNController}.
     */
    private final RtpChannel dstChannel;

    /**
     * The collection of IDs of the {@link Endpoint}s that are forwarded.
     */
    private final Collection<String> forwardedEndpoints;

    /**
     * The collection of IDs of the {@link Endpoint}s that are to be optimized.
     */
    private final Collection<String> optimizedEndpoints;

    /**
     * The available bandwidth (in bps) to allocate
     */
    private final long bweBps;

    /**
     * The required bitrate that is required to stream the "good stuff".
     */
    private long optimalBitrateBps;

    /**
     * The target bitrate of this bitrate allocation.
     */
    private long targetBitrateBps;

    /**
     * The computed target.
     */
    Map<String, Map<String, WeakReference<RTPEncodingImpl>>> target;

    /**
     * A boolean that indicates whether or not this allocation can be further
     * optimized.
     */
    private boolean canOptimize = true;

    /**
     *
     */
    private Collection<EndpointAllocation> allocations;

    /**
     * Ctor.
     *
     * @param dstChannel
     * @param forwardedEndpoints
     * @param selectedEndpoints
     * @param lastBweBps
     */
    public ConferenceAllocation(
        RtpChannel dstChannel,
        Collection<String> forwardedEndpoints,
        Collection<String> selectedEndpoints,
        long lastBweBps)
    {
        this.dstChannel = dstChannel;
        this.forwardedEndpoints = forwardedEndpoints;
        this.optimizedEndpoints = selectedEndpoints;
        this.bweBps = lastBweBps;
        this.canOptimize = forwardedEndpoints != null
            && !forwardedEndpoints.isEmpty();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "conference_allocation" +
            ",stream_hash=" + dstChannel.getStream().hashCode() +
            " target_bps=" + targetBitrateBps +
            ",optimal_bps=" + optimalBitrateBps;
    }

    /**
     * Gets the required bitrate that is required to stream the "good stuff".
     */
    public long getOptimalBitrateBps()
    {
        return optimalBitrateBps;
    }

    /**
     *
     * @return
     */
    private boolean optimize()
    {
        if (!canOptimize)
        {
            return canOptimize;
        }

        canOptimize = false;

        if (allocations == null)
        {
            Collection<Endpoint> endpoints
                = dstChannel.getContent().getConference().getEndpoints();
            allocations = new ArrayList<>(endpoints.size());

            for (Endpoint endpoint : endpoints)
            {
                EndpointAllocation balloc = new EndpointAllocation(endpoint);

                allocations.add(balloc);

                if (balloc.optimize())
                {
                    canOptimize = true;
                }
            }

            return canOptimize;
        }
        else
        {
            for (EndpointAllocation balloc : allocations)
            {
                if (balloc.canOptimize && balloc.optimize())
                {
                    canOptimize = true;
                }
            }

            return canOptimize;
        }
    }

    public Map<String, Map<String, WeakReference<RTPEncodingImpl>>> getTarget()
    {
        return target;
    }

    public void compute()
    {
        while (canOptimize && optimize())
        {
        }

        target = new TreeMap<>();

        if (allocations == null || allocations.isEmpty())
        {
            return;
        }

        if (logger.isDebugEnabled())
        {
            logger.debug(this);
        }

        for (EndpointAllocation endpointAllocation : allocations)
        {
            for (ChannelAllocation channelAllocation : endpointAllocation.allocations)
            {
                Map<String, WeakReference<RTPEncodingImpl>> channelTarget = new TreeMap<>();
                target.put(channelAllocation.srcChannel.getID(), channelTarget);
                for (TrackAllocation trackAllocation : channelAllocation.allocations)
                {
                    channelTarget.put(trackAllocation.track.getMediaStreamTrackId(), new WeakReference<>(trackAllocation.targetEncoding));

                    if (logger.isDebugEnabled())
                    {
                        logger.debug(trackAllocation);
                    }
                }
            }
        }
    }

    /**
     *
     */
    private class EndpointAllocation
    {
        /**
         *
         */
        private final Endpoint endpoint;

        /**
         *
         */
        private Collection<ChannelAllocation> allocations;

        /**
         *
         */
        public boolean canOptimize = true;

        /**
         *
         * @param endpoint
         */
        public EndpointAllocation(Endpoint endpoint)
        {
            this.endpoint = endpoint;
        }

        /**
         */
        private boolean optimize()
        {
            if (!canOptimize)
            {
                return canOptimize;
            }

            canOptimize = false;

            if (allocations == null)
            {
                allocations = new ArrayList<>();

                if (endpoint == null || endpoint.isExpired())
                {
                    return canOptimize;
                }

                if (!forwardedEndpoints.contains(endpoint.getID()))
                {
                    return canOptimize;
                }

                Collection<RtpChannel> srcChannels
                    = endpoint.getChannels(MediaType.VIDEO);

                if (srcChannels == null || srcChannels.isEmpty())
                {
                    return canOptimize;
                }

                for (RtpChannel srcChannel : srcChannels)
                {
                    ChannelAllocation alloc
                        = new ChannelAllocation(srcChannel);
                    allocations.add(alloc);

                    if (alloc.optimize())
                    {
                        canOptimize = true;
                    }
                }

                return canOptimize;
            }
            else
            {
                for (ChannelAllocation balloc : allocations)
                {
                    if (balloc.canOptimize && balloc.optimize())
                    {
                        canOptimize = true;
                    }
                }

                return canOptimize;
            }
        }
    }

    /**
     *
     */
    private class ChannelAllocation
    {
        /**
         *
         */
        private final RtpChannel srcChannel;

        /**
         *
         */
        public boolean canOptimize = true;

        /**
         *
         */
        private Collection<TrackAllocation> allocations;

        /**
         *
         * @param srcChannel
         */
        public ChannelAllocation(RtpChannel srcChannel)
        {
            this.srcChannel = srcChannel;
        }

        /**
         */
        public boolean optimize()
        {
            if (!canOptimize)
            {
                return canOptimize;
            }

            canOptimize = false;

            if (allocations == null)
            {
                allocations = new ArrayList<>();

                if (srcChannel == null || srcChannel.isExpired())
                {
                    return false;
                }

                MediaStreamTrackReceiver
                    receiver = srcChannel.getMediaStreamTrackReceiver();
                if (receiver == null)
                {
                    return false;
                }

                MediaStreamTrackImpl[] tracks = receiver.getMediaStreamTracks();
                if (tracks == null || tracks.length == 0)
                {
                    return false;
                }

                for (MediaStreamTrackImpl track : tracks)
                {
                    TrackAllocation balloc = new TrackAllocation(track);
                    allocations.add(balloc);
                    if (balloc.optimize())
                    {
                        canOptimize = true;
                    }
                }

                return canOptimize;
            }
            else
            {
                for (TrackAllocation balloc : allocations)
                {
                    if (balloc.optimizeMore && balloc.optimize())
                    {
                        canOptimize = true;
                    }
                }

                return canOptimize;
            }
        }
    }

    /**
     *
     */
    private class TrackAllocation
    {
        /**
         *
         */
        private final MediaStreamTrackImpl track;

        /**
         *
         */
        private RTPEncodingImpl targetEncoding;

        /**
         *
         */
        private long targetEncodingBps;

        /**
         *
         */
        private RTPEncodingImpl optimalEncoding;

        /**
         *
         */
        private long optimalEncodingBps;

        /**
         *
         */
        private long[] rates;

        /**
         *
         */
        public boolean optimizeMore = true;

        /**
         * Ctor.
         * @param track
         */
        public TrackAllocation(MediaStreamTrackImpl track)
        {
            this.track = track;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString()
        {
            return "track_allocation" +
                ",stream_hash=" + dstChannel.getStream().hashCode() +
                " target_bps=" + targetBitrateBps +
                ",optimal_bps=" + optimalBitrateBps +
                ",optimal_encoding_id=" + optimalEncoding.getEncodingId() +
                ",target_encoding_id=" + targetEncoding.getEncodingId();
        }

        /**
         *
         * @return
         */
        public boolean optimize()
        {
            if (!optimizeMore)
            {
                return optimizeMore;
            }

            optimizeMore = false;

            // This assumes that the array is ordered by the subjective quality
            // ordering ex: one can argue that 360p@30fps looks better than
            // 720p@7.5fps.
            RTPEncodingImpl[] encodings = track.getRTPEncodings();
            if (encodings == null || encodings.length == 0)
            {
                return optimizeMore;
            }

            // Initialize rates.
            if (rates == null)
            {
                rates = new long[encodings.length];
                for (int i = 0; i < encodings.length; i++)
                {
                    rates[i] = encodings[i].getLastStableBitrateBps();
                }
            }

            // Determine the optimal encoding. In future evolutions of this
            // class the optimal encoding can be set at the endpoint or at the
            // channel level. Say for example that an endpoint is streaming 2
            // tracks (video+screensharing). One approach would be to first
            // optimize the screensharing and then the video.
            if (optimalEncoding == null)
            {
                // Determine the optimal encoding and the optimal bitrate.
                boolean hq = optimizedEndpoints.contains(track
                    .getMediaStreamTrackReceiver().getChannel()
                    .getEndpoint().getID());

                int optimalIdx = hq ? encodings.length - 1 : 0;

                optimalEncoding = encodings[optimalIdx];
                optimalEncodingBps = rates[optimalIdx];
                optimalBitrateBps += optimalEncodingBps;
            }

            // Determine the target with incremental steps that approach
            // the optimal encoding.
            if (targetEncoding == null)
            {
                // Start low, optimize at a later steps.

                long remainingBps = bweBps - targetBitrateBps;
                if (remainingBps > rates[0])
                {
                    // We have enough bandwidth for the encoding that consumes
                    // the least bandwidth.
                    targetEncoding = encodings[0];
                    targetEncodingBps = rates[0];
                    targetBitrateBps += rates[0];
                }
                else if (logger.isDebugEnabled())
                {
                    logger.debug("suspending_track" +
                        ",stream_hash=" + dstChannel.getStream().hashCode() +
                        " track_id=" + track.getMediaStreamTrackId());
                }
            }
            else if (targetEncoding != optimalEncoding)
            {
                // Try to optimize. Calculate the remaining bandwidth.
                long remainingBps = bweBps - targetBitrateBps + targetEncodingBps;

                for (int i = encodings.length - 1; i >= 0; i--)
                {
                    if (remainingBps > rates[i])
                    {
                        targetBitrateBps
                            = targetBitrateBps - targetEncodingBps + rates[i];
                        targetEncoding = encodings[i];
                        targetEncodingBps = rates[i];
                        break;
                    }
                }
            }

            boolean needsOptimization = targetEncoding != optimalEncoding;
            boolean canOptimizeMore
                = bweBps > targetBitrateBps - targetEncodingBps + optimalEncodingBps;

            optimizeMore = needsOptimization && canOptimizeMore;
            return optimizeMore;
        }
    }
}
