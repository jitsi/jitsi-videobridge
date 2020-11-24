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
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.bps
import org.jitsi.videobridge.VideoConstraints

/**
 * The result of bitrate allocation.
 */
class Allocation(
    val allocations: List<SingleAllocation>
) {
    val oversending: Boolean
        get() = allocations.any { it.oversending }
    val targetBitrate: Bandwidth
        get() = allocations.mapNotNull { it.targetLayer?.bitrate?.bps }.sum().bps
    val idealBitrate: Bandwidth
        get() = allocations.mapNotNull { it.idealLayer?.bitrate?.bps }.sum().bps

    val forwardedEndpoints: List<String>
        get() = allocations.filter { it.targetLayer != null }.map { it.endpointId }.toList()
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
    val targetLayer: LayerSnapshot?,
    /**
     * The layer which would have been selected without bandwidth constraints.
     */
    val idealLayer: LayerSnapshot?,
    val effectiveVideoConstraints: VideoConstraints,
    /**
     * Set to true if the selected/target layer has higher bitrate than the available bandwidth.
     */
    val oversending: Boolean
)

/**
 * Saves the bitrate of a specific [RtpLayerDesc] at a specific point in time.
 */
data class LayerSnapshot(
    val layer: RtpLayerDesc,
    val bitrate: Bandwidth
)
