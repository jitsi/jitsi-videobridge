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
import org.jitsi.videobridge.VideoConstraints;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * A bitrate allocation that pertains to a specific source.
 *
 * @author George Politis
 */
public class SingleSourceAllocation {
    /**
     * An reusable empty array of {@link LayerSnapshot} to reduce allocations.
     */
    private static final LayerSnapshot[] EMPTY_RATE_SNAPSHOT_ARRAY = new LayerSnapshot[0];

    /**
     * The ID of the {@code Endpoint} that this instance pertains to.
     */
    public final String endpointID;

    final VideoConstraints constraints;

    /**
     * The first {@link MediaSourceDesc} of the {@code Endpoint} that
     * this instance pertains to.
     */
    final MediaSourceDesc source;

    /**
     * An array that holds the stable bitrate snapshots of the
     * {@link RtpLayerDesc}s that this {@link #source} offers.
     * <p>
     * {@link RtpLayerDesc} of {@link #source}.
     */
    final LayerSnapshot[] ratedIndices;

    /**
     * The rated quality that needs to be achieved before allocating
     * bandwidth for any of the other subsequent sources in this allocation
     * decision. The rated quality is not necessarily equal to the encoding
     * quality. For example, for the on-stage participant we consider 5
     * rated qualities:
     * <p>
     * 0 -> 180p7.5, 1 -> 180p15, 2 -> 180p30, 3 -> 360p30, 4 -> 720p30.
     * <p>
     * The encoding quality of the 4th rated quality is 8.
     */
    final int ratedPreferredIdx;

    /**
     * The current rated quality target for this source. It can potentially
     * be improved in the improve step, provided there is enough bandwidth.
     */
    int ratedTargetIdx = -1;

    /**
     * A boolean that indicates whether or not we're force pushing through
     * the bottleneck this source.
     */
    public boolean oversending = false;

    /**
     * the bitrate (in bps) of the layer that is the closest to the ideal
     * and has a bitrate, or 0 if there are no layers with a bitrate (for
     * example, the endpoint is video muted).
     */
    final long idealBitrate;

    /**
     * @param source The {@link MediaSourceDesc} that this bitrate allocation pertains to.
     */
    SingleSourceAllocation(
            String endpointID,
            MediaSourceDesc source,
            VideoConstraints constraints,
            Clock clock)
    {
        this.endpointID = endpointID;
        this.constraints = constraints;
        this.source = source;

        if (source == null || constraints.getIdealHeight() <= 0) {
            ratedPreferredIdx = -1;
            idealBitrate = 0;
            ratedIndices = EMPTY_RATE_SNAPSHOT_ARRAY;
            return;
        }

        long nowMs = clock.instant().toEpochMilli();
        List<LayerSnapshot> ratesList = new ArrayList<>();
        // Initialize the list of flows that we will consider for sending
        // for this source. For example, for the on-stage participant we
        // consider 720p@30fps, 360p@30fps, 180p@30fps, 180p@15fps,
        // 180p@7.5fps while for the thumbnails we consider 180p@30fps,
        // 180p@15fps and 180p@7.5fps
        int ratedPreferredIdx = 0;
        long idealBps = 0;
        for (RtpLayerDesc layer : source.getRtpLayers()) {

            int idealHeight = constraints.getIdealHeight();
            // We don't want to exceed the ideal resolution but we also
            // want to make sure we have at least 1 rated encoding.
            if (idealHeight >= 0 && layer.getHeight() > idealHeight && !ratesList.isEmpty()) {
                continue;
            }

            // For the "selected" participant we favor frame rate over
            // resolution. We include all temporal layers up to the
            // preferred resolution, but only consider the preferred
            // frame-rate with higher-than-preferred resolutions. In
            // practice today this translates to 180p7.5fps, 180p15fps,
            // 180p30fps, 360p30fps and 720p30fps.

            boolean lessThanPreferredResolution
                    = layer.getHeight() < constraints.getPreferredHeight();
            boolean lessThanOrEqualIdealResolution
                    = layer.getHeight() <= constraints.getIdealHeight();
            boolean atLeastPreferredFps
                    = layer.getFrameRate() >= constraints.getPreferredFps();

            if ((lessThanPreferredResolution
                    || (lessThanOrEqualIdealResolution && atLeastPreferredFps))
                    || ratesList.isEmpty()) {
                Bandwidth layerBitrate = layer.getBitrate(nowMs);
                long layerBitrateBps = (long) layerBitrate.getBps();
                if (layerBitrateBps > 0) {
                    idealBps = layerBitrateBps;
                }
                ratesList.add(new LayerSnapshot(layer, layerBitrate));
            }

            if (layer.getHeight() <= constraints.getPreferredHeight()) {
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

        this.ratedPreferredIdx = ratedPreferredIdx;
        ratedIndices = ratesList.toArray(new LayerSnapshot[0]);
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
     *               quality can have.
     */
    void improve(long maxBps)
    {
        if (ratedIndices.length == 0) {
            return;
        }

        if (ratedTargetIdx == -1 && ratedPreferredIdx > -1) {
            // Boost on stage participant to preferred, if there's enough bw.
            for (int i = 0; i < ratedIndices.length; i++) {
                if (i > ratedPreferredIdx || maxBps < ratedIndices[i].bitrate.getBps()) {
                    break;
                }

                ratedTargetIdx = i;
            }
        } else {
            // Try the next element in the ratedIndices array.
            if (ratedTargetIdx + 1 < ratedIndices.length
                    && ratedIndices[ratedTargetIdx + 1].bitrate.getBps() < maxBps)
            {
                ratedTargetIdx++;
            }
        }

        if (ratedTargetIdx > -1) {
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
                if (ratedIndices[i].bitrate.getBps() > 0 && ratedIndices[i].bitrate.getBps()
                        <= ratedIndices[ratedTargetIdx].bitrate.getBps())
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
    long getTargetBitrate()
    {
        return ratedTargetIdx != -1 ? (long) ratedIndices[ratedTargetIdx].bitrate.getBps() : 0;
    }

    private LayerSnapshot getTargetLayer()
    {
        return ratedTargetIdx != -1 ? ratedIndices[ratedTargetIdx] : null;
    }

    /**
     * Gets the target quality for this source.
     *
     * @return the target quality for this source.
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
     * Creates the final immutable result of this allocation. To be called once the allocation algorithm has
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
                new VideoConstraints2(
                        constraints.getIdealHeight(),
                        constraints.getPreferredFps()),
                oversending
        );
    }

    @Override
    public String toString() {
        return "[id=" + endpointID
                + " effectiveVideoConstraints=" + constraints
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
