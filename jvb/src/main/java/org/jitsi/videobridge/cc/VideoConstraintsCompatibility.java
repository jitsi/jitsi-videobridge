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

package org.jitsi.videobridge.cc;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.cc.config.*;
import org.json.simple.*;

import java.util.*;
import java.util.stream.*;

/**
 * A class that translates between old-world selected/maxFrameHeight
 * messages to new-world video constraints.
 */
public class VideoConstraintsCompatibility
{
    /**
     * The last selected endpoints set signaled by the receiving endpoint.
     */
    @NotNull
    private List<String> selectedEndpoints = Collections.emptyList();

    /**
     * The last max resolution signaled by the receiving endpoint. We set a
     * very large initial value because we want this to be ignored when we take
     * the Math.min below, unless the client has set it to a lower, more
     * reasonable, value.
     */
    private int maxFrameHeight = Integer.MAX_VALUE;

    /**
     * Computes the video constraints map (endpoint -> video constraints) that
     * corresponds to the selected and max resolution messages (whose
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
     * The max height constrained was added for tile-view back when everything
     * was expressed as "selected" endpoints, the idea being we
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
    public Map<String, VideoConstraints> computeVideoConstraints()
    {
        Map<String, VideoConstraints> newVideoConstraints = new HashMap<>();

        int maxFrameHeightCopy = maxFrameHeight;

        List<String> selectedEndpointsCopy = selectedEndpoints;
        if (!selectedEndpointsCopy.isEmpty())
        {
            final VideoConstraints selectedEndpointConstraints;

            if (selectedEndpointsCopy.size() > 1)
            {
                // This implements special handling for tile-view. We can show that
                // (selectedEndpoints.size() > 1) is equivalent to tile-view (so we
                // can use it as a clue to detect tile-view):
                //
                // (selectedEndpoints.size() > 1) implies tile-view because multiple
                // "selected" endpoints has only ever been used for tile-view.
                //
                // tile-view implies (selectedEndpoints.size() > 1), or, equivalently,
                // (selectedEndpoints.size() <= 1) implies non tile-view because as
                // soon as we click on a participant we exit tile-view, see:
                //
                // https://github.com/jitsi/jitsi-meet/commit/ebcde745ef34bd3d45a2d884825fdc48cfa16839
                // https://github.com/jitsi/jitsi-meet/commit/4cea7018f536891b028784e7495f71fc99fc18a0
                // https://github.com/jitsi/jitsi-meet/commit/29bc18df01c82cefbbc7b78f5aef7b97c2dee0e4
                // https://github.com/jitsi/jitsi-meet/commit/e63cd8c81bceb9763e4d57be5f2262c6347afc23
                //
                // This means that the condition selectedEndpoints.size() > 1 is
                // equivalent to tile-view.

                // In tile view we set the ideal height but not the preferred height
                // nor the preferred frame-rate because we want even even
                // distribution of bandwidth among all the tiles to avoid ninjas.
                selectedEndpointConstraints = new VideoConstraints(
                    Math.min(BitrateControllerConfig.onstageIdealHeightPx(),
                        maxFrameHeightCopy));
            }
            else
            {
                selectedEndpointConstraints = new VideoConstraints(
                    Math.min(BitrateControllerConfig.onstageIdealHeightPx(),
                        maxFrameHeightCopy),
                    BitrateControllerConfig.onstagePreferredHeightPx(),
                    BitrateControllerConfig.onstagePreferredFramerate());
            }

            Map<String, VideoConstraints> selectedVideoConstraintsMap
                = selectedEndpointsCopy
                .stream()
                .collect(Collectors.toMap(e -> e, e -> selectedEndpointConstraints));

            // Add video constraints for all the selected endpoints.
            newVideoConstraints.putAll(selectedVideoConstraintsMap);
        }

        return newVideoConstraints;
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
    public void setSelectedEndpoints(@NotNull List<String> newSelectedEndpoints)
    {
        this.selectedEndpoints = newSelectedEndpoints;
    }

    @SuppressWarnings("unchecked")
    public OrderedJsonObject getDebugState()
    {
        OrderedJsonObject debugState = new OrderedJsonObject();

        JSONArray selected = new JSONArray();
        selected.addAll(selectedEndpoints);
        debugState.put("selected", selected);

        debugState.put("max_frame_height", maxFrameHeight);

        return debugState;
    }
}
