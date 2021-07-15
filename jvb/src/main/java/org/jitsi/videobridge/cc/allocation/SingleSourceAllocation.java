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

import kotlin.*;
import org.jitsi.nlj.*;
import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.cc.config.*;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.*;

/**
 * A bitrate allocation that pertains to a specific source. This is the internal representation used in the allocation
 * algorithm, as opposed to {@link SingleAllocation} which is the end result.
 *
 * @author George Politis
 */
class SingleSourceAllocation
{
    private static final TimeSeriesLogger timeSeriesLogger
            = TimeSeriesLogger.getTimeSeriesLogger(BandwidthAllocator.class);

    /**
     * An reusable empty array of {@link LayerSnapshot} to reduce allocations.
     */
    private static final LayerSnapshot[] EMPTY_RATE_SNAPSHOT_ARRAY = new LayerSnapshot[0];

    private static Pair<List<LayerSnapshot>, Integer> selectLayers(
            List<LayerSnapshot> allLayers,
            VideoConstraints constraints)
    {
        int idealHeight = constraints.getMaxHeight();
        int preferredHeight = -1;
        double preferredFps = -1.0;
        if (constraints.getMaxHeight() > 180)
        {
            // For participants with sufficient maxHeight we favor frame rate over resolution. We consider all
            // temporal layers for resolutions lower than the preferred, but for resolutions >= preferred, we only
            // consider frame rates at least as high as the preferred. In practice this means we consider
            // 180p/7.5fps, 180p/15fps, 180p/30fps, 360p/30fps and 720p/30fps.
            preferredHeight = BitrateControllerConfig.onstagePreferredHeightPx();
            preferredFps = BitrateControllerConfig.onstagePreferredFramerate();
        }
        boolean noActiveLayers = allLayers.stream().noneMatch(l -> l.bitrate > 0);

        List<LayerSnapshot> ratesList = new ArrayList<>();
        // Initialize the list of layers to be considered. These are the layers that satisfy the constraints, with
        // a couple of exceptions (see comments below).
        int ratedPreferredIdx = 0;
        for (LayerSnapshot layerSnapshot : allLayers)
        {
            RtpLayerDesc layer = layerSnapshot.layer;

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

            boolean lessThanPreferredResolution = layer.getHeight() < preferredHeight;
            boolean lessThanOrEqualIdealResolution = layer.getHeight() <= constraints.getMaxHeight();
            // If frame rate is unknown, consider it to be sufficient.
            boolean atLeastPreferredFps = layer.getFrameRate() < 0 || layer.getFrameRate() >= preferredFps;

            if ((lessThanPreferredResolution
                    || (lessThanOrEqualIdealResolution && atLeastPreferredFps))
                    || ratesList.isEmpty())
            {

                // No active layers usually happens when the source has just been signaled and we haven't received
                // any packets yet. Add the layers here, so one gets selected and we can start forwarding sooner.
                if (noActiveLayers || layerSnapshot.bitrate > 0)
                {
                    ratesList.add(layerSnapshot);

                    if (layer.getHeight() <= preferredHeight)
                    {
                        // Set the layer up to which allocation will be "eager", meaning it will continue to allocate
                        // to this endpoint before moving on to the next. This is only set for the "on-stage" endpoint,
                        // to the "preferred" resolution with the highest bitrate.
                        ratedPreferredIdx = ratesList.size() - 1;
                    }
                }
            }

        }

        return new Pair<>(ratesList, ratedPreferredIdx);
    }

    final MediaSourceContainer endpoint;

    /**
     * The constraints to use while allocating bandwidth to this endpoint.
     */
    final VideoConstraints constraints;

    /**
     * An array that holds the layers to be considered when allocating bandwidth.
     */
    private final LayerSnapshot[] layers;

    private final boolean onStage;

    /**
     * The index (into {@link #layers}) of the "preferred" layer, i.e. the layer up to which we allocate eagerly.
     */
    final int preferredIdx;

    /**
     * The index of the current target layer. It can be improved in the {@code improve()} step, if there is enough
     * bandwidth.
     */
    int targetIdx = -1;

