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
import org.jitsi.utils.event.*;
import org.jitsi.utils.logging.*;
import org.jitsi.utils.logging2.Logger;
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
 *
 * @author George Politis
 */
public class BandwidthAllocator<T extends MediaSourceContainer>
{
    /**
     * Returns a boolean that indicates whether or not the current bandwidth estimation (in bps) has changed above the
     * configured threshold with respect to the previous bandwidth estimation.
     *
     * @param previousBwe the previous bandwidth estimation (in bps).
     * @param currentBwe the current bandwidth estimation (in bps).
     * @return true if the bandwidth has changed above the configured threshold, * false otherwise.
     */
    private static boolean bweChangeIsLargerThanThreshold(long previousBwe, long currentBwe)
    {
        if (previousBwe == -1 || currentBwe == -1)
        {
            return true;
        }

        long deltaBwe = currentBwe - previousBwe;

        // If the bwe has increased, we should act upon it, otherwise we may end up in this broken situation: Suppose
        // that the target bitrate is 2.5Mbps, and that the last bandwidth allocation was performed with a 2.4Mbps
        // bandwidth estimate.  The bridge keeps probing and, suppose that, eventually the bandwidth estimate reaches
        // 2.6Mbps, which is plenty to accommodate the target bitrate; but the minimum bandwidth estimate that would
        // trigger a new bandwidth allocation is 2.4Mbps + 2.4Mbps * 15% = 2.76Mbps.
        if (deltaBwe > 0)
        {
            return true;
        }

        // If, on the other hand, the bwe has decreased, we require at least a 15% drop in order to update the bitrate
        // allocation. This is an ugly hack to prevent too many resolution/UI changes in case the bridge produces too
        // low bandwidth estimate, at the risk of clogging the receiver's pipe.
        // TODO: do we still need this? Do we ever ever see BWE drop by <%15?
        return deltaBwe < -1 * previousBwe * BitrateControllerConfig.bweChangeThreshold();
    }

    private final Logger logger;

    /**
     * The estimated available bandwidth in bits per second.
     */
    private long bweBps = -1;

    /**
     * Provide the current list of endpoints (in no particular order).
     * TODO: Simplify to avoid the weird (and slow) flow involving `endpointsSupplier` and `sortedEndpointIds`.
     */
    private final Supplier<List<T>> endpointsSupplier;

    /**
     * The "effective" constraints for an endpoint indicate the maximum resolution/fps that this
     * {@link BandwidthAllocator} would allocate for this endpoint given enough bandwidth.
     *
     * They are the constraints signaled by the receiver, further reduced to 0 when the endpoint is "outside lastN".
     *
     * Effective constraints are used to signal to video senders to reduce their resolution to the minimum that
     * satisfies all receivers.
     */
    private Map<String, VideoConstraints> effectiveConstraints = Collections.emptyMap();

    private final Clock clock;

    private final EventEmitter<EventHandler> eventEmitter = new EventEmitter<>();

    /**
     * Whether bandwidth allocation should be constrained to the available bandwidth (when {@code true}), or assume
     * infinite bandwidth (when {@code false}.
     */
    private final Supplier<Boolean> trustBwe;

    /**
     * The allocations settings signalled by the receiver.
     */
    private AllocationSettings allocationSettings = new AllocationSettings();

    /**
     * The last time {@link BandwidthAllocator#update()} was called.
     */
    private Instant lastUpdateTime = Instant.MIN;

    /**
     * The result of the bitrate control algorithm, the last time it ran.
     */
    @NotNull
    private BandwidthAllocation allocation = new BandwidthAllocation(Collections.emptySet());

    private final DiagnosticContext diagnosticContext;

    BandwidthAllocator(
            EventHandler eventHandler,
            Supplier<List<T>> endpointsSupplier,
            Supplier<Boolean> trustBwe,
            Logger parentLogger,
            DiagnosticContext diagnosticContext,
            Clock clock)
    {
        this.logger = parentLogger.createChildLogger(BandwidthAllocator.class.getName());
        this.clock = clock;
        this.trustBwe = trustBwe;
        this.diagnosticContext = diagnosticContext;

        this.endpointsSupplier = endpointsSupplier;
        eventEmitter.addHandler(eventHandler);
    }

