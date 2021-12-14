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

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.videobridge.calculateLastN
import org.jitsi.videobridge.jvbLastNSingleton
import org.jitsi.videobridge.load_management.ConferenceSizeLastNLimits.Companion.singleton as conferenceSizeLimits

/**
 * @param selectedEndpointIds the IDs of the selected endpoints, in order of selection.
 * @param conferenceEndpoints the conference endpoints in no particular order.
 *
 * @return the endpoints from `conferenceEndpoints` ordered by selection first, and then speech activity.
 */
@Deprecated("", ReplaceWith("prioritize2"), DeprecationLevel.WARNING)
fun <T : MediaSourceContainer> prioritize(
    conferenceEndpoints: MutableList<T>,
    selectedEndpointIds: List<String> = emptyList()
): List<T> {
    // Bump selected endpoints to the top of the list.
    selectedEndpointIds.asReversed().forEach { selectedEndpointId ->
        conferenceEndpoints.find { it.id == selectedEndpointId }?.let { selectedEndpoint ->
            conferenceEndpoints.remove(selectedEndpoint)
            conferenceEndpoints.add(0, selectedEndpoint)
        }
    }
    return conferenceEndpoints
}

fun prioritize2(
    conferenceSources: MutableList<MediaSourceDesc>,
    selectedSourceNames: List<String> = emptyList()
): List<MediaSourceDesc> {
    // Bump selected sources to the top of the list.
    selectedSourceNames.asReversed().forEach { selectedSourceName ->
        // Note the usage of sourceName!! which is expected to be always defined in the multi-stream mode
        // If you get an NPE here, that means Jicofo is not polyfilling the source names correctly for the clients
        // which do not support it yet.
        conferenceSources.find { it.sourceName!! == selectedSourceName }?.let { selectedSource ->
            conferenceSources.remove(selectedSource)
            conferenceSources.add(0, selectedSource)
        }
    }
    return conferenceSources
}

/**
 * Return the "effective" constraints for the given endpoints, i.e. the constraints adjusted for LastN.
 */
fun <T : MediaSourceContainer> getEffectiveConstraints(endpoints: List<T>, allocationSettings: AllocationSettings):
    Map<String, VideoConstraints> {

    // Add 1 for the receiver endpoint, which is not in the list.
    val effectiveLastN = effectiveLastN(allocationSettings.lastN, endpoints.size + 1)

    // Keep track of the number of endpoints with non-zero constraints. Once [effectiveLastN] of them have been
    // added, all other endpoints have effectiveConstraints 0, because they would never be forwarded by the
    // algorithm.
    var endpointsWithNonZeroConstraints = 0
    return endpoints.associate { endpoint ->
        endpoint.id to if (endpointsWithNonZeroConstraints >= effectiveLastN) {
            VideoConstraints.NOTHING
        } else {
            allocationSettings.getConstraints(endpoint.id).also {
                if (it.maxHeight > 0) endpointsWithNonZeroConstraints++
            }
        }
    }
}

/**
 * Return the "effective" constraints for the given media sources, i.e. the constraints adjusted for LastN.
 */
fun getEffectiveConstraints2(sources: List<MediaSourceDesc>, allocationSettings: AllocationSettings):
    Map<String, VideoConstraints> {

    // FIXME figure out before merge - is using source count instead of endpoints
    // Add 1 for the receiver endpoint, which is not in the list.
    val effectiveLastN = effectiveLastN(allocationSettings.lastN, sources.size + 1)

    // Keep track of the number of sources with non-zero constraints. Once [effectiveLastN] of them have been
    // added, all other sources have effectiveConstraints 0, because they would never be forwarded by the
    // algorithm.
    var sourcesWithNonZeroConstraints = 0

    // Note that source.sourceName!! is used, because Jicofo is supposed to inject source names for every source
    // when running in the multi-stream mode (or reject such sources in the future). A non-null value can not be
    // enforced on the Kotlin level, because of the legacy Endpoint ID signaling where the source names are not defined.
    return sources.associate { source ->
        (source.sourceName)!! to if (sourcesWithNonZeroConstraints >= effectiveLastN) {
            VideoConstraints.NOTHING
        } else {
            allocationSettings.getConstraints(source.sourceName!!).also {
                if (it.maxHeight > 0) sourcesWithNonZeroConstraints++
            }
        }
    }
}

/**
 * The LastN value adjusted according to the limits configured on the bridge, or [Int.MAX_VALUE] if LastN is disabled.
 */
private fun effectiveLastN(lastN: Int, conferenceSize: Int): Int {
    val adjustedLastN =
        calculateLastN(lastN, jvbLastNSingleton.jvbLastN, conferenceSizeLimits.getLastNLimit(conferenceSize))
    return if (adjustedLastN < 0) Int.MAX_VALUE else adjustedLastN
}
