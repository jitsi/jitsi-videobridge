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
import org.jitsi.videobridge.SsrcLimitConfig
import org.jitsi.videobridge.calculateLastN
import org.jitsi.videobridge.jvbLastNSingleton
import org.jitsi.videobridge.load_management.ConferenceSizeLastNLimits.Companion.singleton as conferenceSizeLimits

fun prioritize(
    /**
     * All available sources ordered by their endpoint's speech activity and video availability. Note that when an
     * endpoint has multiple sources and one of them is disabled it will still sort high because the endpoint has
     * video availability.
     */
    conferenceSources: MutableList<MediaSourceDesc>,
    selectedSourceNames: List<String> = emptyList()
): List<MediaSourceDesc> {
    val enabledSelectedSources = mutableListOf<MediaSourceDesc>()
    val enabledNonSelectedSources = mutableListOf<MediaSourceDesc>()
    val disabledSources = mutableListOf<MediaSourceDesc>()

    // conferenceSources can be large, while selectedSourceNames is usually small, so do a single pass over
    // conferenceSources.
    conferenceSources.forEach { source ->
        if (source.videoType.isEnabled()) {
            if (selectedSourceNames.contains(source.sourceName)) {
                enabledSelectedSources.add(source)
            } else {
                enabledNonSelectedSources.add(source)
            }
        } else {
            disabledSources.add(source)
        }
    }
    // The enabled selected sources are sorted according to the order in which they are selected and prioritized
    // over non-selected.
    enabledSelectedSources.sortBy { selectedSourceNames.indexOf(it.sourceName) }
    enabledSelectedSources.addAll(enabledNonSelectedSources)
    // All disabled sources are sorted last, regardless of whether they are selected.
    enabledSelectedSources.addAll(disabledSources)

    return enabledSelectedSources
}

/**
 * Return the "effective" constraints for the given media sources, i.e. the constraints adjusted for LastN.
 */
fun getEffectiveConstraints(
    sources: List<MediaSourceDesc>,
    allocationSettings: AllocationSettings
): EffectiveConstraintsMap {
    // FIXME figure out before merge - is using source count instead of endpoints
    // Add 1 for the receiver endpoint, which is not in the list.
    val effectiveLastN = effectiveLastN(allocationSettings.lastN, sources.size + 1)

    // Keep track of the number of sources with non-zero constraints. Once [effectiveLastN] of them have been
    // added, all other sources have effectiveConstraints 0, because they would never be forwarded by the
    // algorithm.
    var sourcesWithNonZeroConstraints = 0

    return sources.associateWith { source ->
        if (!source.videoType.isEnabled() || sourcesWithNonZeroConstraints >= effectiveLastN) {
            VideoConstraints.NOTHING
        } else {
            allocationSettings.getConstraints(source.sourceName).also {
                if (!it.isDisabled()) sourcesWithNonZeroConstraints++
            }
        }
    }
}

/**
 * The LastN value adjusted according to the limits configured on the bridge, or [Int.MAX_VALUE] if LastN is disabled.
 */
private fun effectiveLastN(lastN: Int, conferenceSize: Int): Int {
    val adjustedLastN =
        calculateLastN(
            lastN,
            jvbLastNSingleton.jvbLastN,
            conferenceSizeLimits.getLastNLimit(conferenceSize),
            SsrcLimitConfig.config.maxVideoSsrcs
        )
    return if (adjustedLastN < 0) Int.MAX_VALUE else adjustedLastN
}
