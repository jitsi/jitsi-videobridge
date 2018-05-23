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
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

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
     * The property name that holds the bandwidth estimation threshold.
     */
    public static final String BWE_CHANGE_THRESHOLD_PCT_PNAME
        = "org.jitsi.videobridge.BWE_CHANGE_THRESHOLD_PCT";

    /**
     * The property name of the max resolution to allocate for the thumbnails.
     */
    public static final String THUMBNAIL_MAX_HEIGHT_PNAME
        = "org.jitsi.videobridge.THUMBNAIL_MAX_HEIGHT";

    /**
     * The property name of the preferred resolution to allocate for the onstage
     * participant, before allocating bandwidth for the thumbnails.
     */
    public static final String ONSTAGE_PREFERRED_HEIGHT_PNAME
        = "org.jitsi.videobridge.ONSTAGE_PREFERRED_HEIGHT";

    /**
     * The property name of the preferred frame rate to allocate for the onstage
     * participant.
     */
    public static final String ONSTAGE_PREFERRED_FRAME_RATE_PNAME
        = "org.jitsi.videobridge.ONSTAGE_PREFERRED_FRAME_RATE";

    /**
     * The property name of the option that enables/disables video suspension
     * for the on-stage participant.
     */
    public static final String ENABLE_ONSTAGE_VIDEO_SUSPEND_PNAME
        = "org.jitsi.videobridge.ENABLE_ONSTAGE_VIDEO_SUSPEND";

    /**
     * An reusable empty array of {@link RateSnapshot} to reduce allocations.
     */
    private static final RateSnapshot[] EMPTY_RATE_SNAPSHOT_ARRAY
        = new RateSnapshot[0];

    /**
     * The default max resolution to allocate for the thumbnails.
     */
    private static final int THUMBNAIL_MAX_HEIGHT_DEFAULT = 180;

    /**
     * The default preferred resolution to allocate for the onstage participant,
     * before allocating bandwidth for the thumbnails.
     */
    private static final int ONSTAGE_PREFERRED_HEIGHT_DEFAULT = 360;

    /**
     * The default preferred frame rate to allocate for the onstage participant.
     */
    private static final double ONSTAGE_PREFERRED_FRAME_RATE_DEFAULT = 30;

    /**
     * The video for the onstage participant can be disabled by default.
     */
    private static final boolean ENABLE_ONSTAGE_VIDEO_SUSPEND_DEFAULT = false;

    /**
     * The default value of the bandwidth change threshold above which we react
     * with a new bandwidth allocation.
     */
    private static int BWE_CHANGE_THRESHOLD_PCT_DEFAULT = 15;

    /**
     * The ConfigurationService to get config values from.
     */
    private static final ConfigurationService
        cfg = LibJitsi.getConfigurationService();

    /**
     * In order to limit the resolution changes due to bandwidth changes we
     * react to bandwidth changes greater BWE_CHANGE_THRESHOLD_PCT / 100 of the
     * last bandwidth estimation.
     */
    private static final int BWE_CHANGE_THRESHOLD_PCT
        = cfg != null ? cfg.getInt(BWE_CHANGE_THRESHOLD_PCT_PNAME,
        BWE_CHANGE_THRESHOLD_PCT_DEFAULT) : BWE_CHANGE_THRESHOLD_PCT_DEFAULT;

    /**
     * The max resolution to allocate for the thumbnails.
     */
    private static final int THUMBNAIL_MAX_HEIGHT
        = cfg != null ? cfg.getInt(THUMBNAIL_MAX_HEIGHT_PNAME,
        THUMBNAIL_MAX_HEIGHT_DEFAULT) : THUMBNAIL_MAX_HEIGHT_DEFAULT;

    /**
     * The preferred resolution to allocate for the onstage participant, before
     * allocating bandwidth for the thumbnails.
     */
    private static final int ONSTAGE_PREFERRED_HEIGHT
        = cfg != null ? cfg.getInt(ONSTAGE_PREFERRED_HEIGHT_PNAME,
        ONSTAGE_PREFERRED_HEIGHT_DEFAULT) : ONSTAGE_PREFERRED_HEIGHT_DEFAULT;

    /**
     * The preferred frame rate to allocate for the onstage participant.
     */
    private static final double ONSTAGE_PREFERRED_FRAME_RATE
        = cfg != null ? cfg.getDouble(ONSTAGE_PREFERRED_FRAME_RATE_PNAME,
        ONSTAGE_PREFERRED_FRAME_RATE_DEFAULT)
        : ONSTAGE_PREFERRED_FRAME_RATE_DEFAULT;

    /**
     * Determines whether or not we're allowed to suspend the video of the
     * on-stage participant.
     */
    private static final boolean ENABLE_ONSTAGE_VIDEO_SUSPEND
        = cfg != null ? cfg.getBoolean(ENABLE_ONSTAGE_VIDEO_SUSPEND_PNAME,
                ENABLE_ONSTAGE_VIDEO_SUSPEND_DEFAULT)
        : ENABLE_ONSTAGE_VIDEO_SUSPEND_DEFAULT;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger = Logger.getLogger(BitrateController.class);

    /**
     * The {@link TimeSeriesLogger} to be used by this instance to print time
     * series.
     */
    private final TimeSeriesLogger timeSeriesLogger
        = TimeSeriesLogger.getTimeSeriesLogger(BitrateController.class);

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
     * The {@link SimulcastController}s that this instance is managing, keyed
     * by the SSRCs of the associated {@link MediaStreamTrackDesc}.
     */
    private final Map<Long, SimulcastController>
        ssrcToSimulcastController = new ConcurrentHashMap<>();

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
     * A boolean that indicates whether to enable or disable the video quality
     * tracing.
     */
    private final boolean enableVideoQualityTracing;

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
     * The last bandwidth estimation that we got. This is used to limit the
     * resolution changes due to bandwidth changes. We react to bandwidth
     * changes greater than BWE_CHANGE_THRESHOLD_PCT/100 of the last bandwidth
     * estimation.
     */
    private long lastBwe = -1;

    /**
     * The current padding parameters list for {@link #dest}.
     */
    private List<SimulcastController> simulcastControllers;

    /**
     * Initializes a new {@link BitrateController} instance which is to
     * belong to a particular {@link VideoChannel}.
     *
     * @param dest the {@link VideoChannel} that owns this instance.
     */
    public BitrateController(VideoChannel dest)
    {
        this.dest = dest;

        ConfigurationService cfg = LibJitsi.getConfigurationService();

        trustBwe = cfg != null && cfg.getBoolean(TRUST_BWE_PNAME, false);
        enableVideoQualityTracing = timeSeriesLogger.isTraceEnabled();
    }

    /**
     * Returns a boolean that indicates whether or not the current bandwidth
     * estimation (in bps) has changed above the configured threshold (in
     * percent) {@link #BWE_CHANGE_THRESHOLD_PCT} with respect to the previous
     * bandwidth estimation.
     *
     * @param previousBwe the previous bandwidth estimation (in bps).
     * @param currentBwe the current bandwidth estimation (in bps).
     *
     * @return true if the bandwidth has changed above the configured threshold,
     * false otherwise.
     */
    private static boolean isLargerThanBweThreshold(
        long previousBwe, long currentBwe)
    {
        return Math.abs(previousBwe - currentBwe)
            >= previousBwe * BWE_CHANGE_THRESHOLD_PCT / 100;
    }

    /**
     * Gets the {@link VideoChannel} which owns this {@link BitrateController}
     * and is the destination of the packets that this instance accepts.
     *
     * @return the {@link VideoChannel} which owns this
     * {@link BitrateController} and is the destination of the packets that this
     * instance accepts.
     */
    VideoChannel getVideoChannel()
    {
        return dest;
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
    List<SimulcastController> getSimulcastControllers()
    {
        return simulcastControllers;
    }

    /**
     * Defines a packet filter that controls which RTP packets to be written
     * into the {@link Channel} that owns this {@link BitrateController}.
     *
     * @param pkt that packet for which to decide to accept
     * @return <tt>true</tt> to allow the specified packet to be
     * written into the {@link Channel} that owns this {@link BitrateController}
     * ; otherwise, <tt>false</tt>
     */
    public boolean accept(RawPacket pkt)
    {
        long ssrc = pkt.getSSRCAsLong();
        if (ssrc < 0)
        {
            return false;
        }

        SimulcastController simulcastController
            = ssrcToSimulcastController.get(ssrc);

        if (simulcastController == null)
        {
            logger.warn(
                "Dropping an RTP packet, because the SSRC has not " +
                    "been signaled:" + ssrc);
            return false;
        }

        return simulcastController.accept(pkt);
    }

    /**
     * Computes a new bitrate allocation for every endpoint in the conference,
     * and updates the state of this instance so that bitrate allocation is
     * eventually met.
     * The list of endpoints will be fetched from the
     * {@link ConferenceSpeechActivity} of the conference.
     *
     * @param bweBps the current bandwidth estimation (in bps).
     */
    public void update(long bweBps)
    {
        update(null, bweBps);
    }

    /**
     * Computes a new bitrate allocation for every endpoint in the conference,
     * and updates the state of this instance so that bitrate allocation is
     * eventually met.
     * The list of endpoints will be fetched from the
     * {@link ConferenceSpeechActivity} of the conference, and the estimated
     * available bandwidth will be fetched from the {@link BandwidthEstimator}.
     */
    public void update()
    {
        update(null, -1);
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
     * @param bweBps the current bandwidth estimation (in bps), or -1 to fetch
     * the value from the {@link BandwidthEstimator}.
     */
    public void update(List<AbstractEndpoint> conferenceEndpoints, long bweBps)
    {
        if (bweBps > -1)
        {
            if (!isLargerThanBweThreshold(lastBwe, bweBps))
            {
                // If this is a "negligible" change in the bandwidth estimation
                // wrt the last bandwidth estimation that we reacted to, then
                // do not update the bitrate allocation. The goal is to limit
                // the resolution changes due to bandwidth estimation changes,
                // as often resolution changes can negatively impact user
                // experience.
                return;
            }

            lastBwe = bweBps;
        }

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

        if (!(dest.getStream() instanceof VideoMediaStreamImpl))
        {
            return;
        }

        VideoMediaStreamImpl destStream
            = (VideoMediaStreamImpl) dest.getStream();
        BandwidthEstimator bwe = destStream == null ? null
            : destStream.getOrCreateBandwidthEstimator();

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

        if (bweBps < 0 || !trustBwe
            || !destStream.getRtxTransformer().destinationSupportsRtx())
        {
            bweBps = Long.MAX_VALUE;
        }

        // Compute the bitrate allocation.
        TrackBitrateAllocation[]
            trackBitrateAllocations = allocate(bweBps, conferenceEndpoints);

        // Update the the controllers based on the allocation and send a
        // notification to the client the set of forwarded endpoints has
        // changed.
        Set<String> oldForwardedEndpointIds = forwardedEndpointIds;

        Set<String> newForwardedEndpointIds = new HashSet<>();
        Set<String> endpointsEnteringLastNIds = new HashSet<>();
        Set<String> conferenceEndpointIds = new HashSet<>();

        // Accumulators used for tracing purposes.
        long optimalBps = 0, targetBps = 0, currentBps = 0;
        int optimalIdx = 0, targetIdx = 0, currentIdx = 0;

        long nowMs = System.currentTimeMillis();
        List<SimulcastController> simulcastControllers = new ArrayList<>();
        if (!ArrayUtils.isNullOrEmpty(trackBitrateAllocations))
        {
            for (TrackBitrateAllocation
                trackBitrateAllocation : trackBitrateAllocations)
            {
                conferenceEndpointIds.add(trackBitrateAllocation.endpointID);

                int ssrc = trackBitrateAllocation.targetSSRC,
                    trackTargetIdx = trackBitrateAllocation.getTargetIndex(),
                    trackOptimalIdx = trackBitrateAllocation.getOptimalIndex();

                // Review this.
                SimulcastController ctrl;
                synchronized (ssrcToSimulcastController)
                {
                    ctrl = ssrcToSimulcastController.get(ssrc & 0xFFFF_FFFFL);
                    if (ctrl == null && trackBitrateAllocation.track != null)
                    {
                        RTPEncodingDesc[] rtpEncodings
                            = trackBitrateAllocation.track.getRTPEncodings();

                        if (!ArrayUtils.isNullOrEmpty(rtpEncodings))
                        {
                            ctrl = new SimulcastController(
                                this, trackBitrateAllocation.track);

                            // Route all encodings to the specified bitrate
                            // controller.
                            for (RTPEncodingDesc rtpEncoding : rtpEncodings)
                            {
                                ssrcToSimulcastController.put(
                                    rtpEncoding.getPrimarySSRC(), ctrl);

                                long rtxSsrc = rtpEncoding.getSecondarySsrc(Constants.RTX);
                                if (rtxSsrc != -1)
                                {
                                    ssrcToSimulcastController.put(
                                        rtxSsrc, ctrl);
                                }
                            }
                        }
                    }
                }

                if (ctrl != null)
                {
                    simulcastControllers.add(ctrl);
                    ctrl.setTargetIndex(trackTargetIdx);
                    ctrl.setOptimalIndex(trackOptimalIdx);

                    MediaStreamTrackDesc sourceTrack = ctrl.getSource();
                    if (sourceTrack != null
                            && enableVideoQualityTracing)
                    {
                        DiagnosticContext diagnosticContext
                            = destStream.getDiagnosticContext();
                        int trackCurrentIdx = ctrl.getCurrentIndex();
                        long trackTargetBps
                            = trackBitrateAllocation.getTargetBitrate();
                        long trackOptimalBps
                            = trackBitrateAllocation.getOptimalBitrate();
                        long trackCurrentBps
                            = sourceTrack.getBps(trackCurrentIdx);
                        targetBps += trackTargetBps;
                        optimalBps += trackOptimalBps;
                        currentBps += trackCurrentBps;
                        targetIdx += trackTargetIdx;
                        optimalIdx += trackOptimalIdx;
                        currentIdx += trackCurrentIdx;
                        // time series that tracks how a media stream track
                        // gets forwarded to a specific receiver.
                        timeSeriesLogger.trace(diagnosticContext
                                .makeTimeSeriesPoint("track_quality", nowMs)
                                .addKey("track_id", sourceTrack.hashCode())
                                .addField("current_idx", trackCurrentIdx)
                                .addField("target_idx", trackTargetIdx)
                                .addField("optimal_idx", trackOptimalIdx)
                                .addField("current_bps", trackCurrentBps)
                                .addField("target_bps", trackTargetBps)
                                .addField("selected",
                                    trackBitrateAllocation.selected)
                                .addField("oversending",
                                    trackBitrateAllocation.oversending)
                                .addField("preferred_idx",
                                    trackBitrateAllocation.preferredIdx)
                                .addField("endpoint_id",
                                    trackBitrateAllocation.endpointID)
                                .addField("optimal_bps", trackOptimalBps));
                    }
                }

                if (trackTargetIdx > -1)
                {
                    newForwardedEndpointIds
                        .add(trackBitrateAllocation.endpointID);
                    if (!oldForwardedEndpointIds
                        .contains(trackBitrateAllocation.endpointID))
                    {
                        endpointsEnteringLastNIds
                            .add(trackBitrateAllocation.endpointID);
                    }
                }
            }
        }
        else
        {
            for (SimulcastController simulcastController
                : ssrcToSimulcastController.values())
            {
                if (enableVideoQualityTracing)
                {
                    currentIdx--;
                    optimalIdx--;
                    targetIdx--;
                }
                simulcastController.setTargetIndex(-1);
                simulcastController.setOptimalIndex(-1);
            }
        }

        if (enableVideoQualityTracing)
        {
            DiagnosticContext diagnosticContext
                = destStream.getDiagnosticContext();
            timeSeriesLogger.trace(diagnosticContext
                    .makeTimeSeriesPoint("video_quality", nowMs)
                    .addField("current_idx", currentIdx)
                    .addField("target_idx", targetIdx)
                    .addField("optimal_idx", optimalIdx)
                    .addField("current_bps", currentBps)
                    .addField("target_bps", targetBps)
                    .addField("optimal_bps", optimalBps));
        }

        // The BandwidthProber will pick this up.
        this.simulcastControllers = simulcastControllers;

        if (!newForwardedEndpointIds.equals(oldForwardedEndpointIds))
        {
            dest.sendLastNEndpointsChangeEvent(
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
     * history. This parameter is optional but it can be used for performance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @return an array of {@link TrackBitrateAllocation}.
     */
    private TrackBitrateAllocation[] allocate(
        long maxBandwidth, List<AbstractEndpoint> conferenceEndpoints)
    {
        TrackBitrateAllocation[]
            trackBitrateAllocations = prioritize(conferenceEndpoints);

        if (ArrayUtils.isNullOrEmpty(trackBitrateAllocations))
        {
            return trackBitrateAllocations;
        }

        long oldMaxBandwidth = 0;

        int oldStateLen = 0;
        int[] oldState = new int[trackBitrateAllocations.length];
        int[] newState = new int[trackBitrateAllocations.length];
        Arrays.fill(newState, -1);

        while (oldMaxBandwidth != maxBandwidth)
        {
            oldMaxBandwidth = maxBandwidth;
            System.arraycopy(newState, 0, oldState, 0, oldState.length);

            int newStateLen = 0;
            for (int i = 0; i < trackBitrateAllocations.length; i++)
            {
                TrackBitrateAllocation trackBitrateAllocation
                    = trackBitrateAllocations[i];

                if (!trackBitrateAllocation.fitsInLastN)
                {
                    // participants that are not forwarded are sunk in the
                    // prioritization step. When we encounter a participant
                    // who's not on-stage, that means that we're done with the
                    // on-stage participants.
                    break;
                }

                maxBandwidth += trackBitrateAllocation.getTargetBitrate();
                trackBitrateAllocation.improve(maxBandwidth);
                maxBandwidth -= trackBitrateAllocation.getTargetBitrate();

                newState[i] = trackBitrateAllocation.getTargetIndex();
                if (trackBitrateAllocation.getTargetIndex() > -1)
                {
                    newStateLen++;
                }

                if (trackBitrateAllocation.ratesIdx
                    < trackBitrateAllocation.preferredIdx)
                {
                    break;
                }
            }

            if (oldStateLen > newStateLen)
            {
                // rollback state to prevent jumps in the number of forwarded
                // participants.
                for (int i = 0; i < trackBitrateAllocations.length; i++)
                {
                    trackBitrateAllocations[i].ratesIdx = oldState[i];
                }

                break;
            }

            oldStateLen = newStateLen;
        }

        // at this point, maxBandwidth is what we failed to allocate.

        return trackBitrateAllocations;
    }

    /**
     * Returns a prioritized {@link TrackBitrateAllocation} array where
     * selected endpoint are at the top of the array, followed by the pinned
     * endpoints, finally followed by any other remaining endpoints. The
     * priority respects the order induced by the <tt>conferenceEndpoints</tt>
     * parameter.
     *
     * @param conferenceEndpoints the ordered list of {@link Endpoint}s
     * participating in the multipoint conference with the dominant (speaker)
     * {@link Endpoint} at the beginning of the list i.e. the dominant speaker
     * history. This parameter is optional but it can be used for performance;
     * if it's omitted it will be fetched from the
     * {@link ConferenceSpeechActivity}.
     * @return a prioritized {@link TrackBitrateAllocation} array where
     * selected endpoint are at the top of the array, followed by the pinned
     * endpoints, finally followed by any other remaining endpoints.
     */
    private TrackBitrateAllocation[] prioritize(
        List<AbstractEndpoint> conferenceEndpoints)
    {
        if (dest.isExpired())
        {
            return null;
        }

        AbstractEndpoint destEndpoint = dest.getEndpoint();
        if (destEndpoint == null || destEndpoint.isExpired())
        {
            return null;
        }

        // Init.
        List<TrackBitrateAllocation> trackBitrateAllocations
            = new ArrayList<>();

        int lastN = dest.getLastN();
        if (lastN < 0)
        {
            // If lastN is disabled, pretend lastN == szConference.
            lastN = conferenceEndpoints.size() - 1;
        }
        else
        {
            // If lastN is enabled, pretend lastN at most as big as the size
            // of the conference.
            lastN = Math.min(lastN, conferenceEndpoints.size() - 1);
        }

        int endpointPriority = 0;

        // First, bubble-up the selected endpoints (whoever's on-stage needs to
        // be visible).
        Set<String> selectedEndpoints = destEndpoint.getSelectedEndpoints();
        for (Iterator<AbstractEndpoint> it = conferenceEndpoints.iterator();
             it.hasNext() && endpointPriority < lastN;)
        {
            AbstractEndpoint sourceEndpoint = it.next();
            if (sourceEndpoint.isExpired()
                    || sourceEndpoint.getID().equals(destEndpoint.getID())
                    || !selectedEndpoints.contains(sourceEndpoint.getID()))
            {
                continue;
            }

            MediaStreamTrackDesc[] tracks
                = sourceEndpoint.getMediaStreamTracks(MediaType.VIDEO);

            if (!ArrayUtils.isNullOrEmpty(tracks))
            {
                for (MediaStreamTrackDesc track : tracks)
                {
                    trackBitrateAllocations.add(
                        endpointPriority,
                        new TrackBitrateAllocation(
                            sourceEndpoint,
                            track,
                            true /* fitsInLastN */,
                            true /* selected */,
                            getVideoChannel().getMaxFrameHeight()));
                }

                endpointPriority++;
            }

            it.remove();
        }

        // Then, bubble-up the pinned endpoints.
        Set<String> pinnedEndpoints = destEndpoint.getPinnedEndpoints();
        if (!pinnedEndpoints.isEmpty())
        {
            for (Iterator<AbstractEndpoint> it = conferenceEndpoints.iterator();
                 it.hasNext() && endpointPriority < lastN;)
            {
                AbstractEndpoint sourceEndpoint = it.next();
                if (sourceEndpoint.isExpired()
                    || sourceEndpoint.getID().equals(destEndpoint.getID())
                    || !pinnedEndpoints.contains(sourceEndpoint.getID()))
                {
                    continue;
                }

                MediaStreamTrackDesc[] tracks
                    = sourceEndpoint.getMediaStreamTracks(MediaType.VIDEO);

                if (!ArrayUtils.isNullOrEmpty(tracks))
                {
                    for (MediaStreamTrackDesc track : tracks)
                    {
                        trackBitrateAllocations.add(
                            endpointPriority, new TrackBitrateAllocation(
                                sourceEndpoint,
                                track,
                                true /* fitsInLastN */,
                                false /* selected */,
                                getVideoChannel().getMaxFrameHeight()));
                    }

                    endpointPriority++;
                }

                it.remove();
            }
        }

        // Finally, deal with any remaining endpoints.
        if (!conferenceEndpoints.isEmpty())
        {
            for (AbstractEndpoint sourceEndpoint : conferenceEndpoints)
            {
                if (sourceEndpoint.isExpired()
                    || sourceEndpoint.getID().equals(destEndpoint.getID()))
                {
                    continue;
                }

                boolean forwarded = endpointPriority < lastN;

                MediaStreamTrackDesc[] tracks
                    = sourceEndpoint.getMediaStreamTracks(MediaType.VIDEO);

                if (!ArrayUtils.isNullOrEmpty(tracks))
                {
                    for (MediaStreamTrackDesc track : tracks)
                    {
                        trackBitrateAllocations.add(
                            endpointPriority, new TrackBitrateAllocation(
                                sourceEndpoint, track,
                                forwarded, false /* selected */,
                                getVideoChannel().getMaxFrameHeight()));
                    }

                    endpointPriority++;
                }
            }
        }

        return trackBitrateAllocations.toArray(
            new TrackBitrateAllocation[trackBitrateAllocations.size()]);
    }

    /**
     * Gets the {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs.
     *
     * @return the {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs.
     */
    public Collection<String> getForwardedEndpoints()
    {
        return forwardedEndpointIds;
    }

    /**
     * A snapshot of the bitrate for a given {@link RTPEncodingDesc}.
     */
    static class RateSnapshot
    {
        /**
         * The bitrate (in bps) of the associated {@link #encoding}.
         */
        final long bps;

        /**
         * The {@link RTPEncodingDesc}.
         */
        final RTPEncodingDesc encoding;

        /**
         * Ctor.
         *
         * @param bps The bitrate (in bps) of the associated {@link #encoding}.
         * @param encoding the {@link RTPEncodingDesc}.
         */
        private RateSnapshot(long bps, RTPEncodingDesc encoding)
        {
            this.bps = bps;
            this.encoding = encoding;
        }
    }

    /**
     * A bitrate allocation that pertains to a specific {@link Endpoint}.
     *
     * @author George Politis
     */
    private class TrackBitrateAllocation
    {
        /**
         * The ID of the {@link Endpoint} that this instance pertains to.
         */
        private final String endpointID;

        /**
         * Indicates whether this {@link Endpoint} is forwarded or not to the
         * {@link VideoChannel} that owns this {@link BitrateController}.
         */
        private final boolean fitsInLastN;

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
         * Maximum frame height, in pixels, for any video stream forwarded to this receiver
         */
        private final int maxFrameHeight;

        /**
         * The first {@link MediaStreamTrackDesc} of the {@link Endpoint} that
         * this instance pertains to.
         */
        private final MediaStreamTrackDesc track;

        /**
         * An array that holds the stable bitrate snapshots of the
         * {@link RTPEncodingDesc}s that this {@link #track} offers.
         *
         * {@link RTPEncodingDesc} of {@link #track}.
         */
        private final RateSnapshot[] rates;

        /**
         * The index in the {@link #rates} array that needs to be achieved
         * before allocating bandwidth for any of the other tracks after this
         * track.
         */
        private final int preferredIdx;

        /**
         * The index in the {@link #rates} array that is the currently selected
         * target for this track.
         */
        private int ratesIdx = -1;

        /**
         * A boolean that indicates whether or not we're force pushing through
         * the bottleneck this track.
         */
        private boolean oversending = false;

        /**
         * Ctor.
         *
         * @param endpoint the {@link Endpoint} that this bitrate allocation
         * pertains to.
         * @param track the {@link MediaStreamTrackDesc} that this bitrate
         * allocation pertains to.
         * @param fitsInLastN a flag indicating whether or not the endpoint is
         * in LastN.
         * @param selected a flag indicating whether or not the endpoint is
         * selected.
         */
        private TrackBitrateAllocation(
            AbstractEndpoint endpoint, MediaStreamTrackDesc track,
            boolean fitsInLastN, boolean selected, int maxFrameHeight)
        {
            this.endpointID = endpoint.getID();
            this.selected = selected;
            this.fitsInLastN = fitsInLastN;
            this.track = track;
            this.maxFrameHeight = maxFrameHeight;

            RTPEncodingDesc[] encodings;
            if (track == null)
            {
                this.targetSSRC = -1;
                encodings = null;
            }
            else
            {
                encodings = track.getRTPEncodings();

                if (ArrayUtils.isNullOrEmpty(encodings))
                {
                    this.targetSSRC = -1;
                }
                else
                {
                    this.targetSSRC = (int) encodings[0].getPrimarySSRC();
                }
            }

            if (targetSSRC == -1 || !fitsInLastN)
            {
                preferredIdx = -1;
                rates = EMPTY_RATE_SNAPSHOT_ARRAY;
                return;
            }

            List<RateSnapshot> ratesList = new ArrayList<>();
            // Initialize the list of flows that we will consider for sending
            // for this track. For example, for the on-stage participant we
            // consider 720p@30fps, 360p@30fps, 180p@30fps, 180p@15fps,
            // 180p@7.5fps while for the thumbnails we consider 180p@30fps,
            // 180p@15fps and 180p@7.5fps
            int preferredIdx = 0;
            for (RTPEncodingDesc encoding : encodings)
            {
                if (encoding.getHeight() > this.maxFrameHeight)
                {
                    continue;
                }
                if (selected)
                {
                    // For the selected participant we favor resolution over
                    // frame rate.
                    if (encoding.getHeight() < ONSTAGE_PREFERRED_HEIGHT
                        || encoding.getFrameRate() >= ONSTAGE_PREFERRED_FRAME_RATE)
                    {
                        ratesList.add(new RateSnapshot(
                            encoding.getLastStableBitrateBps(), encoding));
                    }

                    if (encoding.getHeight() <= ONSTAGE_PREFERRED_HEIGHT)
                    {
                        preferredIdx = ratesList.size() - 1;
                    }
                }
                else if (encoding.getHeight() <= THUMBNAIL_MAX_HEIGHT)
                {
                    // For the thumbnails, we consider all temporal layers of
                    // the low resolution stream.
                    ratesList.add(new RateSnapshot(
                        encoding.getLastStableBitrateBps(), encoding));
                }
            }

            this.preferredIdx = preferredIdx;
            rates = ratesList.toArray(new RateSnapshot[ratesList.size()]);
            // TODO Determining the optimal index needs some work. The optimal
            // index is constrained by the viewport of the endpoint. For
            // example, on a mobile device we should probably not send
            // anything above 360p (not even the on-stage participant). On a
            // laptop computer 720p seems reasonable and on a big screen 1080p
            // or above.
        }

        /**
         * Computes the optimal and the target bitrate, limiting the target to
         * be less than bandwidth estimation specified as an argument.
         *
         * @param maxBps the maximum bitrate (in bps) that the target subjective
         * quality can have.
         */
        void improve(long maxBps)
        {
            if (rates.length == 0)
            {
                return;
            }

            if (ratesIdx == -1 && selected)
            {
                if (!ENABLE_ONSTAGE_VIDEO_SUSPEND)
                {
                    ratesIdx = 0;
                    oversending = rates[0].bps > maxBps;
                }

                // Boost on stage participant to 360p.
                for (int i = ratesIdx + 1; i < rates.length; i++)
                {
                    if (i > preferredIdx
                        || maxBps < rates[i].bps)
                    {
                        break;
                    }

                    ratesIdx = i;
                }
            }
            else
            {
                // Try the next element in the rates array.
                if (ratesIdx + 1 < rates.length
                    && rates[ratesIdx + 1].bps < maxBps)
                {
                    ratesIdx++;
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
            return ratesIdx != -1 ? rates[ratesIdx].bps : 0;
        }

        /**
         * Gets the optimal bitrate (in bps) for this endpoint allocation.
         *
         * @return the optimal bitrate (in bps) for this endpoint allocation.
         */
        long getOptimalBitrate()
        {
            return rates.length != 0 ? rates[rates.length - 1].bps : 0L;
        }

        /**
         * Gets the target subjective quality index for this track.
         *
         * @return the target subjective quality index for this track.
         */
        int getTargetIndex()
        {
            return ratesIdx != -1 ? rates[ratesIdx].encoding.getIndex() : -1;
        }

        /**
         * Gets the optimal subjective quality index for this track.
         *
         * @return the optimal subjective quality index for this track.
         */
        int getOptimalIndex()
        {
            return rates.length != 0
                ? rates[rates.length - 1].encoding.getIndex() : -1;
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
            for (SimulcastController simulcastController
                : ssrcToSimulcastController.values())
            {
                try
                {
                    simulcastController.close();
                }
                catch (Exception ignored)
                {

                }
            }
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

                SimulcastController simulcastController
                    = ssrcToSimulcastController.get(ssrc);

                // FIXME properly support unannounced SSRCs.
                RawPacket[] ret
                    = simulcastController == null
                            ? null : simulcastController.rtpTransform(pkts[i]);

                if (ArrayUtils.isNullOrEmpty(ret))
                {
                    pkts[i] = null;
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

            SimulcastController simulcastController
                = ssrcToSimulcastController.get(ssrc);

            return simulcastController == null
                    ? pkt : simulcastController.rtcpTransform(pkt);
        }
    }
}
