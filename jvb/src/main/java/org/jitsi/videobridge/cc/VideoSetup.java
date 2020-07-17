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
package org.jitsi.videobridge.cc;

import org.jetbrains.annotations.*;
import org.jitsi.nlj.*;
import org.jitsi.videobridge.*;

import java.util.*;

/**
 * A pair of {@link VideoConstraints} and a {@link VideoPolicy} that forms the constraints sub-problem to solve when
 * allocating bandwidth for a specific endpoint or a specific source.
 */
public class VideoSetup
{
    /**
     * The instructions that describe how to chose the layers for a specific endpoint or a specific source.
     */
    public final VideoPolicy policy;

    /**
     * The constraints that limit the layer choices for a specific endpoint or a specific source.
     */
    public final VideoConstraints constraints;

    /**
     * Ctor.
     *
     * @param policy The instructions that describe how to chose the layers for a specific endpoint or a specific source.
     * @param constraints The constraints that limit the layer choices for a specific endpoint or a specific source.
     */
    public VideoSetup(@NotNull VideoPolicy policy, @NotNull VideoConstraints constraints)
    {
        this.policy = policy;
        this.constraints = constraints;
    }


    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoSetup that = (VideoSetup) o;
        return constraints.equals(that.constraints) && policy.equals(that.policy);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(constraints.hashCode(), policy.hashCode());
    }

    @Override
    public String toString()
    {
        return "{policy: " + policy + ",constraints: " + constraints + "}";
    }

    public RtpLayerMatcher makeRtpLayerMatcher(RtpLayerDesc layer)
    {
        boolean lessThanOrEqualIdealResolution = layer.getHeight() <= constraints.getIdealHeight();
        boolean lessThanPreferredResolution = layer.getHeight() < policy.getGreedyHeightAllocationUpperBound();
        boolean atLeastPreferredFps = layer.getFrameRate() >= policy.getMinFpsBeyondGreedyHeight();

        RtpLayerMatcher rtpLayerMatcher = new RtpLayerMatcher();
        rtpLayerMatcher.isMatch = lessThanOrEqualIdealResolution && ((lessThanPreferredResolution || atLeastPreferredFps));
        rtpLayerMatcher.withEagerAllocation = layer.getHeight() <= policy.getGreedyHeightAllocationUpperBound();

        return rtpLayerMatcher;
    }

    static class RtpLayerMatcher
    {
        private boolean withEagerAllocation;
        private boolean isMatch;

        public boolean matches()
        {
            return isMatch;
        }

        public boolean shouldDoGreedyAllocation()
        {
            return withEagerAllocation;
        }
    }
}
