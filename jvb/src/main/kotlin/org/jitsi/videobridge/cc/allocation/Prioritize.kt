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
package org.jitsi.videobridge.cc.allocation

import org.jitsi.videobridge.calculateLastN
import org.jitsi.videobridge.jvbLastNSingleton
import java.util.ArrayList

/**
 * TODO: take into account whether selected endpoints are sending video. Currently, a selected endpoint without
 * video will count towards lastN, which is not desired.
 *
 * TODO: This is *horrible* and should be cleaned up (the input to BitrateAllocator should change), but it allows
 * us to simplify the rest of the allocation process.
 *
 * @param endpointIdsBySpeechActivity the endpoints IDs in the original order (by speech activity).
 * @param selectedEndpointIds the IDs of the selected endpoints, in order of selection.
 * @param conferenceEndpoints the conference endpoints in no particular order.
 *
 * @return the endpoints from `conferenceEndpoints` ordered by selection first, and then speech activity.
 */
fun <T : MediaSourceContainer?> prioritize(
    endpointIdsBySpeechActivity: List<String>,
    selectedEndpointIds: List<String>,
    conferenceEndpoints: List<T>
): List<T> {
    val orderedEndpoints = ArrayList<T>(conferenceEndpoints.size)

    selectedEndpointIds.forEach {
        conferenceEndpoints
            .stream()
            .filter { e: T -> e!!.id == it }
            .findFirst().ifPresent { e: T -> orderedEndpoints.add(e) }
    }

    val remainingEndpointIds: MutableList<String> = ArrayList(endpointIdsBySpeechActivity)
    remainingEndpointIds.removeAll(selectedEndpointIds)
    remainingEndpointIds.forEach {
        conferenceEndpoints
            .stream()
            .filter { e: T -> e!!.id == it }
            .findFirst().ifPresent { e: T -> orderedEndpoints.add(e) }
    }
    return orderedEndpoints
}

/**
 * Return the "effective" constraints for the given endpoints, i.e. the constraints adjusted for LastN.
 */
fun <T : MediaSourceContainer> getEffectiveConstraints(endpoints: List<T>, allocationSettings: AllocationSettings):
    Map<String, VideoConstraints2> {

        val effectiveLastN = effectiveLastN(allocationSettings.lastN)
        return endpoints.mapIndexed { i, endpoint ->
            endpoint.id to if (i >= effectiveLastN) {
                VideoConstraints2(0)
            } else {
                allocationSettings.getConstraints(endpoint.id)
            }
        }.toMap()
    }

/**
 * The LastN value adjusted according to the limits configured on the bridge, or [Int.MAX_VALUE] if LastN is disabled.
 */
private fun effectiveLastN(lastN: Int): Int {
    val adjustedLastN = calculateLastN(lastN, jvbLastNSingleton.jvbLastN)
    return if (adjustedLastN < 0) Int.MAX_VALUE else adjustedLastN
}
