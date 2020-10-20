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

package org.jitsi.videobridge.cc

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainInOrder
import org.jitsi.videobridge.VideoConstraints

class EndpointMultiRankerTest : FunSpec({
    test("follow active speaker") {
        val ranks = listOf(
            stageVideoConstraints.toRank(0),
            thumbnailVideoConstraints.toRank(1),
            thumbnailVideoConstraints.toRank(2)
        )
        val sorted = ranks.sortedWith(BitrateController.EndpointMultiRanker())

        // NOTE that the active speaker rank is the tie breaker if both the
        // ideal and preferred height is equal and the order in which the
        // endpoints are added to the list determines their speaker rank (for
        // more information on how the ranking works, see the EndpointMultiRanker
        // class documentation).
        //
        // Whoever's on-stage needs to be prioritized first, then by speaker. In
        // this particular test case, the on-stage speaker coincides with the
        // active speaker.
        sorted shouldContainInOrder ranks
    }

    test("follow arbitrary speaker") {
        val activeSpeaker = thumbnailVideoConstraints.toRank(0)
        val speaker2 = thumbnailVideoConstraints.toRank(1)
        val speaker3 = stageVideoConstraints.toRank(3)

        val sorted = listOf(activeSpeaker, speaker2, speaker3).sortedWith(BitrateController.EndpointMultiRanker())

        // NOTE that the active speaker rank is the tie breaker if both the
        // ideal and preferred height is equal and the order in which the
        // endpoints are added to the list determines their speaker rank (for
        // more information on how the ranking works, see the EndpointMultiRanker
        // class documentation).
        //
        // Whoever's on-stage needs to be prioritized first, then by speaker. In
        // this particular test case, the on-stage speaker is the least recent
        // active speaker.
        sorted shouldContainInOrder listOf(speaker3, activeSpeaker, speaker2)
    }
})

private val stageVideoConstraints = VideoConstraints(720)
private val thumbnailVideoConstraints = VideoConstraints(180)

private fun VideoConstraints.toRank(rank: Int): BitrateController.EndpointMultiRank<Endpoint> =
    BitrateController.EndpointMultiRank<Endpoint>(rank, this, null)