    SingleSourceAllocation(
            MediaSourceContainer endpoint,
            VideoConstraints constraints,
            boolean onStage,
            DiagnosticContext diagnosticContext,
            Clock clock)
    {
        this.endpoint = endpoint;
        this.constraints = constraints;
        this.onStage = onStage;

        MediaSourceDesc source = endpoint.getMediaSource();
        if (source == null || constraints.getMaxHeight() <= 0)
        {
            preferredIdx = -1;
            layers = EMPTY_RATE_SNAPSHOT_ARRAY;
            return;
        }

        long nowMs = clock.instant().toEpochMilli();
        Pair<List<LayerSnapshot>, Integer> ratesListAndPreferredIdx
                = selectLayers(
                        source.getRtpLayers().stream()
                                .map(l -> new LayerSnapshot(l, l.getBitrate(nowMs))).collect(Collectors.toList()),
                        constraints);
        List<LayerSnapshot> ratesList = ratesListAndPreferredIdx.getFirst();

        if (timeSeriesLogger.isTraceEnabled())
        {
            DiagnosticContext.TimeSeriesPoint ratesTimeSeriesPoint
                    = diagnosticContext.makeTimeSeriesPoint("layers_considered")
                        .addField("remote_endpoint_id", endpoint.getId());
            for (LayerSnapshot layerSnapshot : ratesList)
            {
                RtpLayerDesc l = layerSnapshot.layer;
                ratesTimeSeriesPoint.addField(
                        RtpLayerDesc.indexString(l.getIndex()) +
                            "_" + l.getHeight() + "p_" + l.getFrameRate() + "fps_bps",
                        layerSnapshot.bitrate);
            }
            timeSeriesLogger.trace(ratesTimeSeriesPoint);
        }

        this.preferredIdx = ratesListAndPreferredIdx.getSecond();
        layers = ratesList.toArray(new LayerSnapshot[0]);
    }

    /**
     * Implements an "improve" step, incrementing {@link #targetIdx} to the next layer if there is sufficient
     * bandwidth. Note that this works eagerly up until the "preferred" layer (if any), and as a single step from
     * then on.
     *
     * @param maxBps the bandwidth available.
     */
    void improve(long maxBps)
    {
        if (layers.length == 0)
        {
            return;
        }

        if (targetIdx == -1 && preferredIdx > -1 && onStage)
        {
            // Boost on stage participant to preferred, if there's enough bw.
            for (int i = 0; i < layers.length; i++)
            {
                if (i > preferredIdx || maxBps < layers[i].bitrate)
                {
                    break;
                }

                targetIdx = i;
            }
        }
        else
        {
            // Try the next element in the ratedIndices array.
            if (targetIdx + 1 < layers.length && layers[targetIdx + 1].bitrate < maxBps)
            {
                targetIdx++;
            }
        }

        if (targetIdx > -1)
        {
            // If there's a higher layer available with a lower bitrate, skip to it.
            //
            // For example, if 1080p@15fps is configured as a better subjective quality than 720p@30fps (i.e. it sits
            // on a higher index in the ratedIndices array) and the bitrate that we measure for the 1080p stream is less
            // than the bitrate that we measure for the 720p stream, then we "jump over" the 720p stream and immediately
            // select the 1080p stream.
            //
            // TODO further: Should we just prune the list of layers we consider to not include such layers?
            for (int i = layers.length - 1; i >= targetIdx + 1; i--)
            {
                if (layers[i].bitrate <= layers[targetIdx].bitrate)
                {
                    targetIdx = i;
                }
            }
        }
    }

    /**
     * The source is suspended if we've not selected a layer AND the source has active layers.
     */
    boolean isSuspended()
    {
        return targetIdx == -1 && layers.length > 0 && layers[0].bitrate > 0;
    }

    /**
     * Gets the target bitrate (in bps) for this endpoint allocation, i.e. the bitrate of the currently chosen layer.
     */
    long getTargetBitrate()
    {
        LayerSnapshot targetLayer = getTargetLayer();
        return targetLayer != null ? (long) targetLayer.bitrate : 0;
    }

    private LayerSnapshot getTargetLayer()
    {
        return targetIdx != -1 ? layers[targetIdx] : null;
    }

    /**
     * Exposed for testing only.
     */
    RtpLayerDesc getPreferredLayer()
    {
        return preferredIdx != -1 ? layers[preferredIdx].layer : null;
    }

    private LayerSnapshot getIdealLayer()
    {
        return layers.length != 0 ? layers[layers.length - 1] : null;
    }

    /**
     * If there is no target layer, switch to the lowest layer (if any are available).
     * @return true if the target layer was changed.
     */
    boolean tryLowestLayer()
    {
        if (targetIdx < 0 && layers.length > 0)
        {
            targetIdx = 0;
            return true;
        }
        return false;
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
                endpoint,
                targetLayer == null ? null : targetLayer.layer,
                idealLayer == null ? null : idealLayer.layer
        );
    }

    @Override
    public String toString() {
        return "[id=" + endpoint.getId()
                + " constraints=" + constraints
                + " ratedPreferredIdx=" + preferredIdx
                + " ratedTargetIdx=" + targetIdx;
    }

    public boolean isOnStage()
    {
        return onStage;
    }

    /**
     * Exposed for testing only.
     */
    LayerSnapshot[] getLayers()
    {
        return layers;
    }

    /**
     * Saves the bitrate of a specific [RtpLayerDesc] at a specific point in time.
     */
    static class LayerSnapshot
    {
        final RtpLayerDesc layer;
        private final double bitrate;
        private LayerSnapshot(RtpLayerDesc layer, double bitrate)
        {
            this.layer = layer;
            this.bitrate = bitrate;
        }
    }
}
