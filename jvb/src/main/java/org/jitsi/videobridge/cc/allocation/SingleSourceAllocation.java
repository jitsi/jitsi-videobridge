/*
 * Copyright @ 2020 - present 8x8, Inc.
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

import org.jitsi.nlj.MediaSourceDesc;
import org.jitsi.nlj.RtpLayerDesc;
import org.jitsi.nlj.util.*;
import org.jitsi.videobridge.cc.config.*;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * A bitrate allocation that pertains to a specific source. This is the internal representation used in the allocation
 * algorithm, as opposed to {@link SingleAllocation} which is the end result.
 *
 * @author George Politis
 */
class SingleSourceAllocation {
    /**
     * An reusable empty array of {@link LayerSnapshot} to reduce allocations.
     */
    private static final LayerSnapshot[] EMPTY_RATE_SNAPSHOT_ARRAY = new LayerSnapshot[0];

    /**
     * The ID of the {@code Endpoint} that this instance pertains to.
     */
    public final String endpointID;

    /**
     * The constraints to use while allocating bandwidth to this endpoint.
     */
    final VideoConstraints constraints;

    /**
     * The {@link MediaSourceDesc} that this instance pertains to.
     */
    final MediaSourceDesc source;

    /**
     * An array that holds the stable bitrate snapshots of the {@link RtpLayerDesc}s that this {@link #source} offers.
     */
    final LayerSnapshot[] ratedIndices;

    /**
     * The rated quality that needs to be achieved before allocating bandwidth for any of the subsequent sources.
     */
    final int ratedPreferredIdx;

    /**
     * The current rated quality target for this source. It can be improved in the {@code improve()} step, if there is
     * enough bandwidth.
     */
    int ratedTargetIdx = -1;

    /**
     * Whether the chosen index leads to sending more than the available bandwidth.
     */
    public boolean oversending = false;

    /**
     * The bitrate (in bps) of the highest active layer (with bitrate > 0) that will be considered for selection (i.e.
     * the layer that will be chosen given infinite bandwidth).
     * Note that not all layers satisfying the constraints are considered for selection. With the default config, the
     * {@link AllocationStrategy#StageView} strategy prefers 360p/30fps over 720p at 7.5 or 15 fps, so the latter
     * two will not be considered. So when the 720p/30fps layer is not active, the "ideal" will be 360p/30fps.
     */
    final long idealBitrate;

    SingleSourceAllocation(
            String endpointID,
            MediaSourceDesc source,
            VideoConstraints constraints,
            AllocationStrategy strategy,
            Clock clock)
    {
        this.endpointID = endpointID;
        this.constraints = constraints;
        this.source = source;

        if (source == null || constraints.getMaxHeight() <= 0)
        {
            ratedPreferredIdx = -1;
            idealBitrate = 0;
            ratedIndices = EMPTY_RATE_SNAPSHOT_ARRAY;
            return;
        }

        long nowMs = clock.instant().toEpochMilli();
        List<LayerSnapshot> ratesList = new ArrayList<>();
        // Initialize the list of layers to be considered. These are the layers that satisfy the constraints, with
        // a couple of exceptions (see comments below).
        int ratedPreferredIdx = 0;
        long idealBps = 0;
        for (RtpLayerDesc layer : source.getRtpLayers())
        {

            int idealHeight = constraints.getMaxHeight();
            // Skip layers that do not satisfy the constraints. If no layers satisfy the constraints, add the lowest
            // layer anyway (the constraints are "soft", and given enough bandwidth we prefer to exceed them rather than
            // sending no video at all).
            if (!ratesList.isEmpty())
            {
                if (idealHeight >= 0 && layer.getHeight() > idealHeight)
                {
                    continue;
                }
                if (constraints.getMaxFrameRate() > 0 && layer.getFrameRate() > constraints.getMaxFrameRate())
                {
                    continue;
                }
            }

            int preferredHeight = -1;
            double preferredFps = -1.0;
            if (strategy == AllocationStrategy.StageView && constraints.getMaxHeight() > 180)
            {
                // For the "on-stage" participant we favor frame rate over resolution. We consider all temporal layers
                // for resolutions lower than the preferred, but for resolutions >= preferred, we only consider
                // frame rates at least as high as the preferred. In practice this means we consider 180p/7.5fps,
                // 180p/15fps, 180p/30fps, 360p/30fps and 720p/30fps.
                // TODO: do we want to consider 360p/15 and/or 360o/7.5 too?
                preferredHeight = BitrateControllerConfig.onstagePreferredHeightPx();
                preferredFps = BitrateControllerConfig.onstagePreferredFramerate();
            }

            boolean lessThanPreferredResolution = layer.getHeight() < preferredHeight;
            boolean lessThanOrEqualIdealResolution = layer.getHeight() <= constraints.getMaxHeight();
            boolean atLeastPreferredFps = layer.getFrameRate() >= preferredFps;

            if ((lessThanPreferredResolution
                    || (lessThanOrEqualIdealResolution && atLeastPreferredFps))
                    || ratesList.isEmpty())
            {
                Bandwidth layerBitrate = layer.getBitrate(nowMs);
                long layerBitrateBps = (long) layerBitrate.getBps();
                if (layerBitrateBps > 0)
                {
                    idealBps = layerBitrateBps;
                }
                // TODO: Do we want to consider layers with bitrate=0?
                ratesList.add(new LayerSnapshot(layer, layerBitrate));
            }

            if (layer.getHeight() <= preferredHeight)
            {
                // Set the layer up to which allocation will be "eager", meaning it will continue to allocate to this
                // endpoint before moving on to the next. This is only set for the "on-stage" endpoint, to the
                // "preferred" resolution with the highest bitrate.
                // TODO: Is there a bug with this being set outside the above "if"?
                ratedPreferredIdx = ratesList.size() - 1;
            }
        }

        this.idealBitrate = idealBps;

        this.ratedPreferredIdx = ratedPreferredIdx;
        ratedIndices = ratesList.toArray(new LayerSnapshot[0]);
    }

