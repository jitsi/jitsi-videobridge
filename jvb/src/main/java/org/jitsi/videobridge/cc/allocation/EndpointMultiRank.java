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

import org.jitsi.videobridge.VideoConstraints;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A helper class that is used to determine the bandwidth allocation
 * rank/priority of an endpoint that is based on its speaker rank and its
 * video constraints. See {@link EndpointMultiRanker} for more information
 * on how the ranking works.
 */
class EndpointMultiRank<T extends MediaSourceContainer> {
    /**
     * The speaker rank of the {@link #endpoint} with 0 meaning that the
     * {@link #endpoint} is the most recent dominant speaker.
     */
    final int speakerRank;

    /**
     * The video constraints of the {@link #endpoint}.
     */
    VideoConstraints effectiveVideoConstraints;

    /**
     * The endpoint (sender) that's constrained and is ranked for bandwidth
     * allocation.
     */
    final T endpoint;

    /**
     * Ctor.
     *
     * @param speakerRank
     * @param effectiveVideoConstraints
     * @param endpoint
     */
    EndpointMultiRank(int speakerRank, VideoConstraints effectiveVideoConstraints, T endpoint) {
        this.speakerRank = speakerRank;
        this.effectiveVideoConstraints = effectiveVideoConstraints;
        this.endpoint = endpoint;
    }

    static <T extends MediaSourceContainer> List<EndpointMultiRank<T>> makeEndpointMultiRankList(
            List<T> conferenceEndpoints,
            Map<String, VideoConstraints> videoConstraintsMap,
            int adjustedLastN)
    {
        List<EndpointMultiRank<T>> endpointMultiRankList = new ArrayList<>(conferenceEndpoints.size());
        for (int i = 0; i < conferenceEndpoints.size(); i++)
        {
            T endpoint = conferenceEndpoints.get(i);

            endpointMultiRankList.add(new EndpointMultiRank<>(i,
                    videoConstraintsMap.getOrDefault(endpoint.getId(),
                            VideoConstraints.thumbnailVideoConstraints), endpoint));
        }

        endpointMultiRankList.sort(new EndpointMultiRanker<>());

        if (adjustedLastN > -1)
        {
            for (int i = adjustedLastN; i < endpointMultiRankList.size(); i++)
            {
                endpointMultiRankList.get(i).effectiveVideoConstraints = VideoConstraints.disabledVideoConstraints;
            }
        }

        return endpointMultiRankList;
    }

    /**
     * An endpoint that has higher priority/rank will be allocated
     * bandwidth prior to other endpoints with lower priority/rank
     * (see the allocate method bellow)
     * <p>
     * Once the endpoints are ranked, the bandwidth allocation algorithm
     * loops over the endpoints multiple times, improving their target
     * bitrate at every step, until no further improvement is possible.
     * <p>
     * In this multi-rank implementation, endpoints that have a preferred height
     * set (on-stage endpoints in Jitsi Meet) will be given bandwidth first.
     * Then we prioritize endpoints that have higher ideal height (this rule is
     * somewhat arbitrary since we don't have a use case in Jitsi Meet that
     * leverages it). If two endpoints have the same ideal and preferred height,
     * then we look at their speech rank (whoever spoke last has is ranked higher).
     */
    static class EndpointMultiRanker<T extends MediaSourceContainer> implements Comparator<EndpointMultiRank<T>>
    {
        @Override
        public int compare(EndpointMultiRank o1, EndpointMultiRank o2)
        {
            // We want "o1 has higher preferred height than o2" to imply "o1 is
            // smaller than o2" as this is equivalent to "o1 needs to be
            // prioritized first".
            int preferredHeightDiff =
                    o2.effectiveVideoConstraints.getPreferredHeight()
                            - o1.effectiveVideoConstraints.getPreferredHeight();
            if (preferredHeightDiff != 0)
            {
                return preferredHeightDiff;
            }
            else
            {
                // We want "o1 has higher ideal height than o2" to imply "o1 is
                // smaller than o2" as this is equivalent to "o1 needs to be
                // prioritized first".
                int idealHeightDiff
                        = o2.effectiveVideoConstraints.getIdealHeight() - o1.effectiveVideoConstraints.getIdealHeight();
                if (idealHeightDiff != 0)
                {
                    return idealHeightDiff;
                }

                // Everything else being equal, we rely on the speaker order.
                return o1.speakerRank - o2.speakerRank;
            }
        }
    }
}
