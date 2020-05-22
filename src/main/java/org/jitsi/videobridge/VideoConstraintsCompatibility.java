package org.jitsi.videobridge;

import org.jitsi.videobridge.cc.config.*;

import java.util.*;
import java.util.stream.*;

/**
 * A constraints object for the given endpoint id, with ideal height set to
 * 180p.
 *
 * Pinned endpoints are those that we always want to have in the last-n
 * set in LD, if they're not on-stage, or in HD if they're on-stage
 * (provided that there's enough bandwidth, but that's up to the bitrate
 * controller to decide).
 *
 * Note that a selected endpoint can be pinned. Signaling that to the
 * bridge may sound a bit redundant, after all if an endpoint is
 * selected, we already have a 720p constraint for it. However, when the
 * selected endpoint goes off-stage, it needs to maintain its status
 * as "pinned".
 *
 * By setting the ideal height to 180, a receiver expresses the "desire"
 * to watch them in low resolution. This will result in being
 * prioritized during the bandwidth allocation step.
 */
class VideoConstraintsCompatibility
{
    /**
     * The last pinned endpoints set signaled by the client, converted into
     * a map of endpoint id -> video constraints. We need this for backwards
     * compatibility.
     */
    private Set<String> pinnedEndpoints;

    /**
     * The last selected endpoints set signaled by the client, converted into
     * a map of endpoint id -> video constraints. We need this for backwards
     * compatibility.
     */
    private Set<String> selectedEndpoints;

    /**
     * The max height constrained was added for tile-view back when everything
     * was expressed as "selected" and "pinned" endpoints, the idea being we
     * mark everything as selected (so endpoints aren't limited to 180p) and
     * set the max to 360p (so endpoints are limited to 360p, instead of 720p
     * which is normally used for selected endpoints. This was the quickest, not
     * the nicest way to implement the tile-view constraints signaling and it
     * was subsequently used to implement low-bandwidth mode.
     *
     * Now, one negative side effect, other than being a hack, was that for
     * selected endpoints, we eagerly allocate bandwidth up to 360p30fps. This
     * eager bandwidth allocation was something we had discussed and agreed
     * upon several moons ago, but it doesn't work well in tile-view because we
     * end-up with a lot of ninjas.
     *
     * By simply setting an ideal height X as a global constraint, we signal to
     * the bitrate controller that it needs to (evenly) distribute bandwidth
     * across all participants, up to X.
     *
     * @param idealHeight the ideal height of the constraint object.
     *
     * @return a constraints object without endpoint id (can be used as a global
     * endpoint constraint), with ideal height set to the given ideal height.
     */
    private int maxFrameHeight;

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

    public void setPinnedEndpoints(Set<String> newPinnedEndpoints)
    {
        this.pinnedEndpoints = newPinnedEndpoints;
    }

    public void setMaxFrameHeight(int maxFrameHeight)
    {
        this.maxFrameHeight = maxFrameHeight;
    }

    public void setSelectedEndpoints(Set<String> newSelectedEndpoints)
    {
        this.selectedEndpoints = newSelectedEndpoints;
    }
}
