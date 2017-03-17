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
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * The {@link BitrateController} is attached to a destination {@link
 * VideoChannel} and its purpose is 1st to selectively drop incoming packets
 * based on their source {@link VideoChannel} and 2nd to rewrite the accepted
 * packets so that they form a correct RTP stream without gaps in their
 * sequence numbers nor in their timestamps.
 *
 * In the Selective Forwarding Middlebox (SFM) topology [RFC7667], senders are
 * sending multiple bitstreams of (they multistream) the same video source
 * (using either with simulcast or scalable video coding) and the SFM decides
 * which bitstream to send to which receiver.
 *
 * For example, suppose we're in a 3-way simulcast-enabled video call (so 3
 * source {@link VideoChannel}s) where the endpoints are sending two
 * non-scalable RTP streams of the same video source. The bitrate controller,
 * will chose to forward only one RTP stream at a time to a specific destination
 * endpoint.
 *
 * With scalable video coding (SVC) a bitstream can be broken down into multiple
 * sub-bitstreams of lower frame rate (that we call temporal layers or TL)
 * and/or lower resolution (that we call spatial layers or SL). The exact
 * dependencies between the different layers of an SVC bitstream are codec
 * specific but, as a general rule of thumb higher temporal/spatial layers
 * depend on lower temporal/spatial layers creating a DAG of dependencies.
 *
 * For example, a 720p@30fps VP8 scalable bitstream can be broken down into 3
 * sub-bitstreams: one 720p@30fps layer (the highest temporal layer), that
 * depends on a 720p@15fps layer that depends on a 720p7.5fps layer (the lowest
 * temporal layer). In order for the decoder to be able decode the highest
 * temporal layer, it needs to get all the packets of its direct and transitive
 * dependencies, so of both the other two layers. In this simple case we have a
 * linked list of dependencies with the the lowest temporal layer as root and
 * the highest temporal layer as a leaf.
 *
 * In order for the SFM to be able to filter the incoming packets we need to be
 * able to assign incoming packets to "flows" with properties that allow us to
 * define filtering rules. In this implementation a flow is represented by an
 * {@link RTPEncodingDesc} and its properties are bitrate and subjective quality
 * index. Specifically, the incoming packets belong to some {@link FrameDesc},
 * which belongs to some {@link RTPEncodingDesc}, which belongs to some {@link
 * MediaStreamTrackDesc}, which belongs to the source {@link VideoChannel}.
 * This hierarchy allows for fine-grained filtering up to the {@link FrameDesc}
 * level.
 *
 * The decision of whether to accept or drop a specific RTP packet of a
 * specific {@link FrameDesc} of a specific {@link MediaStreamTrackDesc}
 * depends on the bitrate allocation of the specific {@link
 * MediaStreamTrackDesc} and on the state of the bitstream that is produced by
 * this filter. For example, if we have a {@link MediaStreamTrackDesc} with 2
 * {@link RTPEncodingDesc} in a simulcast configuration, then we can switch
 * between the two {@link RTPEncodingDesc}s if it is mandated by the bitrate
 * allocation and only if we see a refresh point.
 *
 * @author George Politis
 */
