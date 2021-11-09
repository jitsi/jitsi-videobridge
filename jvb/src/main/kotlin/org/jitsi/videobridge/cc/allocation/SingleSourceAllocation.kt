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
import org.jitsi.nlj.RtpLayerDesc.Companion.indexString
import org.jitsi.nlj.VideoType
import org.jitsi.utils.logging.DiagnosticContext
import org.jitsi.utils.logging.TimeSeriesLogger
import org.jitsi.videobridge.cc.config.BitrateControllerConfig
import org.jitsi.videobridge.cc.config.BitrateControllerConfig.Companion.onstagePreferredFramerate
import org.jitsi.videobridge.cc.config.BitrateControllerConfig.Companion.onstagePreferredHeightPx
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
    /**
     * The immutable list of layers to be considered when allocating bandwidth.
     */
    val layers: Layers = selectLayers(endpoint, onStage, constraints, clock.instant().toEpochMilli())

    /**
     * The index (into [layers] of the current target layer). It can be improved in the `improve()` step, if there is
     * enough bandwidth.
     */
    var targetIdx = -1

    init {
        if (timeSeriesLogger.isTraceEnabled) {
            val ratesTimeSeriesPoint = diagnosticContext.makeTimeSeriesPoint("layers_considered")
                .addField("remote_endpoint_id", endpoint.id)
            for ((l, bitrate) in layers.layers) {
                ratesTimeSeriesPoint.addField(
                    "${indexString(l.index)}_${l.height}p_${l.frameRate}fps_bps",
                    bitrate
                )
            }
            timeSeriesLogger.trace(ratesTimeSeriesPoint)
        }
    }

    fun isOnStage() = onStage
    fun hasReachedPreferred(): Boolean = targetIdx >= layers.preferredIndex

    /**
     * Implements an "improve" step, incrementing [.targetIdx] to the next layer if there is sufficient
     * bandwidth. Note that this works eagerly up until the "preferred" layer (if any), and as a single step from
     * then on.
     *
     * @param remainingBps the additional bandwidth which is available on top of the bitrate of the current target
     * layer.
     * @return the bandwidth "consumed" by the method, i.e. the difference between the resulting and initial target
     * bitrate. E.g. if the target bitrate goes from 100 to 300 as a result if the method call, it will return 200.
     */
    fun improve(remainingBps: Long, allowOversending: Boolean): Long {
        val initialTargetBitrate = targetBitrate
        val maxBps = remainingBps + initialTargetBitrate
        if (layers.isEmpty()) {
            return 0
        }
        if (targetIdx == -1 && layers.preferredIndex > -1 && onStage) {
            // Boost on stage participant to preferred, if there's enough bw.
            for (i in layers.indices) {
                if (i > layers.preferredIndex || maxBps < layers[i].bitrate) {
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

        // If oversending is allowed, look for a better layer which doesn't exceed maxBps by more than
        // `maxOversendBitrate`.
        if (allowOversending && layers.oversendIndex >= 0 && targetIdx < layers.oversendIndex) {
            for (i in layers.oversendIndex downTo targetIdx + 1) {
                if (layers[i].bitrate <= maxBps + BitrateControllerConfig.maxOversendBitrateBps()) {
                    targetIdx = i
                }
            }
        }

        val resultingTargetBitrate = targetBitrate
        return resultingTargetBitrate - initialTargetBitrate
    }

    /**
     * The source is suspended if we've not selected a layer AND the source has active layers.
     *
     * TODO: this is not exactly correct because it only looks at the layers we consider. E.g. if the receiver set
     * a maxHeight=0 constraint for an endpoint, it will appear suspended. This is not critical, because this val is
     * only used for logging.
     */
    val isSuspended: Boolean
        get() = targetIdx == -1 && layers.isNotEmpty() && layers[0].bitrate > 0

    /**
     * Gets the target bitrate (in bps) for this endpoint allocation, i.e. the bitrate of the currently chosen layer.
     */
    val targetBitrate: Long
        get() = targetLayer?.bitrate?.toLong() ?: 0

    private val targetLayer: LayerSnapshot?
        get() = layers.getOrNull(targetIdx)

    /**
     * Gets the ideal bitrate (in bps) for this endpoint allocation, i.e. the bitrate of the layer the bridge would
     * forward if there were no (bandwidth) constraints.
     */
    val idealBitrate: Long
        get() = layers.idealLayer?.bitrate?.toLong() ?: 0

    /**
     * Exposed for testing only.
     */
    val preferredLayer: RtpLayerDesc?
        get() = layers.preferredLayer?.layer

    /**
     * Exposed for testing only.
     */
    val oversendLayer: RtpLayerDesc?
        get() = layers.oversendLayer?.layer

    /**
     * Creates the final immutable result of this allocation. Should be called once the allocation algorithm has
     * completed.
     */
    val result: SingleAllocation
        get() = SingleAllocation(
            endpoint.id,
            endpoint.mediaSource,
            targetLayer?.layer,
            layers.idealLayer?.layer
        )

    override fun toString(): String {
        return (
            "[id=" + endpoint.id +
                " constraints=" + constraints +
                " ratedPreferredIdx=" + layers.preferredIndex +
                " ratedTargetIdx=" + targetIdx
            )
    }

    companion object {
        private val timeSeriesLogger = TimeSeriesLogger.getTimeSeriesLogger(BandwidthAllocator::class.java)
    }
}

/**
 * Saves the bitrate of a specific [RtpLayerDesc] at a specific point in time.
 */
data class LayerSnapshot(val layer: RtpLayerDesc, val bitrate: Double)

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

/**
 * Gets the "preferred" height and frame rate based on the constraints signaled from the receiver.
 *
 * For participants with sufficient maxHeight we favor frame rate over resolution. We consider all
 * temporal layers for resolutions lower than the preferred, but for resolutions >= preferred, we only
 * consider frame rates at least as high as the preferred. In practice this means we consider
 * 180p/7.5fps, 180p/15fps, 180p/30fps, 360p/30fps and 720p/30fps.
 */
private fun getPreferred(constraints: VideoConstraints): Pair<Int, Double> {
    return if (constraints.maxHeight > 180) {
        Pair(onstagePreferredHeightPx(), onstagePreferredFramerate())
    } else {
        noPreferredHeightAndFrameRate
    }
}

private val noPreferredHeightAndFrameRate = Pair(-1, -1.0)

/**
 * Selects from the layers of a [MediaSourceContainer] the ones which should be considered when allocating bandwidth for
 * an endpoint. Also selects the indices of the "preferred" and "oversend" layers.
 *
 * @param endpoint the [MediaSourceContainer] that describes the available layers.
 * @param constraints the constraints signaled for the endpoint.
 * @return the ordered list of [endpoint]'s layers which should be considered when allocating bandwidth, as well as the
 * indices of the "preferred" and "oversend" layers.
 */
private fun selectLayers(
    /** The endpoint which is the source of the stream(s). */
    endpoint: MediaSourceContainer,
    onStage: Boolean,
    /** The constraints that the receiver specified for [endpoint]. */
    constraints: VideoConstraints,
    nowMs: Long
): Layers {
    val source = endpoint.mediaSource
    if (constraints.maxHeight <= 0 || source == null || !source.hasRtpLayers()) {
        return Layers.noLayers
    }
    val layers = source.rtpLayers.map { LayerSnapshot(it, it.getBitrateBps(nowMs)) }

    return when (endpoint.videoType) {
        VideoType.CAMERA -> selectLayersForCamera(layers, constraints)
        VideoType.DESKTOP, VideoType.DESKTOP_HIGH_FPS -> selectLayersForScreensharing(layers, constraints, onStage)
        else -> Layers.noLayers
    }
}

/**
 * Selects from a list of layers the ones which should be considered when allocating bandwidth, as well as the
 * "preferred" and "oversend" layers. Logic specific to screensharing: we prioritize resolution over framerate,
 * prioritize the highest layer over other endpoints (by setting the highest layer as "preferred"), and allow
 * oversending up to the highest resolution (with low frame rate).
 */
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
        val minHeight = activeLayers.map { it.layer.height }.minOrNull() ?: return Layers.noLayers
        selectedLayers = activeLayers.filter { it.layer.height == minHeight }

        // This recognizes the structure used with VP9 (multiple encodings with the same resolution and unknown frame
        // rate). In this case, we only want the low quality layer.
        if (selectedLayers.isNotEmpty() && selectedLayers[0].layer.frameRate < 0) {
            selectedLayers = listOf(selectedLayers[0])
        }
    }

    val oversendIdx = if (onStage && BitrateControllerConfig.allowOversendOnStage()) {
        val maxHeight = selectedLayers.map { it.layer.height }.maxOrNull() ?: return Layers.noLayers
        selectedLayers.firstIndexWhich { it.layer.height == maxHeight }
    } else {
        -1
    }
    return Layers(selectedLayers, selectedLayers.size - 1, oversendIdx)
}

/** Return the index of the first item in the list which satisfies a predicate, or -1 if none do. */
private fun <T> List<T>.firstIndexWhich(predicate: (T) -> Boolean): Int {
    forEachIndexed { index, item ->
        if (predicate(item)) return index
    }
    return -1
}

/**
 * Selects from a list of layers the ones which should be considered when allocating bandwidth, as well as the
 * "preferred" and "oversend" layers. Logic specific to a camera stream: once the "preferred" height is reached we
 * require a high frame rate, with preconfigured values for the "preferred" height and frame rate, and we do not allow
 * oversending.
 */
private fun selectLayersForCamera(
    layers: List<LayerSnapshot>,
    constraints: VideoConstraints,
): Layers {

    val minHeight = layers.map { it.layer.height }.minOrNull() ?: return Layers.noLayers
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