    /**
     * Gets a JSON representation of the parts of this object's state that are deemed useful for debugging.
     */
    @SuppressWarnings("unchecked")
    @SuppressFBWarnings(
            value = "IS2_INCONSISTENT_SYNC",
            justification = "We intentionally avoid synchronizing while reading fields only used in debug output.")
    JSONObject getDebugState()
    {
        JSONObject debugState = new JSONObject();
        debugState.put("trustBwe", BitrateControllerConfig.trustBwe());
        debugState.put("bweBps", bweBps);
        debugState.put("allocationSettings", allocationSettings.toString());
        debugState.put("effectiveConstraints", effectiveConstraints);
        return debugState;
    }

    @NotNull
    BandwidthAllocation getAllocation()
    {
        return allocation;
    }

    /**
     * Get the available bandwidth, taking into account the `trustBwe` option.
     */
    private long getAvailableBandwidth()
    {
        return trustBwe.get() ? bweBps : Long.MAX_VALUE;
    }

    /**
     * Notify the {@link BandwidthAllocator} that the estimated available bandwidth has changed.
     * @param newBandwidthBps the newly estimated bandwidth in bps
     */
    void bandwidthChanged(long newBandwidthBps)
    {
        if (!bweChangeIsLargerThanThreshold(bweBps, newBandwidthBps))
        {
            logger.debug(() -> "New bandwidth (" + newBandwidthBps
                    + ") is not significantly " +
                    "changed from previous estimate (" + bweBps + "), ignoring");
            // If this is a "negligible" change in the bandwidth estimation
            // wrt the last bandwidth estimation that we reacted to, then
            // do not update the bandwidth allocation. The goal is to limit
            // the resolution changes due to bandwidth estimation changes,
            // as often resolution changes can negatively impact user
            // experience, at the risk of clogging the receiver pipe.
        }
        else
        {
            logger.debug(() -> "new bandwidth is " + newBandwidthBps + ", updating");

            bweBps = newBandwidthBps;
            update();
        }
    }

    /**
     * Updates the allocation settings and calculates a new bitrate {@link BandwidthAllocation}.
     * @param allocationSettings the new allocation settings.
     */
    void update(AllocationSettings allocationSettings)
    {
        this.allocationSettings = allocationSettings;
        update();
    }

    /**
     * Runs the bandwidth allocation algorithm, and fires events if the result is different from the previous result.
     */
    synchronized void update()
    {
        lastUpdateTime = clock.instant();

        // Order the endpoints by selection, followed by speech activity.
        List<T> sortedEndpoints = prioritize(endpointsSupplier.get(), getSelectedEndpoints());

        // Extract and update the effective constraints.
        Map<String, VideoConstraints> oldEffectiveConstraints = effectiveConstraints;
        effectiveConstraints = PrioritizeKt.getEffectiveConstraints(sortedEndpoints, allocationSettings);

        // Compute the bandwidth allocation.
        BandwidthAllocation newAllocation = allocate(sortedEndpoints);

        boolean allocationChanged = !allocation.isTheSameAs(newAllocation);
        if (allocationChanged)
        {
            eventEmitter.fireEventSync(handler -> {
                handler.allocationChanged(newAllocation);
                return Unit.INSTANCE;
            });
        }
        allocation = newAllocation;

        if (!effectiveConstraints.equals(oldEffectiveConstraints))
        {
            eventEmitter.fireEventSync(handler ->
            {
                handler.effectiveVideoConstraintsChanged(oldEffectiveConstraints, effectiveConstraints);
                return Unit.INSTANCE;
            });
        }
    }

    private List<String> getSelectedEndpoints()
    {
        // On-stage participants are considered selected (with higher prio).
        List<String> selectedEndpoints = new ArrayList<>(allocationSettings.getOnStageEndpoints());
        allocationSettings.getSelectedEndpoints().forEach(selectedEndpoint ->
        {
            if (!selectedEndpoints.contains(selectedEndpoint))
            {
                selectedEndpoints.add(selectedEndpoint);
            }
        });
        return selectedEndpoints;
    }

