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
 * Expresses the video constraints for an endpoint or a track.
 */
public class VideoConstraints
{
    /**
     * The ideal height of the constrained endpoint. The bridge tries to send an
     * encoding that matches this resolution as close as possible, if bandwidth
     * is available.
     */
    private final int idealHeight;

    /**
     * The "preferred" height of the constrained endpoint. When it's time to
     * allocate bandwidth for the associated track or endpoint, the bridge
     * tries to satisfy the preferred resolution before moving to the next
     * endpoint or track.
     */
    private final int preferredHeight;

    /**
     * The "preferred" frame-rate of the constrained endpoint.
     */
    private final double preferredFps;


    /**
     * Ctor.
     *
     * @param idealHeight The ideal height of the constrained endpoint.
     * @param preferredFps
     * @param preferredHeight
     */
    VideoConstraints(int idealHeight, int preferredHeight, double preferredFps)
    {
        this.preferredFps = preferredFps;
        this.preferredHeight = preferredHeight;
        this.idealHeight = idealHeight;
    }

    /**
     * Ctor.
     *
     * @param idealHeight The ideal height of the constrained endpoint or track.
     */
    public VideoConstraints(int idealHeight)
    {
        this(idealHeight, -1, -1);
    }

    /**
     * @return The "preferred" height of the constrained endpoint track.
     */
    public int getPreferredHeight()
    {
        return preferredHeight;
    }

    /**
     * @return The "preferred" framerate of the constrained endpoint or track.
     */
    public double getPreferredFps()
    {
        return preferredFps;
    }

    /**
     * @return the ideal resolution of the constrained endpoint or track.
     */
    public int getIdealHeight()
    {
        return idealHeight;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoConstraints that = (VideoConstraints) o;
        return idealHeight == that.idealHeight
            && preferredHeight == that.preferredHeight
            && preferredFps == that.preferredFps;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(idealHeight);
    }

    @Override
    public String toString()
    {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("idealHeight", idealHeight);
        jsonObject.put("preferredHeight", preferredHeight);
        jsonObject.put("preferredFps", preferredFps);
        return jsonObject.toJSONString();
    }
}