public class BitrateController
    implements TransformEngine
{
    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger = Logger.getLogger(BitrateController.class);

    /**
     * The name of the property used to trust bandwidth estimations.
     */
    public static final String TRUST_BWE_PNAME
        = "org.jitsi.videobridge.TRUST_BWE";

    /**
     * An empty set of {@link String}s instance.
     */
    private static final Set<String> INITIAL_EMPTY_SET
        = Collections.unmodifiableSet(new HashSet<String>(0));

    /**
     * The {@link VideoChannel} which owns this {@link BitrateController} and
     * is the destination of the packets that this instance accepts.
     */
    private final VideoChannel dest;

    /**
     * The {@link SimulcastController}s that this instance is mananaging. A
     * {@link SimulcastController} is 
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
     * A boolean that indicates whether or not we should trust the bandwidth
     * estimations. If this is se to false, then we assume a bandwidth
     * estimation of Long.MAX_VALUE.
     */
    private final boolean trustBwe;

    /**
     * The time (in ms) when this instance first transformed any media. This
     * allows to ignore the CC during the early stages of the call and ramp up
     * the send rate faster.
     *
     * NOTE This is only meant to be as a temporary hack and ideally this should
     * be fixed in the CC.
     */
    private long firstMediaMs = -1;

    /**
     * The current padding parameters list for {@link #dest}.
     */
    private List<PaddingParams> paddingParamsList;

    /**
     * Initializes a new {@link BitrateController} instance which is to
     * belong to a particular {@link VideoChannel}.
     *
     * @param dest the {@link VideoChannel} that owns this instance.
     */
    BitrateController(VideoChannel dest)
    {
        this.dest = dest;

        ConfigurationService cfg = LibJitsi.getConfigurationService();

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
     * Gets the current padding parameters list for {@link #dest}.
     *
     * @return the current padding parameters list for {@link #dest}.
     */
    List<PaddingParams> getPaddingParamsList()
    {
        return paddingParamsList;
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

        MediaStream destStream = dest.getStream();
        BandwidthEstimator bwe = destStream == null ? null
            : ((VideoMediaStream) destStream).getOrCreateBandwidthEstimator();

        boolean trustBwe = this.trustBwe;
        if (trustBwe)
        {
            // Ignore the bandwidth estimations in the first 10 seconds because
            // the REMBs don't ramp up fast enough. This needs to go but it's
            // related to our GCC implementation that needs to be brought up to
            // speed.
            if (firstMediaMs == -1
                || System.currentTimeMillis() - firstMediaMs < 10000)
            {
                trustBwe = false;
            }
        }

        if (bwe != null && bweBps == -1 && trustBwe)
        {
            bweBps = bwe.getLatestEstimate();
        }

        if (bweBps < 0 || !trustBwe)
        {
            bweBps = Long.MAX_VALUE;
        }

        // Compute the bitrate allocation.
        EndpointBitrateAllocation[]
            allocations = allocate(bweBps, conferenceEndpoints);

        // Update the the controllers based on the allocation and send a
        // notification to the client the set of forwarded endpoints has
        // changed.
        Set<String> oldForwardedEndpointIds = forwardedEndpointIds;

        Set<String> newForwardedEndpointIds = new HashSet<>();
        Set<String> endpointsEnteringLastNIds = new HashSet<>();
        Set<String> conferenceEndpointIds = new HashSet<>();

        List<PaddingParams> simulcastControllers = new ArrayList<>();
        long targetBps = 0;
        if (!ArrayUtils.isNullOrEmpty(allocations))
        {
            for (EndpointBitrateAllocation allocation : allocations)
            {
                targetBps += allocation.getTargetBitrate();

                conferenceEndpointIds.add(allocation.endpointID);

                int ssrc = allocation.targetSSRC,
                    targetIdx = allocation.targetIdx,
                    optimalIdx = allocation.optimalIdx;

                // Review this.
                SimulcastController ctrl;
                synchronized (ssrcToBitrateController)
                {
                    ctrl = ssrcToBitrateController.get(ssrc & 0xFFFFFFFFL);
                    if (ctrl == null && allocation.track != null)
                    {
                        RTPEncodingDesc[] rtpEncodings
                            = allocation.track.getRTPEncodings();

                        ctrl = new SimulcastController(allocation.track);

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
                    simulcastControllers.add(ctrl);
                    ctrl.setTargetIndex(targetIdx);
                    ctrl.setOptimalIndex(optimalIdx);
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
                simulcastController.setTargetIndex(-1);
                simulcastController.setOptimalIndex(-1);
            }
        }

        // The BandwidthProber will pick this up.
        this.paddingParamsList = simulcastControllers;

        if (logger.isInfoEnabled() && destStream != null)
        {
            logger.info("bitrate_ctrl" +
                ",stream=" + destStream.hashCode() +
                " target_bps=" + targetBps +
                ",bwe_bps=" + bweBps);
        }

        if (logger.isDebugEnabled())
        {
            if (destStream != null && !ArrayUtils.isNullOrEmpty(allocations))
            {
                for (EndpointBitrateAllocation endpointBitrateAllocation
                    : allocations)
                {
                    logger.debug("endpoint_allocation" +
                        ",stream=" + destStream.hashCode()
                        + " endpoint_id=" + endpointBitrateAllocation.endpointID
                        + ",target_idx=" + endpointBitrateAllocation.targetIdx);
                }
            }
        }

        if (!newForwardedEndpointIds.equals(oldForwardedEndpointIds))
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
            for (Iterator<Endpoint> it = conferenceEndpoints.iterator();
                 it.hasNext() && priority < lastN;)
            {
                Endpoint sourceEndpoint = it.next();
                if (sourceEndpoint.getID().equals(destEndpoint.getID())
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
            for (Iterator<Endpoint> it = conferenceEndpoints.iterator();
                 it.hasNext() && priority < lastN;)
            {
                Endpoint sourceEndpoint = it.next();
                if (sourceEndpoint.getID().equals(destEndpoint.getID())
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
                if (sourceEndpoint.getID().equals(destEndpoint.getID()))
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
     * Gets the {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs.
     *
     * @return the {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs.
     */
    Collection<String> getForwardedEndpoints()
    {
        return forwardedEndpointIds;
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
            int optimal180Idx = 0;
            for (int i = 0; i < encodings.length; i++)
            {
                rates[i] = encodings[i].getLastStableBitrateBps();
                if (encodings[i].getResolution() <= 180)
                {
                    optimal180Idx = i;
                }
            }

            // TODO Determining the optimal index needs some work. The optimal
            // index is constrained by the viewport of the endpoint. For
            // example, on a mobile device we should probably not send
            // anything above 360p (not even the on-stage participant). On a
            // laptop computer 720p seems reasonable and on a big screen 1080p
            // or above.
            optimalIdx = forwarded
                ? (selected ? encodings.length - 1 : optimal180Idx) : -1;
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
            if (rates.length == 0)
            {
                return;
            }

            maxQuality = Math.min(maxQuality, optimalIdx);

            // the targetIdx is initial equal to -1 and it is strictly
            // increasing on every allocation loop.
            for (int i = maxQuality; i > targetIdx; i--)
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

            if (firstMediaMs == -1)
            {
                firstMediaMs = System.currentTimeMillis();
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
}
