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
import org.jitsi.nlj.RtpLayerDesc.Companion.indexString
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.videobridge.cc.config.BitrateControllerConfig
import org.jitsi.videobridge.cc.config.BitrateControllerConfig.Companion.onstagePreferredFramerate
import org.jitsi.videobridge.cc.config.BitrateControllerConfig.Companion.onstagePreferredHeightPx
import org.jitsi.videobridge.util.VideoType
import java.lang.Integer.max
import java.time.Clock

/**
 * A bitrate allocation that pertains to a specific source. This is the internal representation used in the allocation
 * algorithm, as opposed to [SingleAllocation] which is the end result.
 *
 * @author George Politis
 */
internal class SingleSourceAllocation(
    val endpoint: MediaSourceContainer,
    /** The constraints to use while allocating bandwidth to this endpoint. */
    val constraints: VideoConstraints,
    /** Whether the endpoint is on stage. */
    private val onStage: Boolean,
    diagnosticContext: DiagnosticContext,
    clock: Clock
) {
    /** An array that holds the layers to be considered when allocating bandwidth. Exposed for testing only. */
    val layers: List<LayerSnapshot>

    /** The index (into [.layers]) of the "preferred" layer, i.e. the layer up to which we allocate eagerly. */
    val preferredIdx: Int

    /**
     * The index of the current target layer. It can be improved in the `improve()` step, if there is enough
     * bandwidth.
     */
    var targetIdx = -1

    /**
     * The index (into [layers]) of the layer which will be selected if oversending is enabled. If set to -1,
     * oversending is disabled.
     */
    val oversendIdx: Int

    init {
        val (layers, preferredIdx, oversendIdx) = selectLayers(
            endpoint,
            onStage,
            constraints,
            clock.instant().toEpochMilli()
        )
        this.preferredIdx = preferredIdx
        this.layers = layers
        this.oversendIdx = oversendIdx

        if (timeSeriesLogger.isTraceEnabled) {
            val ratesTimeSeriesPoint = diagnosticContext.makeTimeSeriesPoint("layers_considered")
                .addField("remote_endpoint_id", endpoint.id)
            for ((l, bitrate) in layers) {
                ratesTimeSeriesPoint.addField(
                    "${indexString(l.index)}_${l.height}p_${l.frameRate}fps_bps",
                    bitrate
                )
            }
            timeSeriesLogger.trace(ratesTimeSeriesPoint)
        }
    }

    fun isOnStage() = onStage

    /**
     * Implements an "improve" step, incrementing [.targetIdx] to the next layer if there is sufficient
     * bandwidth. Note that this works eagerly up until the "preferred" layer (if any), and as a single step from
     * then on.
     *
     * @param maxBps the bandwidth available.
     */
    fun improve(maxBps: Long) {
        if (layers.isEmpty()) {
            return
        }
        if (targetIdx == -1 && preferredIdx > -1 && onStage) {
            // Boost on stage participant to preferred, if there's enough bw.
            for (i in layers.indices) {
                if (i > preferredIdx || maxBps < layers[i].bitrate) {
                    break
                }
                targetIdx = i
            }
        } else {
            // Try the next element in the ratedIndices array.
            if (targetIdx + 1 < layers.size && layers[targetIdx + 1].bitrate < maxBps) {
                targetIdx++
            }
        }
        if (targetIdx > -1) {
            // If there's a higher layer available with a lower bitrate, skip to it.
            //
            // For example, if 1080p@15fps is configured as a better subjective quality than 720p@30fps (i.e. it sits
            // on a higher index in the ratedIndices array) and the bitrate that we measure for the 1080p stream is less
            // than the bitrate that we measure for the 720p stream, then we "jump over" the 720p stream and immediately
            // select the 1080p stream.
            //
            // TODO further: Should we just prune the list of layers we consider to not include such layers?
            for (i in layers.size - 1 downTo targetIdx + 1) {
                if (layers[i].bitrate <= layers[targetIdx].bitrate) {
                    targetIdx = i
                }
            }
        }
    }

    /** The source is suspended if we've not selected a layer AND the source has active layers. */
    val isSuspended: Boolean
        get() = targetIdx == -1 && layers.isNotEmpty() && layers[0].bitrate > 0

    /**
     * Gets the target bitrate (in bps) for this endpoint allocation, i.e. the bitrate of the currently chosen layer.
     */
    val targetBitrate: Long
        get() {
            val targetLayer = targetLayer
            return targetLayer?.bitrate?.toLong() ?: 0
        }

    private val targetLayer: LayerSnapshot?
        get() = layers.getOrNull(targetIdx)

    /**
     * Exposed for testing only.
     */
    val preferredLayer: RtpLayerDesc?
        get() = layers.getOrNull(preferredIdx)?.layer

    private val idealLayer: LayerSnapshot?
        get() = layers.lastOrNull()

    val oversendLayer: RtpLayerDesc?
        get() = layers.getOrNull(oversendIdx)?.layer

    /**
     * If there is no target layer, switch to the layer chosen for oversending (if any are available).
     * @return true if the target layer was changed.
     */
    fun maybeEnableOversending(): Boolean {
        if (oversendIdx >= 0 && targetIdx < oversendIdx) {
            targetIdx = oversendIdx
            return true
        }
        return false
    }

    /**
     * Creates the final immutable result of this allocation. Should be called once the allocation algorithm has
     * completed.
     */
    val result: SingleAllocation
        get() = SingleAllocation(
            endpoint,
            targetLayer?.layer,
            idealLayer?.layer
        )

    override fun toString(): String {
        return (
            "[id=" + endpoint.id +
                " constraints=" + constraints +
                " ratedPreferredIdx=" + preferredIdx +
                " ratedTargetIdx=" + targetIdx
            )
    }

    companion object {
        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(BandwidthAllocator::class.java)
    }
}

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
 * Selects from the layers of a [MediaSourceDesc] the ones which should be considered when allocating bandwidth for
 * an endpoint. Also returns the index of the "preferred" layer.
 *
 * @param endpoint the [MediaSourceContainer] that describes the available layers.
 * @param constraints the constraints signaled for the endpoint.
 * @return the subset of [endpoint]'s layers which should be considered when allocating bandwidth, and the index of the
 * "preferred" layer.
 */
