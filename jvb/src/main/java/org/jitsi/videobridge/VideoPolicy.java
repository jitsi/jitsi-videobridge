/*
 * Copyright @ 2017 - Present, 8x8 Inc
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

import org.json.simple.*;

import java.util.*;

/**
 *
 */
public class VideoPolicy
{
    /**
     * The instance that expresses the lack of video preferences.
     */
    public static final VideoPolicy empty = new VideoPolicy(-1, -1);

    /**
     * The instance that expresses greedy allocation up to 360p and a preference of motion (frame rate) over quality
     * (resolution/height) beyond that point.
     *
     * For the "selected" participant we favor frame rate over resolution. We include all temporal layers up to the
     * preferred resolution, but only consider the preferred frame-rate with higher-than-preferred resolutions. In
     * practice today this translates to 180p7.5fps, 180p15fps, 180p30fps, 360p30fps and 720p30fps.
     */
    public static final VideoPolicy greedyTo360ThenFavorMotion = new VideoPolicy(360, 30);

    /**
     * The "preferred" height of the constrained endpoint. When it's time to
     * allocate bandwidth for the associated source or endpoint, the bridge
     * tries to satisfy the preferred resolution before moving to the next
     * endpoint or source.
     *
     * NOTE this this field along with {@link #minFpsBeyondGreedyHeight} is more of a
     * per-endpoint policy than a constraint and eventually it should be moved
     * out of this class.
     */
    private final int greedyHeightAllocationUpperBound;

    /**
     * The "preferred" frame-rate of the constrained endpoint.
     */
    private final double minFpsBeyondGreedyHeight;

    /**
     * Ctor.
     *
     * @param greedyHeightAllocationUpperBound The "preferred" height of the constrained endpoint.
     * @param minFpsBeyondGreedyHeight The "preferred" frame-rate of the constrained endpoint.
     */
    public VideoPolicy(int greedyHeightAllocationUpperBound, double minFpsBeyondGreedyHeight)
    {
        this.minFpsBeyondGreedyHeight = minFpsBeyondGreedyHeight;
        this.greedyHeightAllocationUpperBound = greedyHeightAllocationUpperBound;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoPolicy that = (VideoPolicy) o;
        return greedyHeightAllocationUpperBound == that.greedyHeightAllocationUpperBound &&
            Double.compare(that.minFpsBeyondGreedyHeight, minFpsBeyondGreedyHeight) == 0;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(minFpsBeyondGreedyHeight, greedyHeightAllocationUpperBound);
    }

    @Override
    public String toString()
    {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("greedyHeight", greedyHeightAllocationUpperBound);
        jsonObject.put("minFpsBeyondGreedyHeight", minFpsBeyondGreedyHeight);
        return jsonObject.toJSONString();
    }

    public double getMinFpsBeyondGreedyHeight()
    {
        return minFpsBeyondGreedyHeight;
    }

    public int getGreedyHeightAllocationUpperBound()
    {
        return greedyHeightAllocationUpperBound;
    }
}
