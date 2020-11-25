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
import org.jitsi.nlj.RtpLayerDesc

/**
 * The result of bitrate allocation.
 */
class Allocation(
    val allocations: Set<SingleAllocation>
) {
    val oversending: Boolean
        get() = allocations.any { it.oversending }

    val forwardedEndpoints: Set<String>
        get() = allocations.filter { it.isForwarded() }.map { it.endpointId }.toSet()

    /**
     * Whether the two allocations have the same endpoints and same layers.
     */
    fun isTheSameAs(other: Allocation) =
        allocations.size == other.allocations.size &&
            oversending == other.oversending &&
            allocations.all { allocation ->
                other.allocations.any { otherAllocation ->
                    allocation.endpointId == otherAllocation.endpointId &&
                        allocation.targetLayer?.index == otherAllocation.targetLayer?.index &&
                        allocation.idealLayer?.index == otherAllocation.idealLayer?.index
                }
            }
}

/**
 * The result of bitrate allocation for a specific [MediaSourceDesc].
 */
data class SingleAllocation(
    val endpointId: String,
    val source: MediaSourceDesc?,
    /**
     * The layer which has been selected to be forwarded.
     */
    val targetLayer: RtpLayerDesc?,
    /**
     * The layer which would have been selected without bandwidth constraints.
     */
    val idealLayer: RtpLayerDesc?,
    val effectiveVideoConstraints: VideoConstraints2,
    /**
     * Set to true if the selected/target layer has higher bitrate than the available bandwidth.
     */
    val oversending: Boolean
) {
    private val targetIndex: Int
        get() = targetLayer?.index ?: -1
    fun isForwarded(): Boolean = targetIndex > -1
}