private fun selectLayers(
    endpoint: MediaSourceContainer,
    onStage: Boolean,
    constraints: VideoConstraints,
    nowMs: Long
): Layers {
    val source = endpoint.mediaSource
    if (constraints.maxHeight <= 0 || source == null || !source.hasRtpLayers()) {
        return noLayers
    }
    val layers = source.rtpLayers.map { LayerSnapshot(it, it.getBitrateBps(nowMs)) }

    return when (endpoint.videoType) {
        VideoType.CAMERA -> selectLayersForCamera(layers, constraints)
        VideoType.NONE -> noLayers
        VideoType.DESKTOP -> selectLayersForScreensharing(layers, constraints, onStage)
    }
}

typealias Layers = Triple<List<LayerSnapshot>, Int, Int>
private val noLayers = Layers(emptyList(), -1, -1)

private fun selectLayersForScreensharing(
    layers: List<LayerSnapshot>,
    constraints: VideoConstraints,
    onStage: Boolean
): Layers {

    var activeLayers = layers.filter { it.bitrate > 0 }
    // No active layers usually happens when the source has just been signaled and we haven't received
    // any packets yet. Add the layers here, so one gets selected and we can start forwarding sooner.
    if (activeLayers.isEmpty()) activeLayers = layers

    // We select all layers that satisfy the constraints.
    var selectedLayers =
        if (constraints.maxHeight < 0) {
            activeLayers
        } else {
            activeLayers.filter { it.layer.height <= constraints.maxHeight }
        }
    // If no layers satisfy the constraints, we use the layers with the lowest resolution.
    if (selectedLayers.isEmpty()) {
        val minHeight = activeLayers.map { it.layer.height }.minOrNull() ?: return noLayers
        selectedLayers = activeLayers.filter { it.layer.height == minHeight }
    }

    val oversendIdx = if (onStage && BitrateControllerConfig.allowOversendOnStage()) {
        val maxHeight = selectedLayers.map { it.layer.height }.maxOrNull() ?: return noLayers
        selectedLayers.firstIndexWhich { it.layer.height == maxHeight }
    } else {
        -1
    }
    return Triple(selectedLayers, selectedLayers.size - 1, oversendIdx)
}

private fun <T> List<T>.firstIndexWhich(predicate: (T) -> Boolean): Int {
    forEachIndexed { index, item ->
        if (predicate(item)) return index
    }
    return -1
}

private fun selectLayersForCamera(
    layers: List<LayerSnapshot>,
    constraints: VideoConstraints,
): Layers {

    val minHeight = layers.map { it.layer.height }.minOrNull() ?: return noLayers
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
        if (lessThanPreferredHeight ||
            (lessThanOrEqualMaxHeight && atLeastPreferredFps) ||
            layer.height == minHeight
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
    return Layers(ratesList, preferredIndex, -1)
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
data class LayerSnapshot(val layer: RtpLayerDesc, val bitrate: Double)
