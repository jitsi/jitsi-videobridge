/*
 * Copyright @ 2015 - Present, 8x8 Inc
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

import com.google.common.collect.*;
import edu.umd.cs.findbugs.annotations.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.nlj.rtp.*;
import org.jitsi.rtp.rtcp.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.cc.config.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import javax.annotation.CheckReturnValue;
import java.lang.*;
import java.lang.SuppressWarnings;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import java.util.concurrent.atomic.*;

/**
 * The {@link BitrateController} is attached to a destination {@link
 * Endpoint} and its purpose is 1st to selectively drop incoming packets
 * based on their source {@link Endpoint} and 2nd to rewrite the accepted
 * packets so that they form a correct RTP stream without gaps in their
 * sequence numbers nor in their timestamps.
 *
 * In the Selective Forwarding Middlebox (SFM) topology [RFC7667], senders are
 * sending multiple bitstreams of (they multistream) the same video source
 * (using either with simulcast or scalable video coding) and the SFM decides
 * which bitstream to send to which receiver.
 *
 * For example, suppose we're in a 3-way simulcast-enabled video call (so 3
 * source {@link Endpoint}s) where the endpoints are sending two
 * non-scalable RTP streams of the same video source. The bitrate controller,
 * will chose to forward only one RTP stream at a time to a specific destination
 * endpoint.
 *
 * With scalable video coding (SVC) a bitstream can be broken down into multiple
 * sub-bitstreams of lower frame rate (that we call temporal layers or TL)
 * and/or lower resolution (that we call spatial layers or SL). The exact
 * dependencies between the different layers of an SVC bitstream are codec
 * specific but, as a general rule of thumb higher temporal/spatial layers
 * depend on lower temporal/spatial layers creating a dependency graph.
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
 * {@link RtpLayerDesc} and its properties are bitrate and subjective quality
 * index. Specifically, the incoming packets belong to some {@link FrameDesc},
 * which belongs to some {@link RtpLayerDesc}, which belongs to some {@link
 * MediaSourceDesc}, which belongs to the source {@link Endpoint}.
 * This hierarchy allows for fine-grained filtering up to the {@link FrameDesc}
 * level.
 *
 * The decision of whether to project or drop a specific RTP packet of a
 * specific {@link FrameDesc} of a specific {@link MediaSourceDesc}
 * depends on the bitrate allocation of the specific {@link
 * MediaSourceDesc} and on the state of the bitstream that is produced by
 * this filter. For example, if we have a {@link MediaSourceDesc} with 2
 * {@link RtpLayerDesc} in a simulcast configuration, then we can switch
 * between the two {@link RtpLayerDesc}s if it is mandated by the bitrate
 * allocation and only if we see a refresh point.
 *
 * @author George Politis
 */
@SuppressWarnings("JavadocReference")
public class BitrateController
{
    /**
     * An reusable empty array of {@link RateSnapshot} to reduce allocations.
     */
    private static final RateSnapshot[] EMPTY_RATE_SNAPSHOT_ARRAY
        = new RateSnapshot[0];

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The {@link TimeSeriesLogger} to be used by this instance to print time
     * series.
     */
    private static final TimeSeriesLogger timeSeriesLogger
        = TimeSeriesLogger.getTimeSeriesLogger(BitrateController.class);

    /**
     * The {@link AdaptiveSourceProjection}s that this instance is managing, keyed
     * by the SSRCs of the associated {@link MediaSourceDesc}.
     */
    private final Map<Long, AdaptiveSourceProjection>
        adaptiveSourceProjectionMap = new ConcurrentHashMap<>();

