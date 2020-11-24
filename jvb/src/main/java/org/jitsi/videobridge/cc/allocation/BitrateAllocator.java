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
package org.jitsi.videobridge.cc.allocation;

import com.google.common.collect.*;
import edu.umd.cs.findbugs.annotations.*;
import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.nlj.format.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.cc.AdaptiveSourceProjection;
import org.jitsi.videobridge.cc.config.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import java.lang.*;
import java.lang.SuppressWarnings;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * The {@link BitrateAllocator} is attached to a destination {@code
 * Endpoint} and its purpose is 1st to selectively drop incoming packets
 * based on their source {@link Endpoint} and 2nd to rewrite the accepted
 * packets so that they form a correct RTP stream without gaps in their
 * sequence numbers nor in their timestamps.
 * <p>
 * In the Selective Forwarding Middlebox (SFM) topology [RFC7667], senders are
 * sending multiple bitstreams of (they multistream) the same video source
 * (using either with simulcast or scalable video coding) and the SFM decides
 * which bitstream to send to which receiver.
 * <p>
 * For example, suppose we're in a 3-way simulcast-enabled video call (so 3
 * source {@link Endpoint}s) where the endpoints are sending two
 * non-scalable RTP streams of the same video source. The bitrate controller,
 * will chose to forward only one RTP stream at a time to a specific destination
 * endpoint.
 * <p>
 * With scalable video coding (SVC) a bitstream can be broken down into multiple
 * sub-bitstreams of lower frame rate (that we call temporal layers or TL)
 * and/or lower resolution (that we call spatial layers or SL). The exact
 * dependencies between the different layers of an SVC bitstream are codec
 * specific but, as a general rule of thumb higher temporal/spatial layers
 * depend on lower temporal/spatial layers creating a dependency graph.
 * <p>
 * For example, a 720p@30fps VP8 scalable bitstream can be broken down into 3
 * sub-bitstreams: one 720p@30fps layer (the highest temporal layer), that
 * depends on a 720p@15fps layer that depends on a 720p7.5fps layer (the lowest
 * temporal layer). In order for the decoder to be able decode the highest
 * temporal layer, it needs to get all the packets of its direct and transitive
 * dependencies, so of both the other two layers. In this simple case we have a
 * linked list of dependencies with the the lowest temporal layer as root and
 * the highest temporal layer as a leaf.
 * <p>
 * In order for the SFM to be able to filter the incoming packets we need to be
 * able to assign incoming packets to "flows" with properties that allow us to
 * define filtering rules. In this implementation a flow is represented by an
 * {@link RtpLayerDesc} and its properties are bitrate and subjective quality
 * index. Specifically, the incoming packets belong to some {@link FrameDesc},
 * which belongs to some {@link RtpLayerDesc}, which belongs to some {@link
 * MediaSourceDesc}, which belongs to the source {@link Endpoint}.
 * This hierarchy allows for fine-grained filtering up to the {@link FrameDesc}
 * level.
 * <p>
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
public class BitrateAllocator<T extends MediaSourceContainer>
{
    /**
     * Returns a boolean that indicates whether or not the current bandwidth
     * estimation (in bps) has changed above the configured threshold (in
     * percent) {@link #BWE_CHANGE_THRESHOLD_PCT} with respect to the previous
     * bandwidth estimation.
     *
     * @param previousBwe the previous bandwidth estimation (in bps).
     * @param currentBwe the current bandwidth estimation (in bps).
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

        return deltaBwe > 0 || deltaBwe < -1 * previousBwe * BitrateControllerConfig.bweChangeThreshold();
    }

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The {@link List} of endpoints that are currently being forwarded,
     * represented by their IDs. Required for backwards compatibility with
     * existing LastN code.
     */
    private Set<String> forwardedEndpointIds = Collections.emptySet();

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
     * The map of endpoint id to video constraints that contains the video
     * constraints to respect when allocating bandwidth for a specific endpoint.
     */
    private ImmutableMap<String, VideoConstraints> videoConstraintsMap = ImmutableMap.of();

