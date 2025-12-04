/*
 * Copyright @ 2021 - present 8x8, Inc.
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

import org.jitsi.nlj.RtpLayerDesc

/**
 * Saves the bitrate of a specific [RtpLayerDesc] at a specific point in time.
 */
data class LayerSnapshot(val layer: RtpLayerDesc, val bitrate: Long)

/**
 * An immutable representation of the layers to be considered when allocating bandwidth for an endpoint. The order is
 * ascending by preference (and not necessarily bitrate).
 */
data class Layers(
    val layers: List<LayerSnapshot>,
    /** The index of the "preferred" layer, i.e. the layer up to which we allocate eagerly. */
    val preferredIndex: Int,
    /**
     * The index of the layer which will be selected if oversending is enabled. If set to -1, oversending is disabled.
     */
    val oversendIndex: Int
) : List<LayerSnapshot> by layers {
    val preferredLayer = layers.getOrNull(preferredIndex)
    val oversendLayer = layers.getOrNull(oversendIndex)
    val idealLayer = layers.lastOrNull()

    companion object {
        val noLayers = Layers(emptyList(), -1, -1)
    }
}
