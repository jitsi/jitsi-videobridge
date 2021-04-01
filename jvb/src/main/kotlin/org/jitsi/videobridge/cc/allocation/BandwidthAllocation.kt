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
 * The result of bandwidth allocation.
 */
class BandwidthAllocation @JvmOverloads constructor(
    val allocations: Set<SingleAllocation>,
    val oversending: Boolean = false
) {
    val forwardedEndpoints: Set<String> =
        allocations.filter { it.isForwarded() }.map { it.endpointId }.toSet()

    /**
     * Whether the two allocations have the same endpoints and same layers.
     */
    fun isTheSameAs(other: BandwidthAllocation) =
        allocations.size == other.allocations.size &&
            oversending == other.oversending &&
            allocations.all { allocation ->
                other.allocations.any { otherAllocation ->
                    allocation.endpointId == otherAllocation.endpointId &&
                        allocation.source?.primarySSRC == otherAllocation.source?.primarySSRC &&
                        allocation.targetLayer?.index == otherAllocation.targetLayer?.index
                }
            }

    /**
     * Whether this allocation is forwarding a source from an endpoint with this ID.
     */
    fun isForwarding(epId: String): Boolean = forwardedEndpoints.contains(epId)

    override fun toString(): String = "oversending=$oversending " + allocations.joinToString()
}

/**
 * The result of bandwidth allocation for a specific endpoint and a [MediaSourceDesc].
 */
data class SingleAllocation(
    val endpointId: String,
    val source: MediaSourceDesc? = null,
    /**
     * The layer which has been selected to be forwarded.
     */
    val targetLayer: RtpLayerDesc? = null,
    /**
     * The layer which would have been selected without bandwidth constraints.
     */
    val idealLayer: RtpLayerDesc? = null
) {
    private val targetIndex: Int
        get() = targetLayer?.index ?: -1
    fun isForwarded(): Boolean = targetIndex > -1

    /**
     * Exclude [source]
     */
    override fun toString(): String = "[id=$endpointId target=${targetLayer?.height}/${targetLayer?.frameRate} " +
        "ideal=${idealLayer?.height}/${idealLayer?.frameRate}"
}