    /**
     * A modified copy of the original video constraints map, augmented with video constraints for the endpoints that
     * fall outside of the last-n set + endpoints not announced in the videoConstraintsMap.
     */
    private Map<String, VideoConstraints> effectiveConstraintsMap = Collections.emptyMap();

    /**
     * The last-n value for the endpoint to which this {@link BitrateAllocator}
     * belongs
     */
    private int lastN = -1;

    /**
     * The ID of the endpoint to which this {@link BitrateAllocator} belongs
     */
    private final String destinationEndpointId;

    // NOTE(george): this flag acts as an approximation for determining whether
    // or not adaptivity/probing is supported. Eventually we need to scrap this
    // and implement something cleaner, i.e. disable adaptivity if the endpoint
    // hasn't signaled `goog-remb` nor `transport-cc`.
    //
    // Unfortunately the channel iq from jicofo lists `goog-remb` and
    // `transport-cc` support, even tho the jingle from firefox doesn't (which
    // is the main use case for wanting to disable adaptivity).
    private boolean supportsRtx = false;

    private final Clock clock;

    private final EventEmitter<BitrateController.EventHandler> eventEmitter = new EventEmitter<>();

    private final Supplier<List<T>> endpointsSupplier;

    /**
     * The last time {@link BitrateAllocator#update()} was called
     */
    private Instant lastUpdateTime = Instant.MIN;

    /**
     * Keep track of how much time we spend knowingly oversending (due to enableOnstageVideoSuspend being false)
     */
    final BooleanStateTimeTracker oversendingTimeTracker = new BooleanStateTimeTracker();

    private final BitrateControllerPacketHandler packetHandler;

    /**
     * Initializes a new {@link BitrateAllocator} instance which is to
     * belong to a particular {@link Endpoint}.
     */
    BitrateAllocator(
            String destinationEndpointId,
            BitrateController.EventHandler eventHandler,
            Supplier<List<T>> endpointsSupplier,
            Logger parentLogger,
            Clock clock,
            BitrateControllerPacketHandler packetHandler
    )
    {
        this.destinationEndpointId = destinationEndpointId;
        this.logger = parentLogger.createChildLogger(BitrateAllocator.class.getName());
        this.clock = clock;
        this.packetHandler = packetHandler;

        this.endpointsSupplier = endpointsSupplier;
        eventEmitter.addHandler(eventHandler);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that
     * are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    @SuppressFBWarnings(
            value = "IS2_INCONSISTENT_SYNC",
            justification = "We intentionally avoid synchronizing while reading fields only used in debug output.")
    JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("forwardedEndpoints", forwardedEndpointIds.toString());
        debugState.put("trustBwe", BitrateControllerConfig.trustBwe());
        debugState.put("lastBwe", lastBwe);
        debugState.put("videoConstraints", videoConstraintsMap);
        debugState.put("effectiveVideoConstraints", effectiveConstraintsMap);
        debugState.put("lastN", lastN);
        debugState.put("supportsRtx", supportsRtx);
        debugState.put("oversending", oversendingTimeTracker.getState());
        debugState.put("total_oversending_time_secs", oversendingTimeTracker.totalTimeOn().getSeconds());
        return debugState;
    }

    /**
     * Get a snapshot of the following data:
     * 1) The current target bitrate we're trying to send across our sources
     * 2) The ideal bitrate we could possibly send, given our sources
     * 3) The ssrcs we're currently forwarding
     *
     * @return the snapshot containing that info
     */
    BitrateControllerStatusSnapshot getStatusSnapshot()
    {
        List<Long> activeSsrcs = new ArrayList<>();
        long totalTargetBps = 0, totalIdealBps = 0;
        long nowMs = clock.instant().toEpochMilli();
        for (MediaSourceDesc incomingSource : endpointsSupplier.get().stream()
                .filter(e -> !destinationEndpointId.equals(e.getId()))
                .map(MediaSourceContainer::getMediaSources)
                .flatMap(Arrays::stream)
                .filter(MediaSourceDesc::hasRtpLayers)
                .collect(Collectors.toList()))
        {

            long primarySsrc = incomingSource.getPrimarySSRC();
            AdaptiveSourceProjection adaptiveSourceProjection
                    = packetHandler.getAdaptiveSourceProjectionMap().getOrDefault(primarySsrc, null);

            if (adaptiveSourceProjection == null)
            {
                logger.debug(destinationEndpointId + " is missing " +
                        "an adaptive source projection for endpoint=" +
                        incomingSource.getOwner() + ", ssrc=" + primarySsrc);
                continue;
            }

            long targetBps
                    = (long) incomingSource.getBitrate(nowMs, adaptiveSourceProjection.getTargetIndex()).getBps();
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
            totalIdealBps += incomingSource.getBitrate(nowMs, adaptiveSourceProjection.getIdealIndex()).getBps();
        }
        return new BitrateControllerStatusSnapshot(totalTargetBps, totalIdealBps, activeSsrcs);
    }

