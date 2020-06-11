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

import org.jitsi.videobridge.*;
import org.junit.*;

import java.util.*;
import org.jitsi.videobridge.cc.BitrateController.*;

import static org.junit.Assert.*;

public class EndpointMultiRankerTest
{
    final List<EndpointMultiRank> endpointMultiRanks = new ArrayList<>();
    final VideoConstraints stageVideoConstraints
        = new VideoConstraints(/* idealHeight */ 720);
    final VideoConstraints thumbnailVideoConstraints
        = new VideoConstraints(/* idealHeight */ 180);

    @Test
    public void followActiveSpeaker()
    {
        EndpointMultiRank activeSpeakerMultiRank
            = makeNextSpeakerWithVideoConstraints(stageVideoConstraints);
        endpointMultiRanks.add(activeSpeakerMultiRank);

        EndpointMultiRank speaker2MultiRank
            = makeNextSpeakerWithVideoConstraints(thumbnailVideoConstraints);
        endpointMultiRanks.add(speaker2MultiRank);

        EndpointMultiRank speaker3MultiRank
            = makeNextSpeakerWithVideoConstraints(thumbnailVideoConstraints);
        endpointMultiRanks.add(speaker3MultiRank);

        endpointMultiRanks.sort(new EndpointMultiRanker());

        // Whoever's on-stage needs to be prioritized first, then by speaker. In
        // this particular test case, the on-stage speaker coincides with the
        // active speaker.
        assertEquals(endpointMultiRanks.get(0), activeSpeakerMultiRank);
        assertEquals(endpointMultiRanks.get(1), speaker2MultiRank);
        assertEquals(endpointMultiRanks.get(2), speaker3MultiRank);
    }

    @Test
    public void followArbitrarySpeaker()
    {
        EndpointMultiRank activeSpeakerMultiRank
            = makeNextSpeakerWithVideoConstraints(thumbnailVideoConstraints);
        endpointMultiRanks.add(activeSpeakerMultiRank);

        EndpointMultiRank speaker2MultiRank
            = makeNextSpeakerWithVideoConstraints(thumbnailVideoConstraints);
        endpointMultiRanks.add(speaker2MultiRank);

        EndpointMultiRank speaker3MultiRank
            = makeNextSpeakerWithVideoConstraints(stageVideoConstraints);
        endpointMultiRanks.add(speaker3MultiRank);

        endpointMultiRanks.sort(new EndpointMultiRanker());

        // Whoever's on-stage needs to be prioritized first, then by speaker. In
        // this particular test case, the on-stage speaker is the least recent
        // active speaker.
        assertEquals(endpointMultiRanks.get(0), speaker3MultiRank);
        assertEquals(endpointMultiRanks.get(1), activeSpeakerMultiRank);
        assertEquals(endpointMultiRanks.get(2), speaker2MultiRank);
    }

    private EndpointMultiRank makeNextSpeakerWithVideoConstraints(VideoConstraints videoConstraints)
    {
        return new EndpointMultiRank(endpointMultiRanks.size(), videoConstraints, null);
    }
}