    /**
     * The {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs. Required for backwards compatibility with
     * existing LastN code.
     */
    private Set<String> forwardedEndpointIds = Collections.emptySet();

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
     * The list of endpoints ids ordered by activity (minus the id of the {@link #destinationEndpoint}).
     */
    private List<String> sortedEndpointIds;

    /**
     * The main result of the bitrate allocation algorithm computation.
     */
    private List<AdaptiveSourceProjection> adaptiveSourceProjections
            = Collections.emptyList();

    /**
     * The map of endpoint id to video constraints that contains the video
     * constraints to respect when allocating bandwidth for a specific endpoint.
     */
    private ImmutableMap<String, VideoConstraints> videoConstraintsMap = ImmutableMap.of();

    /**
     * A modified copy of the original video constraints map, augmented with video constraints for the endpoints that
     * fall outside of the last-n set + endpoints not announced in the videoConstraintsMap.
     */
    private Map<String, VideoConstraints> effectiveConstraintsMap = Collections.EMPTY_MAP;

    /**
     * The last-n value for the endpoint to which this {@link BitrateController}
     * belongs
     */
    private int lastN = -1;

    /**
     * The ID of the endpoint to which this {@link BitrateController} belongs
     */
    private final Endpoint destinationEndpoint;

    private final DiagnosticContext diagnosticContext;

    // NOTE(george): this flag acts as an approximation for determining whether
    // or not adaptivity/probing is supported. Eventually we need to scrap this
    // and implement something cleaner, i.e. disable adaptivity if the endpoint
    // hasn't signaled `goog-remb` nor `transport-cc`.
    //
    // Unfortunately the channel iq from jicofo lists `goog-remb` and
    // `transport-cc` support, even tho the jingle from firefox doesn't (which
    // is the main use case for wanting to disable adaptivity).
    private boolean supportsRtx = false;

    private final Map<Byte, PayloadType> payloadTypes =
        new ConcurrentHashMap<>();

    private final AtomicInteger numDroppedPacketsUnknownSsrc =
        new AtomicInteger(0);

    private final Clock clock;

    /**
     * The last time {@link BitrateController#update()} was called
     */
    private Instant lastUpdateTime = Instant.MIN;

    /**
     * Initializes a new {@link BitrateController} instance which is to
     * belong to a particular {@link Endpoint}.
     */
    public BitrateController(
            Endpoint destinationEndpoint,
            @NotNull DiagnosticContext diagnosticContext,
            Logger parentLogger,
            Clock clock
    )
    {
        this.destinationEndpoint = destinationEndpoint;
        this.diagnosticContext = diagnosticContext;
        this.logger = parentLogger.createChildLogger(BitrateController.class.getName());
        this.clock = clock;

        enableVideoQualityTracing = timeSeriesLogger.isTraceEnabled();
    }

    public BitrateController(
        Endpoint destinationEndpoint,
        @NotNull DiagnosticContext diagnosticContext,
        Logger parentLogger
    ) {
        this(destinationEndpoint, diagnosticContext, parentLogger, Clock.systemUTC());
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
    private static boolean changeIsLargerThanThreshold(
        long previousBwe, long currentBwe)
    {
        if (previousBwe == -1 || currentBwe == -1)
        {
            return true;
        }

        long deltaBwe = currentBwe - previousBwe;

        // if the bwe has increased, we should act upon it, otherwise
        // we may end up in this broken situation: Suppose that the target
        // bitrate is 2.5Mbps, and that the last bitrate allocation was
        // performed with a 2.4Mbps bandwidth estimate.  The bridge keeps
        // probing and, suppose that, eventually the bandwidth estimate reaches
        // 2.6Mbps, which is plenty to accommodate the target bitrate; but the
        // minimum bandwidth estimate that would trigger a new bitrate
        // allocation is 2.4Mbps + 2.4Mbps * 15% = 2.76Mbps.
        //
        // if, on the other hand, the bwe has decreased, we require a 15%
        // (configurable) drop at last in order to update the bitrate
        // allocation. This is an ugly hack to prevent too many resolution/UI
        // changes in case the bridge produces too low bandwidth estimate, at
        // the risk of clogging the receiver's pipe.

        return deltaBwe > 0
            ||  deltaBwe < -1 * previousBwe * BitrateControllerConfig.bweChangeThreshold();
    }

    /**
     * A helper class that is used to determine the bandwidth allocation
     * rank/priority of an endpoint that is based on its speaker rank and its
     * video constraints. See {@link EndpointMultiRanker} for more information
     * on how the ranking works.
     */
    static class EndpointMultiRank
    {
        /**
         * The speaker rank of the {@link #endpoint} with 0 meaning that the
         * {@link #endpoint} is the most recent dominant speaker. Also see
         * {@link ConferenceSpeechActivity#endpoints}
         */
        final int speakerRank;

        /**
         * The video constraints of the {@link #endpoint}.
         */
        final VideoConstraints effectiveVideoConstraints;

        /**
         * The endpoint (sender) that's constrained and is ranked for bandwidth
         * allocation.
         */
        final AbstractEndpoint endpoint;

        /**
         * Ctor.
         *
         * @param speakerRank
         * @param effectiveVideoConstraints
         * @param endpoint
         */
        EndpointMultiRank(int speakerRank, VideoConstraints effectiveVideoConstraints, AbstractEndpoint endpoint)
        {
            this.speakerRank = speakerRank;
            this.effectiveVideoConstraints = effectiveVideoConstraints;
            this.endpoint = endpoint;
        }
    }

    /**
     * An endpoint that has higher priority/rank will be allocated
     * bandwidth prior to other endpoints with lower priority/rank
     * (see the allocate method bellow)
     *
     * Once the endpoints are ranked, the bandwidth allocation algorithm
     * loops over the endpoints multiple times, improving their target
     * bitrate at every step, until no further improvement is possible.
     *
     * In this multi-rank implementation, endpoints that have a preferred height
     * set (on-stage endpoints in Jitsi Meet) will be given bandwidth first.
     * Then we prioritize endpoints that have higher ideal height (this rule is
     * somewhat arbitrary since we don't have a use case in Jitsi Meet that
     * leverages it). If two endpoints have the same ideal and preferred height,
     * then we look at their speech rank (whoever spoke last has is ranked higher).
     */
    static class EndpointMultiRanker
        implements Comparator<EndpointMultiRank>
    {
        @Override
        public int compare(EndpointMultiRank o1, EndpointMultiRank o2)
        {
            // We want "o1 has higher preferred height than o2" to imply "o1 is
            // smaller than o2" as this is equivalent to "o1 needs to be
            // prioritized first".
            int preferredHeightDiff =
                o2.effectiveVideoConstraints.getPreferredHeight() - o1.effectiveVideoConstraints.getPreferredHeight();
            if (preferredHeightDiff != 0)
            {
                return preferredHeightDiff;
            }
            else
            {
                // We want "o1 has higher ideal height than o2" to imply "o1 is
                // smaller than o2" as this is equivalent to "o1 needs to be
                // prioritized first".
                int idealHeightDiff
                    = o2.effectiveVideoConstraints.getIdealHeight() - o1.effectiveVideoConstraints.getIdealHeight();
                if (idealHeightDiff != 0)
                {
                    return idealHeightDiff;
                }

                // Everything else being equal, we rely on the speaker order.
                return o1.speakerRank - o2.speakerRank;
            }
        }
    }

    /**
     * Defines a packet filter that controls which RTP packets to be written
     * into the {@link Endpoint} that owns this {@link BitrateController}.
     *
     * @param packetInfo that packet for which to decide whether to accept
     * @return <tt>true</tt> to allow the specified packet to be
     * written into the {@link Endpoint} that owns this {@link BitrateController}
     * ; otherwise, <tt>false</tt>
     */
    public boolean accept(@NotNull PacketInfo packetInfo)
    {
        if (packetInfo.getLayeringChanged()) {
            update();
        }

        VideoRtpPacket videoRtpPacket = packetInfo.packetAs();
        long ssrc = videoRtpPacket.getSsrc();

        AdaptiveSourceProjection adaptiveSourceProjection
            = adaptiveSourceProjectionMap.get(ssrc);

        if (adaptiveSourceProjection == null)
        {
            logger.debug(() ->
                "Dropping an RTP packet, because the SSRC has not " +
                    "been signaled:" + ssrc);
            numDroppedPacketsUnknownSsrc.incrementAndGet();
            return false;
        }

        return adaptiveSourceProjection.accept(packetInfo);
    }

    /**
     * Defines a packet filter that controls which RTCP Sender Report
     * packets to be written into the {@link Endpoint} that owns this
     * {@link BitrateController}.
     * </p>
     * Filters out packets that match one of the streams that this
     * {@code BitrateController} manages, but don't match the target SSRC.
     * Allows packets for streams not managed by this {@link BitrateController}.
     *
     * @param rtcpSrPacket that packet for which to decide whether to accept
     * @return <tt>true</tt> to allow the specified packet to be
     * written into the {@link Endpoint} that owns this {@link BitrateController}
     * ; otherwise, <tt>false</tt>
     */
    public boolean accept(RtcpSrPacket rtcpSrPacket)
    {
        if (Duration.between(lastUpdateTime, clock.instant())
                .compareTo(BitrateControllerConfig.maxTimeBetweenCalculations()) > 0) {
            logger.debug("Forcing an update");
            TaskPools.CPU_POOL.submit(this::update);
        }
        long ssrc = rtcpSrPacket.getSenderSsrc();

        AdaptiveSourceProjection adaptiveSourceProjection
                = adaptiveSourceProjectionMap.get(ssrc);

        if (adaptiveSourceProjection == null)
        {
            // This is probably for an audio stream. In any case, if it's for a
            // stream which we are not forwarding it will be stripped off at
            // a later stage (in RtcpSrUpdater).
            return true;
        }

        // We only accept SRs for the SSRC that we're forwarding with.
        return ssrc == adaptiveSourceProjection.getTargetSsrc();
    }

    public boolean transformRtcp(RtcpSrPacket rtcpSrPacket)
    {
        long ssrc = rtcpSrPacket.getSenderSsrc();

        AdaptiveSourceProjection adaptiveSourceProjection
                = adaptiveSourceProjectionMap.get(ssrc);

        return adaptiveSourceProjection != null
                && adaptiveSourceProjection.rewriteRtcp(rtcpSrPacket);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    @SuppressFBWarnings(
            value = "IS2_INCONSISTENT_SYNC",
            justification = "We intentionally avoid synchronizing while reading" +
                    " fields only used in debug output.")
    public JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("forwardedEndpoints", forwardedEndpointIds.toString());
        debugState.put("trustBwe", BitrateControllerConfig.trustBwe());
        debugState.put("lastBwe", lastBwe);
        debugState.put("videoConstraints", videoConstraintsMap);
        debugState.put("effectiveVideoConstraints", effectiveConstraintsMap);
        debugState.put("lastN", lastN);
        debugState.put("supportsRtx", supportsRtx);
        JSONObject adaptiveSourceProjectionsJson = new JSONObject();
        for (Map.Entry<Long, AdaptiveSourceProjection> entry
                : adaptiveSourceProjectionMap.entrySet())
        {
            adaptiveSourceProjectionsJson.put(
                    entry.getKey(),
                    entry.getValue().getDebugState());
        }
        debugState.put(
                "adaptiveSourceProjectionMap",
                adaptiveSourceProjectionsJson);
        debugState.put(
            "numDroppedPacketsUnknownSsrc",
            numDroppedPacketsUnknownSsrc.intValue());
        return debugState;
    }

    /**
     * TODO Document
     */
    static class StatusSnapshot
    {
        final long currentTargetBps;
        final long currentIdealBps;
        final Collection<Long> activeSsrcs;

        StatusSnapshot()
        {
            currentTargetBps = -1L;
            currentIdealBps = -1L;
            activeSsrcs = Collections.emptyList();
        }
        StatusSnapshot(
                Long currentTargetBps,
                Long currentIdealBps,
                Collection<Long> activeSsrcs)
        {
            this.currentTargetBps = currentTargetBps;
            this.currentIdealBps = currentIdealBps;
            this.activeSsrcs = activeSsrcs;
        }
    }

    /**
     * Get a snapshot of the following data:
     * 1) The current target bitrate we're trying to send across our sources
     * 2) The ideal bitrate we could possibly send, given our sources
     * 3) The ssrcs we're currently forwarding
     * @return the snapshot containing that info
     */
    StatusSnapshot getStatusSnapshot()
    {
        if (adaptiveSourceProjections == null
            || adaptiveSourceProjections.isEmpty())
        {
            return new StatusSnapshot();
        }
        List<Long> activeSsrcs = new ArrayList<>();
        long totalTargetBps = 0, totalIdealBps = 0;
        long nowMs = clock.instant().toEpochMilli();
        for (MediaSourceDesc incomingSource : destinationEndpoint
            .getConference().getEndpoints().stream()
            .filter(e -> !destinationEndpoint.equals(e))
            .map(AbstractEndpoint::getMediaSources)
            .flatMap(Arrays::stream)
            .filter(MediaSourceDesc::hasRtpLayers)
            .collect(Collectors.toList()))
        {

            long primarySsrc = incomingSource.getPrimarySSRC();
            AdaptiveSourceProjection adaptiveSourceProjection
                = adaptiveSourceProjectionMap.getOrDefault(primarySsrc, null);

            if (adaptiveSourceProjection == null)
            {
                logger.debug(destinationEndpoint.getID() + " is missing " +
                    "an adaptive source projection for endpoint=" +
                    incomingSource.getOwner() + ", ssrc=" + primarySsrc);
                continue;
            }

            long targetBps = incomingSource.getBitrateBps(nowMs,
                    adaptiveSourceProjection.getTargetIndex());
            if (targetBps > 0)
            {
                long ssrc = adaptiveSourceProjection.getTargetSsrc();
                if (ssrc > -1)
                {
                    activeSsrcs.add(ssrc);
                }
            }

            totalTargetBps += targetBps;
            // the sum of the bitrates (in bps) of the layers that are the
            // closest to the ideal and has a bitrate. this is similar to how
            // we compute the ideal bitrate bellow in
            // {@link SourceBitrateAllocation#idealBitrate} and the logic should
            // be extracted in a utility method somehow.
            totalIdealBps += incomingSource.getBitrateBps(nowMs,
                    adaptiveSourceProjection.getIdealIndex());
        }
        return new StatusSnapshot(totalTargetBps, totalIdealBps, activeSsrcs);
    }

    /**
     * We can't just use {@link #lastBwe} to get the most recent bandwidth
     * estimate as we must take into account the initial join period where
     * we don't 'trust' the bwe anyway, as well as whether or not we'll
     * trust it at all (we ignore it always if the endpoint doesn't support
     * RTX, which is our way of filtering out endpoints that don't do
     * probing)
     * @return the estimated bandwidth, in bps, based on the last update
     * we received and taking into account whether or not we 'trust' the
     * bandwidth estimate.
     */
    private long getAvailableBandwidth(long nowMs)
    {
        boolean trustBwe = BitrateControllerConfig.trustBwe();
        if (trustBwe)
        {
            // Ignore the bandwidth estimations in the first 10 seconds because
            // the REMBs don't ramp up fast enough. This needs to go but it's
            // related to our GCC implementation that needs to be brought up to
            // speed.
            if (firstMediaMs == -1 || nowMs - firstMediaMs < 10000)
            {
                trustBwe = false;
            }
        }

        if (!trustBwe || !supportsRtx)
        {
            return Long.MAX_VALUE;
        }
        else
        {
            return lastBwe;
        }
    }

    /**
     * Called when the estimated bandwidth for the endpoint to which this
     * BitrateController belongs has changed (which may therefore result in a
     * different set of streams being forwarded)
     * @param newBandwidthBps the newly estimated bandwidth in bps
     */
    public void bandwidthChanged(long newBandwidthBps)
    {
        if (timeSeriesLogger.isTraceEnabled())
        {
            timeSeriesLogger.trace(diagnosticContext
                .makeTimeSeriesPoint("new_bwe")
                .addField("bwe_bps", newBandwidthBps));
        }

        if (!changeIsLargerThanThreshold(lastBwe, newBandwidthBps))
        {
            logger.debug(() -> "New bandwidth (" + newBandwidthBps
                + ") is not significantly " +
                "changed from previous estimate (" + lastBwe + "), ignoring");
            // If this is a "negligible" change in the bandwidth estimation
            // wrt the last bandwidth estimation that we reacted to, then
            // do not update the bitrate allocation. The goal is to limit
            // the resolution changes due to bandwidth estimation changes,
            // as often resolution changes can negatively impact user
            // experience, at the risk of clogging the receiver pipe.
        }
        else
        {
            logger.debug(() -> "new bandwidth is " + newBandwidthBps + ", updating");

            lastBwe = newBandwidthBps;
            update();
        }
    }

    /**
     * Called when the ordering of endpoints has changed in some way. This could
     * be due to an endpoint joining or leaving, a new dominant speaker, or a
     * change in which endpoints are selected.
     *
     * @param conferenceEndpoints the endpoints of the conference sorted in
     * dominant speaker order (NOTE this does NOT take into account orderings
     * specific to this particular endpoint, e.g. selected or pinned, though
     * this method SHOULD be invoked when those things change; they will be
     * taken into account in this flow)
     */
    public synchronized void endpointOrderingChanged(List<String> conferenceEndpoints)
    {
        logger.debug(() -> " endpoint ordering has changed, updating");

        List<String> newSortedEndpointIds = new ArrayList<>(conferenceEndpoints);
        newSortedEndpointIds.remove(destinationEndpoint.getID());
        sortedEndpointIds = newSortedEndpointIds;
        update();
    }

    /**
     * Computes a new bitrate allocation for every endpoint in the conference,
     * and updates the state of this instance so that bitrate allocation is
     * eventually met.
     */
    private synchronized void update()
    {
        Instant now = clock.instant();
        lastUpdateTime = now;
        long nowMs = now.toEpochMilli();

        long bweBps = getAvailableBandwidth(nowMs);

        // Create a copy as we may modify the list in the prioritize method.
        List<String> sortedEndpointIdsCopy = sortedEndpointIds;
        if (sortedEndpointIdsCopy == null || sortedEndpointIdsCopy.isEmpty())
        {
            return;
        }

        List<AbstractEndpoint> sortedEndpoints
            = new ArrayList<>(sortedEndpointIdsCopy.size());
        for (String endpointId : sortedEndpointIdsCopy)
        {
            AbstractEndpoint abstractEndpoint
                 = destinationEndpoint.getConference().getEndpoint(endpointId);
            if (abstractEndpoint != null)
            {
                sortedEndpoints.add(abstractEndpoint);
            }
        }

        // Compute the bitrate allocation.
        SourceBitrateAllocation[]
            sourceBitrateAllocations = allocate(bweBps, sortedEndpoints);

        // Update the the controllers based on the allocation and send a
        // notification to the client the set of forwarded endpoints has
        // changed.
        Set<String> oldForwardedEndpointIds = forwardedEndpointIds;

        Set<String> newForwardedEndpointIds = new HashSet<>();
        Set<String> endpointsEnteringLastNIds = new HashSet<>();
        // TODO: The only use of conferenceEndpointIds is sending it to the client. Since it is just a set of all
        // endpoints in the conference (not ordered by anything specific), it carries no useful information to the
        // client. Check if we can remove its use from the client and subsequently from this code.
        Set<String> conferenceEndpointIds = new HashSet<>();

        // Accumulators used for tracing purposes.
        long totalIdealBps = 0, totalTargetBps = 0;
        int totalIdealIdx = 0, totalTargetIdx = 0;

        // Now that the bitrate allocation update is complete, we wish to compute the "effective" sender video
        // constraints map which are used in layer suspension.
        Map<String, VideoConstraints> newEffectiveConstraints = new HashMap<>();

        List<AdaptiveSourceProjection> adaptiveSourceProjections
                = new ArrayList<>();
        if (!ArrayUtils.isNullOrEmpty(sourceBitrateAllocations))
        {
            for (SourceBitrateAllocation
                sourceBitrateAllocation : sourceBitrateAllocations)
            {
                conferenceEndpointIds.add(sourceBitrateAllocation.endpointID);
                newEffectiveConstraints.put(
                        sourceBitrateAllocation.endpointID, sourceBitrateAllocation.effectiveVideoConstraints);

                int sourceTargetIdx = sourceBitrateAllocation.getTargetIndex(),
                    sourceIdealIdx = sourceBitrateAllocation.getIdealIndex();

                // Review this.
                AdaptiveSourceProjection adaptiveSourceProjection
                    = lookupOrCreateAdaptiveSourceProjection(sourceBitrateAllocation);

                if (adaptiveSourceProjection != null)
                {
                    adaptiveSourceProjections.add(adaptiveSourceProjection);
                    adaptiveSourceProjection.setTargetIndex(sourceTargetIdx);
                    adaptiveSourceProjection.setIdealIndex(sourceIdealIdx);

                    if (sourceBitrateAllocation.source != null
                            && enableVideoQualityTracing)
                    {
                        long sourceTargetBps
                            = sourceBitrateAllocation.getTargetBitrate();
                        long sourceIdealBps
                            = sourceBitrateAllocation.getIdealBitrate();
                        totalTargetBps += sourceTargetBps;
                        totalIdealBps += sourceIdealBps;
                        totalTargetIdx += sourceTargetIdx;
                        totalIdealIdx += sourceIdealIdx;
                        // time series that tracks how a media source
                        // gets forwarded to a specific receiver.
                        timeSeriesLogger.trace(diagnosticContext
                            .makeTimeSeriesPoint("source_quality", nowMs)
                            .addField("source_id",
                                sourceBitrateAllocation.source.hashCode())
                            .addField("target_idx", sourceTargetIdx)
                            .addField("ideal_idx", sourceIdealIdx)
                            .addField("target_bps", sourceTargetBps)
                            .addField("effectiveVideoConstraints",
                                sourceBitrateAllocation.effectiveVideoConstraints)
                            .addField("oversending",
                                sourceBitrateAllocation.oversending)
                            .addField("preferred_idx",
                                sourceBitrateAllocation.getPreferredIndex())
                            .addField("remote_endpoint_id",
                                sourceBitrateAllocation.endpointID)
                            .addField("ideal_bps", sourceIdealBps));
                    }
                }

                if (sourceTargetIdx > -1)
                {
                    newForwardedEndpointIds
                        .add(sourceBitrateAllocation.endpointID);
                    if (!oldForwardedEndpointIds
                        .contains(sourceBitrateAllocation.endpointID))
                    {
                        endpointsEnteringLastNIds
                            .add(sourceBitrateAllocation.endpointID);
                    }
                }
            }
        }
        else
        {
            for (AdaptiveSourceProjection adaptiveSourceProjection
                : adaptiveSourceProjectionMap.values())
            {
                if (enableVideoQualityTracing)
                {
                    totalIdealIdx--;
                    totalTargetIdx--;
                }
                adaptiveSourceProjection
                    .setTargetIndex(RtpLayerDesc.SUSPENDED_INDEX);
                adaptiveSourceProjection
                    .setIdealIndex(RtpLayerDesc.SUSPENDED_INDEX);
            }
        }

        if (enableVideoQualityTracing)
        {
            timeSeriesLogger.trace(diagnosticContext
                    .makeTimeSeriesPoint("did_update", nowMs)
                    .addField("total_target_idx", totalTargetIdx)
                    .addField("total_ideal_idx", totalIdealIdx)
                    .addField("bwe_bps", bweBps)
                    .addField("total_target_bps", totalTargetBps)
                    .addField("total_ideal_bps", totalIdealBps));
        }

        // The BandwidthProber will pick this up.
        this.adaptiveSourceProjections
            = Collections.unmodifiableList(adaptiveSourceProjections);

        if (!newForwardedEndpointIds.equals(oldForwardedEndpointIds))
        {
            // TODO(george) bring back sending this message on message transport
            //  connect
            destinationEndpoint.sendLastNEndpointsChangeEvent(
                newForwardedEndpointIds,
                endpointsEnteringLastNIds,
                conferenceEndpointIds);
        }

        if (!newEffectiveConstraints.equals(effectiveConstraintsMap))
        {
            ImmutableMap<String, VideoConstraints>
                oldEffectiveConstraints = ImmutableMap.copyOf(effectiveConstraintsMap);
            // TODO make the call outside the synchronized block.
            effectiveConstraintsMap = newEffectiveConstraints;
            destinationEndpoint.effectiveVideoConstraintsChanged(
                oldEffectiveConstraints, ImmutableMap.copyOf(newEffectiveConstraints));
        }

        this.forwardedEndpointIds = newForwardedEndpointIds;
    }

    /**
     * Utility method that looks-up or creates the adaptive source projection of
     * a source.
     *
     * @param sourceBitrateAllocation the source bitrate allocation
     * @return the adaptive source projection for the source bitrate allocation
     * that is specified as an argument.
     */
    private AdaptiveSourceProjection
    lookupOrCreateAdaptiveSourceProjection(
        SourceBitrateAllocation sourceBitrateAllocation)
    {
        synchronized (adaptiveSourceProjectionMap)
        {
            AdaptiveSourceProjection adaptiveSourceProjection
                = adaptiveSourceProjectionMap.get(
                        sourceBitrateAllocation.targetSSRC);

            if (adaptiveSourceProjection != null
                || sourceBitrateAllocation.source == null)
            {
                return adaptiveSourceProjection;
            }

            RtpEncodingDesc[] rtpEncodings =
                sourceBitrateAllocation.source.getRtpEncodings();

            if (ArrayUtils.isNullOrEmpty(rtpEncodings))
            {
                return null;
            }

            // XXX the lambda keeps a reference to the sourceBitrateAllocation
            // (a short lived object under normal circumstances) which keeps
            // a reference to the Endpoint object that it refers to. That
            // can cause excessive object retention (i.e. the endpoint is expired
            // but a reference persists in the adaptiveSourceProjectionMap). We're
            // creating local final variables and pass that to the lambda function
            // in order to avoid that.
            final String endpointID = sourceBitrateAllocation.endpointID;
            final long targetSSRC = sourceBitrateAllocation.targetSSRC;
            adaptiveSourceProjection
                = new AdaptiveSourceProjection(
                    diagnosticContext,
                    sourceBitrateAllocation.source, () ->
                        destinationEndpoint.getConference().requestKeyframe(
                            endpointID, targetSSRC),
                    payloadTypes,
                    logger);

            logger.debug(() -> "new source projection for " + sourceBitrateAllocation.source);

            // Route all encodings to the specified bitrate controller.
            for (RtpEncodingDesc rtpEncoding: rtpEncodings)
            {
                adaptiveSourceProjectionMap.put(
                    rtpEncoding.getPrimarySSRC(), adaptiveSourceProjection);
            }

            return adaptiveSourceProjection;
        }
    }

    /**
     * Computes the ideal and the target bitrate, limiting the target to be
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
     * @return an array of {@link SourceBitrateAllocation}.
     */
    private SourceBitrateAllocation[] allocate(
        long maxBandwidth,
        List<AbstractEndpoint> conferenceEndpoints)
    {
        SourceBitrateAllocation[] sourceBitrateAllocations
                = prioritize(conferenceEndpoints);

        if (ArrayUtils.isNullOrEmpty(sourceBitrateAllocations))
        {
            return sourceBitrateAllocations;
        }

        long oldMaxBandwidth = 0;

        int oldStateLen = 0;
        int[] oldRatedTargetIndices = new int[sourceBitrateAllocations.length];
        int[] newRatedTargetIndices = new int[sourceBitrateAllocations.length];
        Arrays.fill(newRatedTargetIndices, -1);

        while (oldMaxBandwidth != maxBandwidth)
        {
            oldMaxBandwidth = maxBandwidth;
            System.arraycopy(newRatedTargetIndices, 0,
                oldRatedTargetIndices, 0, oldRatedTargetIndices.length);

            int newStateLen = 0;
            for (int i = 0; i < sourceBitrateAllocations.length; i++)
            {
                SourceBitrateAllocation sourceBitrateAllocation
                    = sourceBitrateAllocations[i];

                if (sourceBitrateAllocation.effectiveVideoConstraints.getIdealHeight() <= 0)
                {
                    continue;
                }

                maxBandwidth += sourceBitrateAllocation.getTargetBitrate();
                sourceBitrateAllocation.improve(maxBandwidth);
                maxBandwidth -= sourceBitrateAllocation.getTargetBitrate();

                newRatedTargetIndices[i]
                    = sourceBitrateAllocation.ratedTargetIdx;
                if (sourceBitrateAllocation.getTargetIndex() > -1)
                {
                    newStateLen++;
                }

                if (sourceBitrateAllocation.ratedTargetIdx
                    < sourceBitrateAllocation.ratedPreferredIdx)
                {
                    break;
                }
            }

            if (oldStateLen > newStateLen)
            {
                // rollback state to prevent jumps in the number of forwarded
                // participants.
                for (int i = 0; i < sourceBitrateAllocations.length; i++)
                {
                    sourceBitrateAllocations[i].ratedTargetIdx
                        = oldRatedTargetIndices[i];
                }

                break;
            }

            oldStateLen = newStateLen;
        }

        // at this point, maxBandwidth is what we failed to allocate.

        return sourceBitrateAllocations;
    }

    /**
     * Returns a prioritized {@link SourceBitrateAllocation} array where
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
     * @return a prioritized {@link SourceBitrateAllocation} array where
     * selected endpoint are at the top of the array, followed by the pinned
     * endpoints, finally followed by any other remaining endpoints.
     */
    private SourceBitrateAllocation[] prioritize(
        List<AbstractEndpoint> conferenceEndpoints)
    {
        Map<String, VideoConstraints> copyOfVideoConstraintsMap = this.videoConstraintsMap;

        // Init.
        List<SourceBitrateAllocation> sourceBitrateAllocations
            = new ArrayList<>();

        int adjustedLastN = JvbLastNKt.calculateLastN(this.lastN, JvbLastNKt.jvbLastNSingleton.getJvbLastN());
        if (adjustedLastN < 0)
        {
            // If lastN is disabled, pretend lastN == szConference.
            adjustedLastN = conferenceEndpoints.size();
        }
        else
        {
            // If lastN is enabled, pretend lastN at most as big as the size
            // of the conference.
            adjustedLastN = Math.min(lastN, conferenceEndpoints.size());
        }
        if (logger.isDebugEnabled())
        {
            logger.debug("Prioritizing endpoints, adjusted last-n: " + adjustedLastN +
                ", sorted endpoint list: " +
                conferenceEndpoints.stream().map(AbstractEndpoint::getID).collect(Collectors.joining(", ")) +
                ". Endpoints constraints: " + Arrays.toString(copyOfVideoConstraintsMap.values().toArray()));
        }

        List<EndpointMultiRank> endpointMultiRankList
            = makeEndpointMultiRankList(conferenceEndpoints, copyOfVideoConstraintsMap, adjustedLastN);

        for (EndpointMultiRank endpointMultiRank : endpointMultiRankList)
        {
            AbstractEndpoint sourceEndpoint = endpointMultiRank.endpoint;
            if (sourceEndpoint.isExpired())
            {
                continue;
            }

            MediaSourceDesc[] sources
                = sourceEndpoint.getMediaSources();

            if (!ArrayUtils.isNullOrEmpty(sources))
            {
                for (MediaSourceDesc source : sources)
                {
                    sourceBitrateAllocations.add(
                        new SourceBitrateAllocation(
                            endpointMultiRank.endpoint.getID(),
                            source, endpointMultiRank.effectiveVideoConstraints));

                }


                logger.trace(() -> "Adding endpoint " + sourceEndpoint.getID() + " to allocations");
            }
        }

        return sourceBitrateAllocations.toArray(new SourceBitrateAllocation[0]);
    }

    public static List<EndpointMultiRank> makeEndpointMultiRankList(
        List<AbstractEndpoint> conferenceEndpoints,
        Map<String, VideoConstraints> videoConstraintsMap,
        int adjustedLastN)
    {
        List<EndpointMultiRank> endpointMultiRankList = new ArrayList<>(conferenceEndpoints.size());
        for (int i = 0; i < conferenceEndpoints.size(); i++)
        {
            AbstractEndpoint endpoint = conferenceEndpoints.get(i);

            VideoConstraints effectiveVideoConstraints = (i < adjustedLastN || adjustedLastN < 0)
                ? videoConstraintsMap.getOrDefault(endpoint.getID(), VideoConstraints.thumbnailVideoConstraints)
                : VideoConstraints.disabledVideoConstraints;

            endpointMultiRankList.add(new EndpointMultiRank(i, effectiveVideoConstraints, endpoint));
        }
        endpointMultiRankList.sort(new EndpointMultiRanker());
        return endpointMultiRankList;
    }

    public void setVideoConstraints(ImmutableMap<String, VideoConstraints> newVideoConstraintsMap)
    {
        if (!this.videoConstraintsMap.equals(newVideoConstraintsMap))
        {
            this.videoConstraintsMap = newVideoConstraintsMap;
            update();
        }
    }

    /**
     * Sets the LastN value.
     */
    public void setLastN(int lastN)
    {
        if (this.lastN != lastN) {
            this.lastN = lastN;

            logger.debug(() -> destinationEndpoint.getID() + " lastN has changed, updating");

            update();
        }
    }

    /**
     * Gets the LastN value.
     */
    public int getLastN()
    {
        return lastN;
    }

    /**
     * Adds a payload type.
     */
    public void addPayloadType(PayloadType payloadType)
    {
        payloadTypes.put(payloadType.getPt(), payloadType);

        if (payloadType.getEncoding() == PayloadTypeEncoding.RTX)
        {
            supportsRtx = true;
        }
    }

    /**
     * Transforms a video RTP packet.
     * @param packetInfo the video rtp packet
     * @return true if the packet was successfully transformed in place; false if
     * if the given packet is not accepted and should
     * be dropped.
     */
    public boolean transformRtp(@NotNull PacketInfo packetInfo)
    {
        VideoRtpPacket videoPacket = (VideoRtpPacket)packetInfo.getPacket();
        if (firstMediaMs == -1)
        {
            firstMediaMs = clock.instant().toEpochMilli();
        }

        Long ssrc = videoPacket.getSsrc();
        AdaptiveSourceProjection adaptiveSourceProjection
                = adaptiveSourceProjectionMap.get(ssrc);

        if (adaptiveSourceProjection == null)
        {
            return false;
        }

        try
        {
            adaptiveSourceProjection.rewriteRtp(packetInfo);

            // The rewriteRtp operation must not modify the VP8 payload.
            if (PacketInfo.Companion.getENABLE_PAYLOAD_VERIFICATION())
            {
                String expected = packetInfo.getPayloadVerification();
                String actual = videoPacket.getPayloadVerification();
                if (!"".equals(expected) && !expected.equals(actual))
                {
                    logger.warn(
                        "Payload unexpectedly modified! Expected: " + expected
                            + ", actual: " + actual);
                }
            }

            return true;
        }
        catch (RewriteException e)
        {
            logger.warn("Failed to rewrite a packet.", e);
            return false;
        }
    }

    /**
     * Return the number of endpoints whose streams are currently being forwarded.
     */
    public int numForwardedEndpoints()
    {
        return this.forwardedEndpointIds.size();
    }


    /**
     * A snapshot of the bitrate for a given {@link RtpLayerDesc}.
     */
    static class RateSnapshot
    {
        /**
         * The bitrate (in bps) of the associated {@link #layer}.
         */
        final long bps;

        /**
         * The {@link RtpLayerDesc}.
         */
        final RtpLayerDesc layer;

        /**
         * Ctor.
         *
         * @param bps The bitrate (in bps) of the associated {@link #layer}.
         * @param layer the {@link RtpLayerDesc}.
         */
        private RateSnapshot(long bps, RtpLayerDesc layer)
        {
            this.bps = bps;
            this.layer = layer;
        }
    }

    /**
     * A bitrate allocation that pertains to a specific {@link Endpoint}.
     *
     * @author George Politis
     */
    private class SourceBitrateAllocation
    {
        /**
         * The ID of the {@link Endpoint} that this instance pertains to.
         */
        private final String endpointID;

        /**
         * Indicates whether this {@link Endpoint} is on-stage/selected or not
         * at the {@link Endpoint} that owns this {@link BitrateController}.
         */
        private final VideoConstraints effectiveVideoConstraints;

        /**
         * Helper field that keeps the SSRC of the target stream.
         */
        private final long targetSSRC;

        /**
         * The first {@link MediaSourceDesc} of the {@link Endpoint} that
         * this instance pertains to.
         */
        private final MediaSourceDesc source;

        /**
         * An array that holds the stable bitrate snapshots of the
         * {@link RtpLayerDesc}s that this {@link #source} offers.
         *
         * {@link RtpLayerDesc} of {@link #source}.
         */
        private final RateSnapshot[] ratedIndices;

        /**
         * The rated quality that needs to be achieved before allocating
         * bandwidth for any of the other subsequent sources in this allocation
         * decision. The rated quality is not necessarily equal to the encoding
         * quality. For example, for the on-stage participant we consider 5
         * rated qualities:
         *
         * 0 -> 180p7.5, 1 -> 180p15, 2 -> 180p30, 3 -> 360p30, 4 -> 720p30.
         *
         * The encoding quality of the 4th rated quality is 8.
         */
        private final int ratedPreferredIdx;

        /**
         * The current rated quality target for this source. It can potentially
         * be improved in the improve step, provided there is enough bandwidth.
         */
        private int ratedTargetIdx = -1;

        /**
         * A boolean that indicates whether or not we're force pushing through
         * the bottleneck this source.
         */
        private boolean oversending = false;

        /**
         * the bitrate (in bps) of the layer that is the closest to the ideal
         * and has a bitrate, or 0 if there are no layers with a bitrate (for
         * example, the endpoint is video muted).
         */
        private final long idealBitrate;

        /**
         * Ctor.
         *
         * @param endpoint the {@link Endpoint} that this bitrate allocation
         * pertains to.
         * @param source the {@link MediaSourceDesc} that this bitrate
         * allocation pertains to.
         * @param fitsInLastN a flag indicating whether or not the endpoint is
         * in LastN.
         * @param selected a flag indicating whether or not the endpoint is
         * selected.
         */
        private SourceBitrateAllocation(
            String endpointID,
            MediaSourceDesc source,
            VideoConstraints effectiveVideoConstraints)
        {
            this.endpointID = endpointID;
            this.effectiveVideoConstraints = effectiveVideoConstraints;
            this.source = source;

            if (source == null)
            {
                this.targetSSRC = -1;
            }
            else
            {
                this.targetSSRC = source.getPrimarySSRC();
            }

            if (targetSSRC == -1 || effectiveVideoConstraints.getIdealHeight() <= 0)
            {
                ratedPreferredIdx = -1;
                idealBitrate = 0;
                ratedIndices = EMPTY_RATE_SNAPSHOT_ARRAY;
                return;
            }

            long nowMs = clock.instant().toEpochMilli();
            List<RateSnapshot> ratesList = new ArrayList<>();
            // Initialize the list of flows that we will consider for sending
            // for this source. For example, for the on-stage participant we
            // consider 720p@30fps, 360p@30fps, 180p@30fps, 180p@15fps,
            // 180p@7.5fps while for the thumbnails we consider 180p@30fps,
            // 180p@15fps and 180p@7.5fps
            int ratedPreferredIdx = 0;
            long idealBps = 0;
            for (RtpLayerDesc layer : source.getRtpLayers())
            {

                int idealHeight = effectiveVideoConstraints.getIdealHeight();
                // We don't want to exceed the ideal resolution but we also
                // want to make sure we have at least 1 rated encoding.
                if (idealHeight >= 0 && layer.getHeight() > idealHeight
                    && !ratesList.isEmpty())
                {
                    continue;
                }

                // For the "selected" participant we favor frame rate over
                // resolution. We include all temporal layers up to the
                // preferred resolution, but only consider the preferred
                // frame-rate with higher-than-preferred resolutions. In
                // practice today this translates to 180p7.5fps, 180p15fps,
                // 180p30fps, 360p30fps and 720p30fps.

                boolean lessThanPreferredResolution
                    = layer.getHeight() < effectiveVideoConstraints.getPreferredHeight();
                boolean lessThanOrEqualIdealResolution
                    = layer.getHeight() <= effectiveVideoConstraints.getIdealHeight();
                boolean atLeastPreferredFps
                    = layer.getFrameRate() >= effectiveVideoConstraints.getPreferredFps();

                if ((lessThanPreferredResolution
                    || (lessThanOrEqualIdealResolution && atLeastPreferredFps))
                    || ratesList.isEmpty())
                {
                    long layerBitrateBps = layer.getBitrateBps(nowMs);
                    if (layerBitrateBps > 0)
                    {
                        idealBps = layerBitrateBps;
                    }
                    ratesList.add(
                        new RateSnapshot(layerBitrateBps, layer));
                }
                else {
                    logger.trace(() ->
                        "Not including layer " + layer + " in rate snapshot: " +
                            "layer height " + layer.getHeight() + ", fps " + layer.getFrameRate() +
                            " with video constraints " +
                            effectiveVideoConstraints);
                }

                if (layer.getHeight() <= effectiveVideoConstraints.getPreferredHeight())
                {
                    // The improve step below will "eagerly" try to allocate
                    // up-to the ratedPreferredIdx before moving on to the next
                    // track. Eagerly means we consume all available bandwidth
                    // up to the preferred resolution, leaving higher-frame
                    // rates as an option for subsequent improvement steps.
                    //
                    // NOTE that the above comment suggests that the prefix
                    // "preferred" in the preferredFps and preferredHeight
                    // params has different semantics: In the preferredHeight
                    // param it means "eagerly allocate up to the preferred
                    // resolution" whereas in the preferredFps param it means
                    // "only consider encodings with at least preferredFps" once
                    // we've reached the preferredHeight.
                    ratedPreferredIdx = ratesList.size() - 1;
                }
            }
            
            this.idealBitrate = idealBps;

            if (timeSeriesLogger.isTraceEnabled())
            {
                DiagnosticContext.TimeSeriesPoint ratesTimeSeriesPoint
                    = diagnosticContext.makeTimeSeriesPoint("calculated_rates")
                    .addField("remote_endpoint_id", endpointID);
                for (RateSnapshot rateSnapshot : ratesList) {
                    ratesTimeSeriesPoint.addField(
                        Integer.toString(rateSnapshot.layer.getIndex()),
                        rateSnapshot.bps);
                }
                timeSeriesLogger.trace(ratesTimeSeriesPoint);
            }

            this.ratedPreferredIdx = ratedPreferredIdx;
            ratedIndices = ratesList.toArray(new RateSnapshot[0]);
            // TODO Determining the rated ideal index needs some work.
            // The ideal rated quality is constrained by the viewport of the
            // endpoint. For example, on a mobile device we should probably not
            // send anything above 360p (not even the on-stage participant). On
            // a laptop computer 720p seems reasonable and on a big screen 1080p
            // or above.
        }

        /**
         * Computes the ideal and the target bitrate, limiting the target to
         * be less than bandwidth estimation specified as an argument.
         *
         * @param maxBps the maximum bitrate (in bps) that the target subjective
         * quality can have.
         */
        private void improve(long maxBps)
        {
            if (ratedIndices.length == 0)
            {
                return;
            }

            if (ratedTargetIdx == -1 && ratedPreferredIdx > -1)
            {
                if (!BitrateControllerConfig.enableOnstageVideoSuspend())
                {
                    ratedTargetIdx = 0;
                    oversending = ratedIndices[0].bps > maxBps;
                }

                // Boost on stage participant to 360p, if there's enough bw.
                for (int i = ratedTargetIdx + 1; i < ratedIndices.length; i++)
                {
                    if (i > ratedPreferredIdx || maxBps < ratedIndices[i].bps)
                    {
                        break;
                    }

                    ratedTargetIdx = i;
                }
            }
            else
            {
                // Try the next element in the ratedIndices array.
                if (ratedTargetIdx + 1 < ratedIndices.length
                    && ratedIndices[ratedTargetIdx + 1].bps < maxBps)
                {
                    ratedTargetIdx++;
                }
            }

            if (ratedTargetIdx > -1)
            {
                // if there's a better subjective quality with the same or less
                // bitrate than the current target quality, make it the target.
                // i.e. set the target to the next best available quality with
                // the least possible bitrate.
                //
                // For example, if 1080p@15fps is configured as a better
                // subjective quality than 720p@30fps (i.e. it sits on a higher
                // index in the ratedIndices array) and the bitrate that we
                // measure for the 1080p stream is less than the bitrate that we
                // measure for the 720p stream, then we "jump over" the 720p
                // stream and immediately select the 1080p stream.
                for (int i = ratedTargetIdx + 1; i < ratedIndices.length; i++)
                {
                    if (ratedIndices[i].bps > 0
                        && ratedIndices[i].bps <= ratedIndices[ratedTargetIdx].bps)
                    {
                        ratedTargetIdx = i;
                    }
                }
            }
        }

        /**
         * Gets the target bitrate (in bps) for this endpoint allocation.
         *
         * @return the target bitrate (in bps) for this endpoint allocation.
         */
        private long getTargetBitrate()
        {
            return ratedTargetIdx != -1 ? ratedIndices[ratedTargetIdx].bps : 0;
        }

        /**
         * @return the bitrate (in bps) of the layer that is the closest to
         * the ideal and has a bitrate, or 0 if there are no layers with a
         * bitrate (for example, the endpoint is video muted).
         */
        private long getIdealBitrate()
        {
            return idealBitrate;
        }

        /**
         * Gets the target quality for this source.
         *
         * @return the target quality for this source.
         */
        private int getTargetIndex()
        {
            // figures out the quality of the layer of the target rated
            // quality.
            return ratedTargetIdx != -1
                ? ratedIndices[ratedTargetIdx].layer.getIndex() : -1;
        }

        /**
         * Gets the preferred quality for this source.
         *
         * @return the preferred quality for this source.
         */
        private int getPreferredIndex()
        {
            // figures out the quality of the layer of the target rated
            // quality.
            return ratedPreferredIdx != -1
                ? ratedIndices[ratedPreferredIdx].layer.getIndex() : -1;
        }

        /**
         * Gets the ideal quality for this source.
         *
         * @return the ideal quality for this source.
         */
        private int getIdealIndex()
        {
            // figures out the quality of the layer of the ideal rated
            // quality.
            return ratedIndices.length != 0
                ? ratedIndices[ratedIndices.length - 1].layer.getIndex() : -1;
        }
    }
}