    /**
     * We can't just use {@link #lastBwe} to get the most recent bandwidth
     * estimate as we must take into account the initial join period where
     * we don't 'trust' the bwe anyway, as well as whether or not we'll
     * trust it at all (we ignore it always if the endpoint doesn't support
     * RTX, which is our way of filtering out endpoints that don't do
     * probing)
     *
     * @return the estimated bandwidth, in bps, based on the last update
     * we received and taking into account whether or not we 'trust' the
     * bandwidth estimate.
     */
    private long getAvailableBandwidth()
    {
        boolean trustBwe = BitrateControllerConfig.trustBwe();
        if (trustBwe)
        {
            // Ignore the bandwidth estimations in the first 10 seconds because
            // the REMBs don't ramp up fast enough. This needs to go but it's
            // related to our GCC implementation that needs to be brought up to
            // speed.
            if (packetHandler.timeSinceFirstMedia() < 10000)
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
     *
     * @param newBandwidthBps the newly estimated bandwidth in bps
     */
    void bandwidthChanged(long newBandwidthBps)
    {
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
    synchronized void endpointOrderingChanged(List<String> conferenceEndpoints)
    {
        logger.debug(() -> " endpoint ordering has changed, updating");

        List<String> newSortedEndpointIds = new ArrayList<>(conferenceEndpoints);
        newSortedEndpointIds.remove(destinationEndpointId);
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
        long bweBps = getAvailableBandwidth();

        if (sortedEndpointIds == null || sortedEndpointIds.isEmpty())
        {
            return;
        }

        List<T> sortedEndpoints = new ArrayList<>(sortedEndpointIds.size());
        List<T> conferenceEndpoints = endpointsSupplier.get();
        for (String endpointId : sortedEndpointIds)
        {
            conferenceEndpoints.stream()
                    .filter(e -> e.getId().equals(endpointId))
                    .findFirst().ifPresent(sortedEndpoints::add);
        }

        // Compute the bitrate allocation.
        List<SingleAllocation> allocations = allocate(bweBps, sortedEndpoints);
        if (!allocations.isEmpty())
        {
            // If we're oversending, we only do it with a single stream, so check the first
            // one we're forwarding and see if it required and oversend.  Note: this is not
            // as flexible as adding up the bitrates and seeing if they exceed the bwe, but
            // it's more efficient than summing them all up.
            if (allocations.get(0).getOversending())
            {
                oversendingTimeTracker.on();
            }
            else
            {
                oversendingTimeTracker.off();
            }
        }

        // Update the the controllers based on the allocation and send a
        // notification to the client the set of forwarded endpoints has
        // changed.
        Set<String> oldForwardedEndpointIds = forwardedEndpointIds;
        Set<String> newForwardedEndpointIds = new HashSet<>();

        // Now that the bitrate allocation update is complete, we wish to compute the "effective" sender video
        // constraints map which are used in layer suspension.
        Map<String, VideoConstraints> newEffectiveConstraints = new HashMap<>();

        boolean changed = false;
        if (!allocations.isEmpty())
        {
            for (SingleAllocation singleAllocation : allocations)
            {
                newEffectiveConstraints.put(
                        singleAllocation.getEndpointId(),
                        singleAllocation.getEffectiveVideoConstraints());

                LayerSnapshot targetLayer = singleAllocation.getTargetLayer();
                int sourceTargetIdx = targetLayer == null ? -1 : targetLayer.getLayer().getIndex();
                LayerSnapshot idealLayer = singleAllocation.getIdealLayer();
                int sourceIdealIdx = idealLayer == null ? -1 : idealLayer.getLayer().getIndex();

                // Review this.
                AdaptiveSourceProjection adaptiveSourceProjection
                        = packetHandler.lookupOrCreateAdaptiveSourceProjection(singleAllocation);

                if (adaptiveSourceProjection != null)
                {
                    changed |= adaptiveSourceProjection.setTargetIndex(sourceTargetIdx);
                    changed |= adaptiveSourceProjection.setIdealIndex(sourceIdealIdx);
                }

                if (sourceTargetIdx > -1)
                {
                    newForwardedEndpointIds.add(singleAllocation.getEndpointId());
                }
            }
        }
        else
        {
            for (AdaptiveSourceProjection adaptiveSourceProjection
                    : packetHandler.getAdaptiveSourceProjectionMap().values())
            {
                adaptiveSourceProjection.setTargetIndex(RtpLayerDesc.SUSPENDED_INDEX);
                adaptiveSourceProjection.setIdealIndex(RtpLayerDesc.SUSPENDED_INDEX);
            }
        }
        if (changed)
        {
            eventEmitter.fireEvent(handler ->
            {
                handler.allocationChanged(new Allocation(allocations));
                return Unit.INSTANCE;
            });
        }

        if (!newForwardedEndpointIds.equals(oldForwardedEndpointIds))
        {
            // TODO(george) bring back sending this message on message transport
            //  connect
            eventEmitter.fireEvent(handler ->
            {
                handler.forwardedEndpointsChanged(newForwardedEndpointIds);
                return Unit.INSTANCE;
            });
        }

        if (!newEffectiveConstraints.equals(effectiveConstraintsMap))
        {
            ImmutableMap<String, VideoConstraints>
                    oldEffectiveConstraints = ImmutableMap.copyOf(effectiveConstraintsMap);
            // TODO make the call outside the synchronized block.
            effectiveConstraintsMap = newEffectiveConstraints;
            eventEmitter.fireEvent(handler ->
            {
                handler.effectiveVideoConstraintsChanged(
                        oldEffectiveConstraints, ImmutableMap.copyOf(newEffectiveConstraints));
                return Unit.INSTANCE;
            });
        }

        this.forwardedEndpointIds = newForwardedEndpointIds;
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
     * @return an array of {@link SingleSourceAllocation}.
     */
    private synchronized @NotNull List<SingleAllocation> allocate(
            long maxBandwidth,
            List<T> conferenceEndpoints)
    {
        List<SingleSourceAllocation> sourceBitrateAllocations = prioritize(conferenceEndpoints);

        if (sourceBitrateAllocations.isEmpty())
        {
            return Collections.emptyList();
        }

        long oldMaxBandwidth = -1;

        int oldStateLen = 0;
        int[] oldRatedTargetIndices = new int[sourceBitrateAllocations.size()];
        int[] newRatedTargetIndices = new int[sourceBitrateAllocations.size()];
        Arrays.fill(newRatedTargetIndices, -1);

        while (oldMaxBandwidth != maxBandwidth)
        {
            oldMaxBandwidth = maxBandwidth;
            System.arraycopy(newRatedTargetIndices, 0, oldRatedTargetIndices, 0, oldRatedTargetIndices.length);

            int newStateLen = 0;
            for (int i = 0; i < sourceBitrateAllocations.size(); i++)
            {
                SingleSourceAllocation sourceBitrateAllocation = sourceBitrateAllocations.get(i);

                if (sourceBitrateAllocation.effectiveVideoConstraints.getIdealHeight() <= 0)
                {
                    continue;
                }

                maxBandwidth += sourceBitrateAllocation.getTargetBitrate();
                sourceBitrateAllocation.improve(maxBandwidth);
                // We will "force" forward the lowest layer of the highest priority (first in line)
                // participant if we weren't able to allocate any bandwidth for it and
                // enableOnstageVideoSuspend is false.
                if (i == 0 &&
                        sourceBitrateAllocation.ratedTargetIdx < 0 &&
                        !BitrateControllerConfig.enableOnstageVideoSuspend())
                {
                    sourceBitrateAllocation.ratedTargetIdx = 0;
                    sourceBitrateAllocation.oversending = true;
                }
                maxBandwidth -= sourceBitrateAllocation.getTargetBitrate();

                newRatedTargetIndices[i] = sourceBitrateAllocation.ratedTargetIdx;
                if (sourceBitrateAllocation.getTargetIndex() > -1)
                {
                    newStateLen++;
                }

                if (sourceBitrateAllocation.ratedTargetIdx < sourceBitrateAllocation.ratedPreferredIdx)
                {
                    break;
                }
            }

            if (oldStateLen > newStateLen)
            {
                // rollback state to prevent jumps in the number of forwarded
                // participants.
                for (int i = 0; i < sourceBitrateAllocations.size(); i++)
                {
                    sourceBitrateAllocations.get(i).ratedTargetIdx = oldRatedTargetIndices[i];
                }

                break;
            }

            oldStateLen = newStateLen;
        }

        // at this point, maxBandwidth is what we failed to allocate.

        return sourceBitrateAllocations.stream().map(SingleSourceAllocation::getResult).collect(Collectors.toList());
    }

    /**
     * Returns a prioritized {@link SingleSourceAllocation} array where
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
     * @return a prioritized {@link SingleSourceAllocation} array where
     * selected endpoint are at the top of the array, followed by the pinned
     * endpoints, finally followed by any other remaining endpoints.
     */
    private synchronized @NotNull List<SingleSourceAllocation> prioritize(List<T> conferenceEndpoints)
    {
        // Init.
        List<SingleSourceAllocation> sourceBitrateAllocations = new ArrayList<>(conferenceEndpoints.size());

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
                    conferenceEndpoints.stream().map(MediaSourceContainer::getId).collect(Collectors.joining(", ")) +
                    ". Endpoints constraints: " + Arrays.toString(videoConstraintsMap.values().toArray()));
        }

        List<EndpointMultiRank<T>> endpointMultiRankList
                = EndpointMultiRank.makeEndpointMultiRankList(conferenceEndpoints, videoConstraintsMap, adjustedLastN);

        for (EndpointMultiRank<T> endpointMultiRank : endpointMultiRankList)
        {
            MediaSourceContainer sourceEndpoint = endpointMultiRank.endpoint;

            MediaSourceDesc[] sources = sourceEndpoint.getMediaSources();

            if (!ArrayUtils.isNullOrEmpty(sources))
            {
                for (MediaSourceDesc source : sources)
                {
                    sourceBitrateAllocations.add(
                            new SingleSourceAllocation(
                                    endpointMultiRank.endpoint.getId(),
                                    source,
                                    endpointMultiRank.effectiveVideoConstraints,
                                    clock));
                }


                logger.trace(() -> "Adding endpoint " + sourceEndpoint.getId() + " to allocations");
            }
        }

        return sourceBitrateAllocations;
    }

    void setVideoConstraints(ImmutableMap<String, VideoConstraints> newVideoConstraintsMap)
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
    void setLastN(int lastN)
    {
        if (this.lastN != lastN)
        {
            this.lastN = lastN;

            logger.debug(() -> destinationEndpointId + " lastN has changed, updating");

            update();
        }
    }

    /**
     * Gets the LastN value.
     */
    int getLastN()
    {
        return lastN;
    }

    /**
     * Adds a payload type.
     */
    void addPayloadType(PayloadType payloadType)
    {
        if (payloadType.getEncoding() == PayloadTypeEncoding.RTX)
        {
            supportsRtx = true;
        }
    }

    /**
     * Return the number of endpoints whose streams are currently being forwarded.
     */
    int numForwardedEndpoints()
    {
        return forwardedEndpointIds.size();
    }

    void maybeUpdate()
    {
        if (Duration.between(lastUpdateTime, clock.instant())
                .compareTo(BitrateControllerConfig.maxTimeBetweenCalculations()) > 0)
        {
            logger.debug("Forcing an update");
            TaskPools.CPU_POOL.submit(this::update);
        }
    }
}
