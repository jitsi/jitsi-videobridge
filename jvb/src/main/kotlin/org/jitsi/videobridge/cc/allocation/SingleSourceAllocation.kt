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

import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.videobridge.cc.config.BitrateControllerConfig.Companion.onstagePreferredFramerate
import org.jitsi.videobridge.cc.config.BitrateControllerConfig.Companion.onstagePreferredHeightPx
import java.lang.Integer.max

/**
 * Gets the "preferred" height and frame rate based on the constraints signaled from the received.
 *
 * For participants with sufficient maxHeight we favor frame rate over resolution. We consider all
 * temporal layers for resolutions lower than the preferred, but for resolutions >= preferred, we only
 * consider frame rates at least as high as the preferred. In practice this means we consider
 * 180p/7.5fps, 180p/15fps, 180p/30fps, 360p/30fps and 720p/30fps.
 */
fun getPreferred(constraints: VideoConstraints): Pair<Int, Double> {
    return if (constraints.maxHeight > 180) {
        Pair(onstagePreferredHeightPx(), onstagePreferredFramerate())
    } else {
        Pair(-1, -1.0)
    }
}

/**
 * Selects from [layers] the ones which should be considered when allocating bandwidth for an endpoint. Also returns
 * the index of the "preferred" layer.
 *
 * @param layers all available layers from the endpoint.
 * @param constraints the constraints signaled for the endpoint.
 * @return the subset of [layers] which should be considered when allocating bandwidth, and the index of the "preferred"
 * layer.
 */
fun selectLayers(
    layers: List<LayerSnapshot>,
    constraints: VideoConstraints
): Pair<List<LayerSnapshot>, Int> {
    if (constraints.maxHeight == 0 || layers.isEmpty()) return Pair(emptyList(), -1)

    val minHeight = layers.map { it.layer.height }.minOrNull() ?: return Pair(emptyList(), -1)
    val noActiveLayers = layers.none { (_, bitrate) -> bitrate > 0 }
    val (preferredHeight, preferredFps) = getPreferred(constraints)

    val ratesList: MutableList<LayerSnapshot> = ArrayList()
    // Initialize the list of layers to be considered. These are the layers that satisfy the constraints, with
    // a couple of exceptions (see comments below).
    for (layerSnapshot in layers) {
        val layer = layerSnapshot.layer
        val lessThanPreferredHeight = layer.height < preferredHeight
        val lessThanOrEqualMaxHeight = layer.height <= constraints.maxHeight
        // If frame rate is unknown, consider it to be sufficient.
        val atLeastPreferredFps = layer.frameRate < 0 || layer.frameRate >= preferredFps
        if (lessThanPreferredHeight
            || (lessThanOrEqualMaxHeight && atLeastPreferredFps)
            || layer.height == minHeight
        ) {

            // No active layers usually happens when the source has just been signaled and we haven't received
            // any packets yet. Add the layers here, so one gets selected and we can start forwarding sooner.
            if (noActiveLayers || layerSnapshot.bitrate > 0) {
                ratesList.add(layerSnapshot)
            }
        }
    }

    val effectivePreferredHeight = max(preferredHeight, minHeight)
    val preferredIndex = ratesList.lastIndexWhich { it.layer.height <= effectivePreferredHeight }
    return Pair(ratesList, preferredIndex)
}

/**
 * Returns the index of the last element of this list which satisfies the given predicate, or -1 if no elements do.
 */
private fun <T> List<T>.lastIndexWhich(predicate: (T) -> Boolean): Int {
    var lastIndex = -1
    forEachIndexed { i, e -> if (predicate(e)) lastIndex = i }
    return lastIndex
}

/**
 * Saves the bitrate of a specific [RtpLayerDesc] at a specific point in time.
 */
data class LayerSnapshot (val layer: RtpLayerDesc, val bitrate: Double)
