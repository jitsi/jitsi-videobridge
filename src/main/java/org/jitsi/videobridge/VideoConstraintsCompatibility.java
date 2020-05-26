/*
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.videobridge.cc.config.*;

import java.util.*;
import java.util.stream.*;

/**
 * A class that translates between old-world selected/pinned/maxFrameHeight
 * messages to new-world video constraints.
 */
class VideoConstraintsCompatibility
{
    /**
     * The last pinned endpoints set signaled by the receiving endpoint.
     */
    private Set<String> pinnedEndpoints;

    /**
     * The last selected endpoints set signaled by the receiving endpoint.
     */
    private Set<String> selectedEndpoints;

    /**
     * The last max resolution signaled by the receiving endpoint.
     */
    private int maxFrameHeight;

    /**
     * Computes the video constraints map (endpoint -> video constraints) that
     * corresponds to the selected, pinned and max resolution messages (whose
     * meanings are explained below) that the bridge has received from the
     * receiving endpoint.
     *
     * Selected endpoints are those that the receiver wants to see in HD because
     * they're on his/her stage-view (provided that there's enough bandwidth,
     * but that's up to the bitrate controller to decide).
     *
     * For selected endpoints we set the "ideal" height to 720 reflecting the
     * the receiver's "desire" to watch the track in high resolution. We also
     * set the "preferred" resolution and the "preferred" frame rate. Under the
     * hood this instructs the bitrate controller to prioritize the endpoint
     * during the bandwidth allocation step and eagerly allocate bandwidth up to
     * the preferred resolution and preferred frame-rate.
     *
     * Pinned endpoints are those that the receiver "always" wants to have in
     * its last-n set even if they're not on-stage (again, provided that there's
     * enough bandwidth, but that's up to the bitrate controller to decide).
     *
     * For pinned endpoints we set the "ideal" height to 180, reflecting the
     * receiver's "desire" always watch them.
     *
     * Note that semantically, selected is not equal to pinned. Pinned endpoints
     * that become selected are treated as selected. Selected endpoints that
     * become pinned are treated as selected. When a selected & pinned endpoint
     * goes off-stage, it maintains its status as "pinned".
     *
     * The max height constrained was added for tile-view back when everything
     * was expressed as "selected" and "pinned" endpoints, the idea being we
     * mark everything as selected (so endpoints aren't limited to 180p) and
     * set the max to 360p (so endpoints are limited to 360p, instead of 720p
     * which is normally used for selected endpoints. This was the quickest, not
     * the nicest way to implement the tile-view constraints signaling and it
     * was subsequently used to implement low-bandwidth mode.
     *
     * One negative side effect of this solution, other than being a hack, was
     * that the eager bandwidth allocation that we do for selected endpoints
     * doesn't work well in tile-view because we end-up with a lot of ninjas.
     *
     * By simply setting an ideal height X as a global constraint, without
     * setting a preferred resolution/frame-rate, we signal to the bitrate
     * controller that it needs to (evenly) distribute bandwidth across all
     * participants, up to X.
     */
    Map<String, VideoConstraints> computeVideoConstraints()
    {
        int maxFrameHeightCopy = maxFrameHeight;

        final VideoConstraints selectedEndpointConstraints
            = new VideoConstraints(Math.min(1080, maxFrameHeightCopy),
            BitrateControllerConfig.Config.onstagePreferredHeightPx(),
            BitrateControllerConfig.Config.onstagePreferredFramerate());

        Map<String, VideoConstraints> selectedVideoConstraintsMap
            = selectedEndpoints
            .stream()
            .collect(Collectors.toMap(e -> e, e -> selectedEndpointConstraints));

        final VideoConstraints pinnedEndpointConstraints
            = new VideoConstraints(Math.min(
            BitrateControllerConfig.Config.thumbnailMaxHeightPx(), maxFrameHeightCopy));

        Map<String, VideoConstraints> pinnedVideoConstraintsMap
            = pinnedEndpoints
            .stream()
            .collect(Collectors.toMap(e -> e, e -> pinnedEndpointConstraints));

        Map<String, VideoConstraints>
            newVideoConstraints = new HashMap<>(pinnedVideoConstraintsMap);

        // Add video constraints for all the selected endpoints (which will
        // automatically override the video constraints for pinned endpoints, so
        // they're bumped to 720p if they're also selected).
        newVideoConstraints.putAll(selectedVideoConstraintsMap);

        return newVideoConstraints;
    }

    /**
     * Sets the pinned endpoints signaled by the receiving endpoint.
     *
     * @param newPinnedEndpoints the pinned endpoints signaled by the receiving
     * endpoint.
     */
    public void setPinnedEndpoints(Set<String> newPinnedEndpoints)
    {
        this.pinnedEndpoints = newPinnedEndpoints;
    }

    /**
     * Sets the max resolution signaled by the receiving endpoint.
     *
     * @param newMaxFrameHeight the max resolution signaled by the receiving
     * endpoint.
     */
    public void setMaxFrameHeight(int newMaxFrameHeight)
    {
        this.maxFrameHeight = newMaxFrameHeight;
    }

    /**
     * Sets the selected endpoints signaled by the receiving endpoint.
     *
     * @param newSelectedEndpoints the selected endpoints signaled by the
     * receiving endpoint.
     */
    public void setSelectedEndpoints(Set<String> newSelectedEndpoints)
    {
        this.selectedEndpoints = newSelectedEndpoints;
    }
}