    /**
     * Implements the bandwidth allocation algorithm for the given ordered list of endpoints.
     *
     * @param conferenceEndpoints the list of endpoints in order of priority to allocate for.
     * @return the new {@link BandwidthAllocation}.
     */
    private synchronized @NotNull BandwidthAllocation allocate(List<T> conferenceEndpoints)
    {
        List<SingleSourceAllocation> sourceBitrateAllocations = createAllocations(conferenceEndpoints);

        if (sourceBitrateAllocations.isEmpty())
        {
            return new BandwidthAllocation(Collections.emptySet());
        }

        long maxBandwidth = getAvailableBandwidth();
        long oldMaxBandwidth = -1;

        int[] oldTargetIndices = new int[sourceBitrateAllocations.size()];
        int[] newTargetIndices = new int[sourceBitrateAllocations.size()];
        Arrays.fill(newTargetIndices, -1);

        // The number of allocations with a selected layer.
        int numAllocationsWithVideo = 0;

        boolean oversending = false;
        while (oldMaxBandwidth != maxBandwidth)
        {
            oldMaxBandwidth = maxBandwidth;
            System.arraycopy(newTargetIndices, 0, oldTargetIndices, 0, oldTargetIndices.length);

            int newNumAllocationsWithVideo = 0;
            for (int i = 0; i < sourceBitrateAllocations.size(); i++)
            {
                SingleSourceAllocation sourceBitrateAllocation = sourceBitrateAllocations.get(i);

                if (sourceBitrateAllocation.constraints.getMaxHeight() <= 0)
                {
                    continue;
                }

                maxBandwidth += sourceBitrateAllocation.getTargetBitrate();
                // In stage view improve greedily until preferred, in tile view go step-by-step.
                sourceBitrateAllocation.improve(maxBandwidth);
                if (i == 0 && sourceBitrateAllocation.isOnStage() && BitrateControllerConfig.allowOversendOnStage())
                {
                    oversending |= sourceBitrateAllocation.tryLowestLayer();
                }
                maxBandwidth -= sourceBitrateAllocation.getTargetBitrate();

                newTargetIndices[i] = sourceBitrateAllocation.targetIdx;
                if (sourceBitrateAllocation.targetIdx != -1)
                {
                    newNumAllocationsWithVideo++;
                }

                // In stage view, do not allocate bandwidth for thumbnails until the on-stage reaches "preferred".
                // This prevents enabling thumbnail only to disable them when bwe slightly increases allowing on-stage
                // to take more.
                if (sourceBitrateAllocation.isOnStage() &&
                        sourceBitrateAllocation.targetIdx < sourceBitrateAllocation.preferredIdx)
                {
                    break;
                }
            }

            if (numAllocationsWithVideo > newNumAllocationsWithVideo)
            {
                // rollback state to prevent jumps in the number of forwarded participants.
                for (int i = 0; i < sourceBitrateAllocations.size(); i++)
                {
                    sourceBitrateAllocations.get(i).targetIdx = oldTargetIndices[i];
                }

                break;
            }

            numAllocationsWithVideo = newNumAllocationsWithVideo;
        }

        return new BandwidthAllocation(
                sourceBitrateAllocations.stream().map(SingleSourceAllocation::getResult).collect(Collectors.toSet()),
                oversending);
    }


    private synchronized @NotNull List<SingleSourceAllocation> createAllocations(List<T> conferenceEndpoints)
    {
        // Init.
        List<SingleSourceAllocation> sourceBitrateAllocations = new ArrayList<>(conferenceEndpoints.size());

        for (MediaSourceContainer endpoint : conferenceEndpoints)
        {
            MediaSourceDesc[] sources = endpoint.getMediaSources();

            if (!ArrayUtils.isNullOrEmpty(sources))
            {
                for (MediaSourceDesc source : sources)
                {
                    sourceBitrateAllocations.add(
                            new SingleSourceAllocation(
                                    endpoint.getId(),
                                    source,
                                    // Note that we use the effective constraints and not the receiver's constraints
                                    // directly. This means we never even try to allocate bitrate to endpoints "outside
                                    // lastN". For example, if LastN=1 and the first endpoint sends a non-scalable
                                    // stream with bitrate higher that the available bandwidth, we will forward no
                                    // video at all instead of going to the second endpoint in the list.
                                    // I think this is not desired behavior. However, it is required for the "effective
                                    // constraints" to work as designed.
                                    effectiveConstraints.get(endpoint.getId()),
                                    allocationSettings.getOnStageEndpoints().contains(endpoint.getId()),
                                    diagnosticContext,
                                    clock));
                }
            }
        }

        return sourceBitrateAllocations;
    }

    /**
     * Submits a call to `update` in a CPU thread if bandwidth allocation has not been performed recently.
     */
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
        default void allocationChanged(@NotNull BandwidthAllocation allocation) {}
        default void effectiveVideoConstraintsChanged(
                @NotNull Map<String, VideoConstraints> oldEffectiveConstraints,
                @NotNull Map<String, VideoConstraints> newEffectiveConstraints) {}
    }
}
