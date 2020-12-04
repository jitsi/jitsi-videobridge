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

import edu.umd.cs.findbugs.annotations.*;
import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.cc.config.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import java.lang.*;
import java.lang.SuppressWarnings;
import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;

import static org.jitsi.videobridge.cc.allocation.PrioritizeKt.prioritize;

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
    private static boolean bweChangeIsLargerThanThreshold(long previousBwe, long currentBwe)
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
     * The last bandwidth estimation that we got. This is used to limit the
     * resolution changes due to bandwidth changes. We react to bandwidth
     * changes greater than BWE_CHANGE_THRESHOLD_PCT/100 of the last bandwidth
     * estimation.
     */
    private long lastBwe = -1;

    /**
     * The list of endpoints ids ordered by speech activity.
     */
    private List<String> sortedEndpointIds;

    /**
     * A modified copy of the original video constraints map, augmented with video constraints for the endpoints that
     * fall outside of the last-n set + endpoints not announced in the videoConstraintsMap.
     */
    private Map<String, VideoConstraints2> effectiveConstraintsMap = Collections.emptyMap();

    private final Clock clock;

    private final EventEmitter<EventHandler> eventEmitter = new EventEmitter<>();

    private final Supplier<List<T>> endpointsSupplier;
    private final Supplier<Boolean> trustBwe;

    private AllocationSettings allocationSettings = new AllocationSettings();

    /**
     * The last time {@link BitrateAllocator#update()} was called
     */
    private Instant lastUpdateTime = Instant.MIN;

    @NotNull
    private Allocation allocation = new Allocation(Collections.emptySet());

    /**
     * Initializes a new {@link BitrateAllocator} instance which is to
     * belong to a particular {@link Endpoint}.
     */
    BitrateAllocator(
            EventHandler eventHandler,
            Supplier<List<T>> endpointsSupplier,
            Supplier<Boolean> trustBwe,
            Logger parentLogger,
            Clock clock)
    {
        this.logger = parentLogger.createChildLogger(BitrateAllocator.class.getName());
        this.clock = clock;
        this.trustBwe = trustBwe;

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
        debugState.put("trustBwe", BitrateControllerConfig.trustBwe());
        debugState.put("lastBwe", lastBwe);
        debugState.put("allocationSettings", allocationSettings.toString());
        debugState.put("effectiveVideoConstraints", effectiveConstraintsMap);
        return debugState;
    }

    @NotNull
    Allocation getAllocation()
    {
        return allocation;
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
        return trustBwe.get() ? lastBwe : Long.MAX_VALUE;
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
        if (!bweChangeIsLargerThanThreshold(lastBwe, newBandwidthBps))
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
     * specific to this particular endpoint, e.g. selected, though
     * this method SHOULD be invoked when those things change; they will be
     * taken into account in this flow)
     */
    synchronized void endpointOrderingChanged(List<String> conferenceEndpoints)
    {
        logger.debug(() -> "Endpoint ordering has changed, updating.");

        // TODO: Maybe suppress calling update() unless the order actually changed?
        sortedEndpointIds = conferenceEndpoints;
        update();
    }

    void update(AllocationSettings allocationSettings)
    {
        this.allocationSettings = allocationSettings;
        update();
    }

    /**
     * Computes a new bitrate allocation for every endpoint in the conference,
     * and updates the state of this instance so that bitrate allocation is
     * eventually met.
     */
    private synchronized void update()
    {
        lastUpdateTime = clock.instant();

        if (sortedEndpointIds == null || sortedEndpointIds.isEmpty())
        {
            return;
        }

        // Order the endpoints by selection, followed by speech activity.
        List<T> sortedEndpoints
                = prioritize(sortedEndpointIds, allocationSettings.getSelectedEndpoints(), endpointsSupplier.get());

        // Compute the bitrate allocation.
        Allocation newAllocation = allocate(getAvailableBandwidth(), sortedEndpoints);

        boolean allocationChanged = !allocation.isTheSameAs(newAllocation);
        if (allocationChanged)
        {
            eventEmitter.fireEvent(handler -> {
                handler.allocationChanged(newAllocation);
                return Unit.INSTANCE;
            });
        }
        allocation = newAllocation;

        // Now that the bitrate allocation update is complete, we wish to compute the "effective" sender video
        // constraints map which are used in layer suspension.
        Map<String, VideoConstraints2> newEffectiveConstraints = new HashMap<>();
        for (SingleAllocation singleAllocation : newAllocation.getAllocations())
        {
            newEffectiveConstraints.put(
                    singleAllocation.getEndpointId(),
                    singleAllocation.getEffectiveVideoConstraints());
        }

        if (!newEffectiveConstraints.equals(effectiveConstraintsMap))
        {
            Map<String, VideoConstraints2> oldEffectiveConstraints = effectiveConstraintsMap;
            effectiveConstraintsMap = newEffectiveConstraints;
            eventEmitter.fireEvent(handler ->
            {
                handler.effectiveVideoConstraintsChanged(oldEffectiveConstraints, newEffectiveConstraints);
                return Unit.INSTANCE;
            });
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
     * @return an array of {@link SingleSourceAllocation}.
     */
    private synchronized @NotNull Allocation allocate(
            long maxBandwidth,
            List<T> conferenceEndpoints)
    {
        List<SingleSourceAllocation> sourceBitrateAllocations = createAllocations(conferenceEndpoints);

        if (sourceBitrateAllocations.isEmpty())
        {
            return new Allocation(Collections.emptySet());
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

        return new Allocation(
                sourceBitrateAllocations.stream().map(SingleSourceAllocation::getResult).collect(Collectors.toSet()));
    }


    /**
     * Returns a prioritized {@link SingleSourceAllocation} array where
     * selected endpoint are at the top of the array followed by any other remaining endpoints. The
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
     * selected endpoint are at the top of the array,
     * followed by any other remaining endpoints.
     */
    private synchronized @NotNull List<SingleSourceAllocation> createAllocations(List<T> conferenceEndpoints)
    {
        // Init.
        List<SingleSourceAllocation> sourceBitrateAllocations = new ArrayList<>(conferenceEndpoints.size());

        int adjustedLastN = JvbLastNKt.calculateLastN(
                allocationSettings.getLastN(),
                JvbLastNKt.jvbLastNSingleton.getJvbLastN());
        if (adjustedLastN < 0)
        {
            // If lastN is disabled, pretend lastN == szConference.
            adjustedLastN = conferenceEndpoints.size();
        }
        else
        {
            // If lastN is enabled, pretend lastN at most as big as the size
            // of the conference.
            adjustedLastN = Math.min(allocationSettings.getLastN(), conferenceEndpoints.size());
        }
        if (logger.isDebugEnabled())
        {
            logger.debug("Prioritizing endpoints, adjusted last-n: " + adjustedLastN +
                    ", sorted endpoint list: " +
                    conferenceEndpoints.stream().map(MediaSourceContainer::getId).collect(Collectors.joining(", ")) +
                    ". Endpoints constraints: "
                    + Arrays.toString(allocationSettings.getVideoConstraints().values().toArray()));
        }

        Map<String, VideoConstraints> videoConstraintsMap = allocationSettings.getVideoConstraints();
        for (int i = 0; i < conferenceEndpoints.size(); i++)
        {
            MediaSourceContainer endpoint = conferenceEndpoints.get(i);
            MediaSourceDesc[] sources = endpoint.getMediaSources();
            VideoConstraints effectiveVideoConstraints
                = videoConstraintsMap.getOrDefault(endpoint.getId(), VideoConstraints.thumbnailVideoConstraints);
            if (adjustedLastN > -1 && i >= adjustedLastN)
            {
                effectiveVideoConstraints = VideoConstraints.disabledVideoConstraints;
            }

            if (!ArrayUtils.isNullOrEmpty(sources))
            {
                for (MediaSourceDesc source : sources)
                {
                    sourceBitrateAllocations.add(
                            new SingleSourceAllocation(
                                    endpoint.getId(),
                                    source,
                                    effectiveVideoConstraints,
                                    clock));
                }
            }
        }

        return sourceBitrateAllocations;
    }

    void maybeUpdate()
    {
        if (Duration.between(lastUpdateTime, clock.instant())
                .compareTo(BitrateControllerConfig.maxTimeBetweenCalculations()) > 0)
        {
            logger.debug("Forcing an update");
            TaskPools.CPU_POOL.submit((Runnable) this::update);
        }
    }

    public interface EventHandler
    {
        default void allocationChanged(@NotNull Allocation allocation) {}
        default void effectiveVideoConstraintsChanged(
                @NotNull Map<String, VideoConstraints2> oldEffectiveConstraints,
                @NotNull Map<String, VideoConstraints2> newEffectiveConstraints) {}
    }
}
