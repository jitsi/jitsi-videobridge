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
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.util.concurrent.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The {@link BitrateController} is attached to a destination
 * {@link VideoChannel} and its purpose is to filter incoming packets based on
 * their source {@link VideoChannel} and modify RTCP packets. This task is
 * actually delegated to the individual {@link SimulcastController} of each
 * media stream track.
 *
 * @author George Politis
 */
public class BitrateController
    extends PeriodicRunnable
    implements TransformEngine
{
    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger = Logger.getLogger(BitrateController.class);
    
    /**
     * the interval/period in milliseconds at which {@link #run()} is to be
     * invoked.
     */
    private static final long PADDING_PERIOD_MS = 15;

    /**
     * The name of the property used to disable LastN notifications.
     */
    public static final String DISABLE_LASTN_NOTIFICATIONS_PNAME
        = "org.jitsi.videobridge.DISABLE_LASTN_NOTIFICATIONS";

    /**
     * The name of the property used to trust bandwidth estimations.
     */
    public static final String TRUST_BWE_PNAME
        = "org.jitsi.videobridge.TRUST_BWE";

    /**
     * An empty list instance.
     */
    private static final Set<String> INITIAL_EMPTY_SET
        = Collections.unmodifiableSet(new HashSet<String>(0));

    /**
     * The {@link VideoChannel} which owns this {@link BitrateController}.
     */
    private final VideoChannel dest;

    /**
     * The bitrate controllers for all SSRCs that this instance has seen.
     */
    private final Map<Long, SimulcastController>
        ssrcToBitrateController = new ConcurrentHashMap<>();

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    private final PacketTransformer rtpTransformer = new RTPTransformer();

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTCP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    private final PacketTransformer rtcpTransformer = new RTCPTransformer();

    /**
     * The {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs. Required for backwards compatibility with
     * existing LastN code.
     */
    private Set<String> forwardedEndpointIds = INITIAL_EMPTY_SET;

    /**
     * A boolean that indicates whether or not we should send data channel
     * notifications to the endpoint about changes in the endpoints that it
     * receives.
     */
    private final boolean disableLastNNotifications;

    /**
     * A boolean that indicates whether or not we should trust the bandwidth
     * estimations. If this is se to false, then we assume a bandwidth
     * estimation of Long.MAX_VALUE.
     */
    private final boolean trustBwe;

    /**
     * The current padding budget for {@link #dest}.
     */
    private PaddingParams paddingParams;

    /**
     * Initializes a new {@link BitrateController} instance which is to
     * belong to a particular {@link VideoChannel}.
     *
     * @param dest the {@link VideoChannel} that owns this instance.
     */
    BitrateController(VideoChannel dest)
    {
        super(PADDING_PERIOD_MS);
        this.dest = dest;

        ConfigurationService cfg = LibJitsi.getConfigurationService();
        disableLastNNotifications = cfg != null
            && cfg.getBoolean(DISABLE_LASTN_NOTIFICATIONS_PNAME, false);

        trustBwe = cfg != null && cfg.getBoolean(TRUST_BWE_PNAME, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return rtpTransformer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return rtcpTransformer;
    }

    /**
     * Defines a packet filter that controls which RTP packets to be written
     * into the {@link Channel} that owns this {@link BitrateController}.
     *
     * @param buf the <tt>byte</tt> array that holds the packet.
     * @param off the offset in <tt>buffer</tt> at which the actual data begins.
     * @param len the number of <tt>byte</tt>s in <tt>buffer</tt> which
     * constitute the actual data.
     * @return <tt>true</tt> to allow the specified packet/<tt>buffer</tt> to be
     * written into the {@link Channel} that owns this {@link BitrateController}
     * ; otherwise, <tt>false</tt>
     */
    public boolean accept(byte[] buf, int off, int len)
    {
        long ssrc = RawPacket.getSSRCAsLong(buf, off, len);
        if (ssrc < 0)
        {
            return false;
        }

        SimulcastController simulcastController
            = ssrcToBitrateController.get(ssrc);

        return simulcastController != null
            && simulcastController.accept(buf, off, len);
    }

    /**
     * Computes a new bitrate allocation for every endpoint in the conference,
     * and updates the state of this instance so that bitrate allocation is
     * eventually met.
     *
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @param bweBps the current bandwidth estimation (in bps).
     */
    public void update(List<Endpoint> conferenceEndpoints, long bweBps)
    {
        // Gather the conference allocation input.
        if (conferenceEndpoints == null)
        {
            conferenceEndpoints
                = dest.getConferenceSpeechActivity().getEndpoints();
        }
        else
        {
            // Create a copy as we may modify the list in the prioritize method.
            conferenceEndpoints = new ArrayList<>(conferenceEndpoints);
        }

        if (bweBps == -1 && trustBwe)
        {
            bweBps = ((VideoMediaStream) dest.getStream())
                .getOrCreateBandwidthEstimator().getLatestEstimate();
        }

        if (bweBps < 0 || !trustBwe)
        {
            bweBps = Long.MAX_VALUE;
        }

        // Compute the bitrate allocation.
        EndpointBitrateAllocation[]
            allocations = allocate(bweBps, conferenceEndpoints);

        Set<String> oldForwardedEndpointIds = forwardedEndpointIds;

        Set<String> newForwardedEndpointIds = new HashSet<>();
        Set<String> endpointsEnteringLastNIds = new HashSet<>();
        Set<String> conferenceEndpointIds = new HashSet<>();

        PaddingParams paddingParams = new PaddingParams();
        long optimalBps = 0, mediaBps = 0;
        if (!ArrayUtils.isNullOrEmpty(allocations))
        {
            paddingParams.ssrc = allocations[0].targetSSRC & 0xFFFFFFFFL;

            for (EndpointBitrateAllocation allocation : allocations)
            {
                optimalBps += allocation.getOptimalBitrate();
                mediaBps += allocation.getTargetBitrate();

                conferenceEndpointIds.add(allocation.endpointID);

                int ssrc = allocation.targetSSRC,
                    targetIdx = allocation.targetIdx;

                // Review this.
                SimulcastController ctrl;
                synchronized (ssrcToBitrateController)
                {
                    ctrl = ssrcToBitrateController.get(ssrc & 0xFFFFFFFFL);
                    if (ctrl == null && allocation.track != null)
                    {
                        ctrl = new SimulcastController(allocation.track);

                        RTPEncodingDesc[] rtpEncodings
                            = allocation.track.getRTPEncodings();

                        // Route all encodings to the specified bitrate
                        // controller.
                        for (RTPEncodingDesc rtpEncoding : rtpEncodings)
                        {
                            ssrcToBitrateController.put(
                                rtpEncoding.getPrimarySSRC(), ctrl);

                            if (rtpEncoding.getRTXSSRC() != -1)
                            {
                                ssrcToBitrateController.put(
                                    rtpEncoding.getRTXSSRC(), ctrl);
                            }
                        }
                    }
                }

                if (ctrl != null)
                {
                    ctrl.update(targetIdx);
                }

                if (targetIdx > -1)
                {
                    newForwardedEndpointIds.add(allocation.endpointID);
                    if (!oldForwardedEndpointIds.contains(allocation.endpointID))
                    {
                        endpointsEnteringLastNIds.add(allocation.endpointID);
                    }
                }
            }
        }
        else
        {
            for (SimulcastController simulcastController
                : ssrcToBitrateController.values())
            {
                simulcastController.update(-1);
            }
        }

        long availablePaddingBps = bweBps - mediaBps;
        paddingParams.bps = Math.min(optimalBps - mediaBps, availablePaddingBps);
        assert paddingParams.bps >= 0;
        this.paddingParams = paddingParams;

        if (logger.isInfoEnabled())
        {
            logger.info("bitrate_ctrl" +
                ",stream=" + dest.getStream().hashCode() +
                " media_bps=" + mediaBps +
                ",padding_ssrc=" + paddingParams.ssrc +
                ",padding_bps=" + paddingParams.bps +
                ",bwe_bps=" + bweBps);
        }

        if (!disableLastNNotifications
            && !newForwardedEndpointIds.equals(oldForwardedEndpointIds))
        {
            dest.sendLastNEndpointsChangeEventOnDataChannel(
                newForwardedEndpointIds,
                endpointsEnteringLastNIds,
                conferenceEndpointIds);
        }

        this.forwardedEndpointIds = newForwardedEndpointIds;
    }

    /**
     * Computes the optimal and the target bitrate, limiting the target to be
     * less than bandwidth estimation specified as an argument.
     *
     * @param maxBandwidth the max bandwidth estimation that the target bitrate
     * must not exceed.
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performaance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @return an array of {@link EndpointBitrateAllocation}.
     */
    private EndpointBitrateAllocation[] allocate(
        long maxBandwidth, List<Endpoint> conferenceEndpoints)
    {
        EndpointBitrateAllocation[]
            endpointBitrateAllocations = prioritize(conferenceEndpoints);

        if (ArrayUtils.isNullOrEmpty(endpointBitrateAllocations))
        {
            return endpointBitrateAllocations;
        }

        int maxQuality = 0;
        long oldMaxBandwidth = 0;
        while (oldMaxBandwidth != maxBandwidth)
        {
            oldMaxBandwidth = maxBandwidth;

            for (EndpointBitrateAllocation endpointBitrateAllocation
                : endpointBitrateAllocations)
            {
                maxBandwidth += endpointBitrateAllocation.getTargetBitrate();
                endpointBitrateAllocation.allocate(maxBandwidth, maxQuality);
                maxBandwidth -= endpointBitrateAllocation.getTargetBitrate();
            }

            maxQuality++;
        }

        return endpointBitrateAllocations;
    }

    /**
     * Returns a prioritized {@link EndpointBitrateAllocation} array where
     * selected endpoint are at the top of the array, followed by the pinned
     * endpoints, finally followed by any other remaining endpoints. The
     * priority respects the order induced by the <tt>conferenceEndpoints</tt>
     * parameter.
     *
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performaance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @return a prioritized {@link EndpointBitrateAllocation} array where
     * selected endpoint are at the top of the array, followed by the pinned
     * endpoints, finally followed by any other remaining endpoints.
     */
    private EndpointBitrateAllocation[] prioritize(
        List<Endpoint> conferenceEndpoints)
    {
        if (dest.isExpired())
        {
            return null;
        }

        Endpoint destEndpoint = dest.getEndpoint();
        if (destEndpoint == null || destEndpoint.isExpired())
        {
            return null;
        }

        // Init.
        int szConference = conferenceEndpoints.size();

        // subtract 1 for destEndpoint.
        EndpointBitrateAllocation[] endpointBitrateAllocations
            = new EndpointBitrateAllocation[szConference - 1];

        int lastN = dest.getLastN();
        if (lastN < 0)
        {
            // If lastN is disable, pretend lastN == szConference.
            lastN = endpointBitrateAllocations.length;
        }
        else
        {
            // If lastN is enabled, pretend lastN at most as big as the size
            // of the conference.
            lastN = Math.min(lastN, endpointBitrateAllocations.length);
        }

        int priority = 0;

        // First, bubble-up the selected endpoints (whoever's on-stage needs to
        // be visible).
        Set<String> selectedEndpoints = destEndpoint.getSelectedEndpoints();
        if (!selectedEndpoints.isEmpty())
        {
            Iterator<Endpoint> it = conferenceEndpoints.iterator();
            while (it.hasNext() && priority < lastN)
            {
                Endpoint sourceEndpoint = it.next();
                if (sourceEndpoint == destEndpoint
                    || !selectedEndpoints.contains(sourceEndpoint.getID()))
                {
                    continue;
                }

                endpointBitrateAllocations[priority++]
                    = new EndpointBitrateAllocation(
                    sourceEndpoint,
                    true /* forwarded */,
                    true /* selected */);

                it.remove();
            }
        }

        // Then, bubble-up the pinned endpoints.
        Set<String> pinnedEndpoints = destEndpoint.getPinnedEndpoints();
        if (!pinnedEndpoints.isEmpty())
        {
            Iterator<Endpoint> it = conferenceEndpoints.iterator();
            while (it.hasNext() && priority < lastN)
            {
                Endpoint sourceEndpoint = it.next();
                if (sourceEndpoint == destEndpoint
                    || !pinnedEndpoints.contains(sourceEndpoint.getID()))
                {
                    continue;
                }

                endpointBitrateAllocations[priority++]
                    = new EndpointBitrateAllocation(
                    sourceEndpoint,
                    true /* forwarded */,
                    false /* selected */);

                it.remove();
            }
        }

        // Finally, deal with any remaining endpoints.
        if (!conferenceEndpoints.isEmpty())
        {
            for (Endpoint sourceEndpoint : conferenceEndpoints)
            {
                if (sourceEndpoint == destEndpoint)
                {
                    continue;
                }

                boolean forwarded = priority < lastN;

                endpointBitrateAllocations[priority++]
                    = new EndpointBitrateAllocation(
                    sourceEndpoint, forwarded, false /* selected */);
            }
        }

        return endpointBitrateAllocations;
    }

    /**
     * A bitrate allocation that pertains to a specific {@link Endpoint}.
     *
     * @author George Politis
     */
    private class EndpointBitrateAllocation
    {
        /**
         * The ID of the {@link Endpoint} that this instance pertains to.
         */
        private final String endpointID;

        /**
         * Indicates whether this {@link Endpoint} is forwarded or not to the
         * {@link VideoChannel} that owns this {@link BitrateController}.
         */
        private final boolean forwarded;

        /**
         * Indicates whether this {@link Endpoint} is on-stage/selected or not
         * at the {@link VideoChannel} that owns this {@link BitrateController}.
         */
        private final boolean selected;

        /**
         * Helper field that keeps the SSRC of the target stream.
         */
        private final int targetSSRC;

        /**
         * The first {@link MediaStreamTrackDesc} of the {@link Endpoint} that
         * this instance pertains to.
         */
        private final MediaStreamTrackDesc track;

        /**
         * An array that holds the stable bitrates of the
         * {@link RTPEncodingDesc} of {@link #track}.
         */
        private final long[] rates;

        /**
         * The target subjective quality index for this bitrate allocation.
         */
        private int targetIdx = -1;

        /**
         * The optimal subjective quality index for this bitrate allocation.
         */
        private final int optimalIdx;

        /**
         * Ctor.
         *
         * @param endpoint the {@link Endpoint} that this bitrate allocation
         * pertains to.
         * @param forwarded a flag indicating whether or not the endpoint is in
         * LastN.
         * @param selected a flag indicating whether or not the endpoint is
         * selected.
         */
        private EndpointBitrateAllocation(
            Endpoint endpoint, boolean forwarded, boolean selected)
        {
            this.endpointID = endpoint.getID();
            this.selected = selected;
            this.forwarded = forwarded;

            // This assumes that the array is ordered by the subjective quality
            // ordering ex: one can argue that 360p@30fps looks better than
            // 720p@7.5fps.
            MediaStreamTrackDesc[] tracks
                = endpoint.getMediaStreamTracks(MediaType.VIDEO);

            if (ArrayUtils.isNullOrEmpty(tracks))
            {
                rates = new long[0];
                targetSSRC = -1;
                optimalIdx = -1;
                track = null;
                return;
            }

            RTPEncodingDesc[] encodings = tracks[0].getRTPEncodings();

            if (ArrayUtils.isNullOrEmpty(encodings))
            {
                rates = new long[0];
                targetSSRC = -1;
                optimalIdx = -1;
                track = null;
                return;
            }

            targetSSRC = (int) encodings[0].getPrimarySSRC();
            track = tracks[0];

            // Initialize rates.
            rates = new long[encodings.length];
            int highIdx = 0;
            for (int i = 0; i < encodings.length; i++)
            {
                rates[i] = encodings[i].getLastStableBitrateBps();
                if (rates[i] > 0)
                {
                    highIdx = i;
                }
            }

            optimalIdx = selected ? highIdx : 0;
        }

        /**
         * Computes the optimal and the target bitrate, limiting the target to
         * be less than bandwidth estimation specified as an argument.
         *
         * @param maxBps the maximum bitrate (in bps) that the target subjective
         * quality can have.
         * @param maxQuality the maximum subjective quality index that the
         * target subjective quality can have. -1 suspends the track.
         */
        void allocate(long maxBps, int maxQuality)
        {
            if (!forwarded || rates.length == 0)
            {
                return;
            }

            maxQuality = selected ? Math.min(maxQuality, rates.length - 1) : 0;

            for (int i = maxQuality; i >= 0; i--)
            {
                if (maxBps >= rates[i])
                {
                    targetIdx = i;
                    break;
                }
            }
        }

        /**
         * Gets the target bitrate (in bps) for this endpoint allocation.
         *
         * @return the target bitrate (in bps) for this endpoint allocation.
         */
        long getTargetBitrate()
        {
            return targetIdx == -1 ? 0 : rates[targetIdx];
        }


        /**
         * Gets the optimal bitrate (in bps) for this endpoint allocation.
         *
         * @return the optimal bitrate (in bps) for this endpoint allocation.
         */
        long getOptimalBitrate()
        {
            return optimalIdx >= 0 ? rates[optimalIdx] : 0;
        }
    }

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    private class RTPTransformer
        implements PacketTransformer
    {
        /**
         * {@inheritDoc}
         */
        @Override
        public void close()
        {
            // TODO decrease counters.
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] reverseTransform(RawPacket[] pkts)
        {
            return pkts;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket[] transform(RawPacket[] pkts)
        {
            if (ArrayUtils.isNullOrEmpty(pkts))
            {
                return pkts;
            }

            RawPacket[] extras = null;
            for (int i = 0; i < pkts.length; i++)
            {
                if (!RTPPacketPredicate.INSTANCE.test(pkts[i]))
                {
                    continue;
                }

                long ssrc = pkts[i].getSSRCAsLong();

                SimulcastController subCtrl = ssrcToBitrateController.get(ssrc);

                // FIXME properly support unannounced SSRCs.
                RawPacket[] ret
                    = subCtrl == null ? null : subCtrl.rtpTransform(pkts[i]);

                if (ArrayUtils.isNullOrEmpty(ret))
                {
                    continue;
                }

                pkts[i] = ret[0];
                if (ret.length > 1)
                {
                    int extrasLen
                        = ArrayUtils.isNullOrEmpty(extras) ? 0 : extras.length;

                    RawPacket[] newExtras
                        = new RawPacket[extrasLen + ret.length - 1];

                    System.arraycopy(
                        ret, 1, newExtras, extrasLen, ret.length - 1);

                    if (extrasLen > 0)
                    {
                        System.arraycopy(extras, 0, newExtras, 0, extrasLen);
                    }

                    extras = newExtras;
                }
            }

            return ArrayUtils.concat(pkts, extras);
        }
    }

    /**
     * The {@link PacketTransformer} that handles incoming/outgoing RTCP
     * packets for this {@link BitrateController} instance. Internally,
     * it delegates this responsibility to the appropriate media stream track
     * bitrate controller ({@link SimulcastController}, etc).
     */
    private class RTCPTransformer
        extends SinglePacketTransformerAdapter
    {
        /**
         * Ctor.
         */
        RTCPTransformer()
        {
            super(RTCPPacketPredicate.INSTANCE);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public RawPacket transform(RawPacket pkt)
        {
            long ssrc = pkt.getRTCPSSRC();
            if (ssrc < 0)
            {
                return pkt;
            }

            SimulcastController subCtrl
                = ssrcToBitrateController.get(ssrc);

            return subCtrl == null ? pkt : subCtrl.rtcpTransform(pkt);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run()
    {
        super.run();

        PaddingParams paddingParams = this.paddingParams;
        if (paddingParams == null)
        {
            return;
        }

        MediaStreamImpl stream = (MediaStreamImpl) dest.getStream();
        RtxTransformer rtxTransformer = stream.getRtxTransformer();

        long bytes = PADDING_PERIOD_MS * paddingParams.bps / 1000 / 8;

        // Prioritize on stage participant protection.
        rtxTransformer.sendPadding(paddingParams.ssrc, bytes);
    }

    /**
     * Helper class that holds the padding parameters.
     */
    class PaddingParams
    {
        /**
         * The SSRC to protect.
         */
        long ssrc;

        /**
         * The padding bitrate for in bps.
         */
        long bps;
    }
}