    /**
     * Implements an "improve" step, incrementing {@link #ratedTargetIdx} to the next layer if there is sufficient
     * bandwidth. Note that this works eagerly up until the "preferred" layer (if any), and as a single step from
     * then on.
     *
     * @param maxBps the bandwidth available.
     */
    void improve(long maxBps)
    {
        if (ratedIndices.length == 0)
        {
            return;
        }

        if (ratedTargetIdx == -1 && ratedPreferredIdx > -1)
        {
            // Boost on stage participant to preferred, if there's enough bw.
            for (int i = 0; i < ratedIndices.length; i++)
            {
                if (i > ratedPreferredIdx || maxBps < ratedIndices[i].bitrate.getBps())
                {
                    break;
                }

                ratedTargetIdx = i;
            }
        }
        else
        {
            // Try the next element in the ratedIndices array.
            if (ratedTargetIdx + 1 < ratedIndices.length && ratedIndices[ratedTargetIdx + 1].bitrate.getBps() < maxBps)
            {
                ratedTargetIdx++;
            }
        }

        if (ratedTargetIdx > -1)
        {
            // If there's a higher layer available with a lower bitrate, skip to it.
            //
            // For example, if 1080p@15fps is configured as a better subjective quality than 720p@30fps (i.e. it sits
            // on a higher index in the ratedIndices array) and the bitrate that we measure for the 1080p stream is less
            // than the bitrate that we measure for the 720p stream, then we "jump over" the 720p stream and immediately
            // select the 1080p stream.
            //
            // TODO: We should skip over to the *highest* layer with a lower bitrate.
            // TODO further: Should we just prune the list of layers we consider to not include such layers?
            for (int i = ratedTargetIdx + 1; i < ratedIndices.length; i++)
            {
                if (ratedIndices[i].bitrate.getBps() > 0 && ratedIndices[i].bitrate.getBps()
                        <= ratedIndices[ratedTargetIdx].bitrate.getBps())
                {
                    ratedTargetIdx = i;
                }
            }
        }
    }

    /**
     * Gets the target bitrate (in bps) for this endpoint allocation, i.e. the bitrate of the currently chosen layer.
     */
    long getTargetBitrate()
    {
        return ratedTargetIdx != -1 ? (long) ratedIndices[ratedTargetIdx].bitrate.getBps() : 0;
    }

    private LayerSnapshot getTargetLayer()
    {
        return ratedTargetIdx != -1 ? ratedIndices[ratedTargetIdx] : null;
    }

    /**
     * Gets the index of the target layer, i.e. the index of the currently chosen layer.
     */
    int getTargetIndex()
    {
        // figures out the quality of the layer of the target rated
        // quality.
        return ratedTargetIdx != -1 ? ratedIndices[ratedTargetIdx].layer.getIndex() : -1;
    }

    private LayerSnapshot getIdealLayer()
    {
        return ratedIndices.length != 0 ? ratedIndices[ratedIndices.length - 1] : null;
    }

    /**
     * Creates the final immutable result of this allocation. Should be called once the allocation algorithm has
     * completed.
     */
    SingleAllocation getResult()
    {
        LayerSnapshot targetLayer = getTargetLayer();
        LayerSnapshot idealLayer = getIdealLayer();
        return new SingleAllocation(
                endpointID,
                source,
                targetLayer == null ? null : targetLayer.layer,
                idealLayer == null ? null : idealLayer.layer,
                oversending
        );
    }

    @Override
    public String toString() {
        return "[id=" + endpointID
                + " constraints=" + constraints
                + " ratedPreferredIdx=" + ratedPreferredIdx
                + " ratedTargetIdx=" + ratedTargetIdx
                + " oversending=" + oversending
                + " idealBitrate=" + idealBitrate;
    }

    /**
     * Saves the bitrate of a specific [RtpLayerDesc] at a specific point in time.
     */
    private static class LayerSnapshot
    {
        private final RtpLayerDesc layer;
        private final Bandwidth bitrate;
        private LayerSnapshot(RtpLayerDesc layer, Bandwidth bitrate)
        {
            this.layer = layer;
            this.bitrate = bitrate;
        }
    }
}
