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

import org.jitsi.videobridge.*;
import org.json.simple.*;

import java.util.*;

public class VideoAllocationPolicy
    extends VideoConstraints
{
    /**
     * The "preferred" height of the constrained endpoint. When it's time to
     * allocate bandwidth for the associated source or endpoint, the bridge
     * tries to satisfy the preferred resolution before moving to the next
     * endpoint or source.
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
     * @param preferredHeight The "preferred" height of the constrained endpoint.
     * @param preferredFps The "preferred" frame-rate of the constrained endpoint.
     */
    public VideoAllocationPolicy(int idealHeight, int preferredHeight, double preferredFps)
    {
        super(idealHeight);
        this.preferredHeight = preferredHeight;
        this.preferredFps = preferredFps;
    }

    /**
     * Ctor.
     *
     * @param idealHeight The ideal height of the constrained endpoint or source.
     */
    public VideoAllocationPolicy(int idealHeight)
    {
        this(idealHeight, -1, -1);
    }

    /**
     * @return The "preferred" height of the constrained endpoint source.
     */
    public int getPreferredHeight()
    {
        return preferredHeight;
    }

    /**
     * @return The "preferred" framerate of the constrained endpoint or source.
     */
    public double getPreferredFps()
    {
        return preferredFps;
    }


    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoAllocationPolicy that = (VideoAllocationPolicy) o;
        return getIdealHeight() == that.getIdealHeight() &&
            preferredHeight == that.preferredHeight &&
            Double.compare(that.preferredFps, preferredFps) == 0;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(getIdealHeight(), preferredHeight, preferredFps);
    }

    @Override
    public String toString()
    {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("idealHeight", getIdealHeight());
        jsonObject.put("preferredHeight", preferredHeight);
        jsonObject.put("preferredFps", preferredFps);
        return jsonObject.toJSONString();
    }
}
