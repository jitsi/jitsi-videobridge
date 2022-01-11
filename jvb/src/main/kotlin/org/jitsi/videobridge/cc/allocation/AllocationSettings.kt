/*
 * Copyright @ 2020 - present 8x8, Inc.
 * Copyright @ 2021 - Vowel, Inc.
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

import org.jitsi.utils.OrderedJsonObject
import org.jitsi.videobridge.MultiStreamConfig
import org.jitsi.videobridge.cc.config.BitrateControllerConfig
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import java.util.stream.Collectors
import kotlin.math.min

/**
 * This class encapsulates all of the client-controlled settings for bandwidth allocation.
 */
data class AllocationSettings @JvmOverloads constructor(
    @Deprecated("", ReplaceWith("onStageSources"), DeprecationLevel.WARNING)
    val onStageEndpoints: List<String> = emptyList(),
    @Deprecated("", ReplaceWith("selectedSources"), DeprecationLevel.WARNING)
    val selectedEndpoints: List<String> = emptyList(),
    val onStageSources: List<String> = emptyList(),
    val selectedSources: List<String> = emptyList(),
    val videoConstraints: Map<String, VideoConstraints> = emptyMap(),
    val lastN: Int = -1,
    val defaultConstraints: VideoConstraints
) {
    fun toJson() = OrderedJsonObject().apply {
        if (MultiStreamConfig.config.enabled) {
            put("on_stage_sources", onStageSources)
            put("selected_sources", selectedSources)
        } else {
            put("on_stage_endpoints", onStageEndpoints)
            put("selected_endpoints", selectedEndpoints)
        }
        put("video_constraints", videoConstraints)
        put("last_n", lastN)
        put("default_constraints", defaultConstraints)
    }

    override fun toString(): String = toJson().toJSONString()

    fun getConstraints(endpointId: String) = videoConstraints.getOrDefault(endpointId, defaultConstraints)
}

/**
 * Maintains an [AllocationSettings] instance and allows fields to be set individually, with an indication of whether
 * the overall state changed.
 */
internal class AllocationSettingsWrapper {
    /**
     * The last selected endpoints set signaled by the receiving endpoint.
     */
    @Deprecated("", ReplaceWith("selectedSources"), DeprecationLevel.WARNING)
    private var selectedEndpoints = emptyList<String>()

    /**
     * The last selected sources set signaled by the receiving endpoint.
     */
    private var selectedSources = emptyList<String>()

    /**
     * The last max resolution signaled by the receiving endpoint.
     */
    private var maxFrameHeight = Int.MAX_VALUE

    internal var lastN: Int = -1

    private var videoConstraints: Map<String, VideoConstraints> = emptyMap()

    private val config = BitrateControllerConfig()

    private var defaultConstraints: VideoConstraints = VideoConstraints(config.thumbnailMaxHeightPx())

    @Deprecated("", ReplaceWith("onStageSources"), DeprecationLevel.WARNING)
    private var onStageEndpoints: List<String> = emptyList()

    private var onStageSources: List<String> = emptyList()

    private var allocationSettings = create()

    /**
     * The set of selected endpoints last signaled via the legacy API ([setSelectedEndpoints]). We save them separately,
     * because they need to be considered when handling `maxFrameHeight`.
     */
    @Deprecated("Use the receiver constraints instead")
    private var signaledSelectedEndpoints = listOf<String>()

    private fun create(): AllocationSettings {
        if (MultiStreamConfig.config.enabled) {
            return AllocationSettings(
                onStageSources = onStageSources,
                selectedSources = selectedSources,
                videoConstraints = videoConstraints,
                defaultConstraints = defaultConstraints,
                lastN = lastN
            )
        } else {
            return AllocationSettings(
                onStageEndpoints = onStageEndpoints,
                selectedEndpoints = selectedEndpoints,
                videoConstraints = videoConstraints,
                defaultConstraints = defaultConstraints,
                lastN = lastN
            )
        }
    }

    fun get() = allocationSettings

    /**
     * Return `true` iff the [AllocationSettings] state changed.
     */
    @Deprecated("Use the ReceiverVideoConstraints msg - adjusting max frame height directly will not be supported")
    fun setMaxFrameHeight(maxFrameHeight: Int): Boolean {
        if (MultiStreamConfig.config.enabled) {
            return false
        }

        if (this.maxFrameHeight != maxFrameHeight) {
            this.maxFrameHeight = maxFrameHeight
            return updateVideoConstraints(maxFrameHeight, signaledSelectedEndpoints).also {
                if (it) {
                    allocationSettings = create()
                }
            }
        }
        return false
    }

    fun setBandwidthAllocationSettings(message: ReceiverVideoConstraintsMessage): Boolean {
        var changed = false

        message.lastN?.let {
            if (lastN != it) {
                lastN = it
                changed = true
            }
        }
        if (MultiStreamConfig.config.enabled) {
            message.selectedSources?.let {
                if (selectedSources != it) {
                    selectedSources = it
                    changed = true
                }
            }
            message.onStageSources?.let {
                if (onStageSources != it) {
                    onStageSources = it
                    changed = true
                }
            }
        }
        message.selectedEndpoints?.let {
            if (selectedEndpoints != it) {
                selectedEndpoints = it
                changed = true
            }
        }
        message.onStageEndpoints?.let {
            if (onStageEndpoints != it) {
                onStageEndpoints = it
                changed = true
            }
        }
        message.defaultConstraints?.let {
            if (defaultConstraints != it) {
                defaultConstraints = it
                changed = true
            }
        }
        message.constraints?.let {
            if (this.videoConstraints != it) {
                this.videoConstraints = it
                changed = true
            }
        }

        if (changed) {
            allocationSettings = create()
        }
        return changed
    }

    /**
     * Return `true` iff the [AllocationSettings] state changed.
     * Note: This is the legacy API, which updates the constraints and strategy based on the selected endpoints and
     * [maxFrameHeight]. To update just the selected endpoints, use [setBandwidthAllocationSettings].
     */
    @Deprecated("Use the ReceiverVideoConstraints msg - no legacy constraints in the multi-stream mode")
    fun setSelectedEndpoints(selectedEndpoints: List<String>): Boolean {
        if (MultiStreamConfig.config.enabled) {
            return false
        }

        signaledSelectedEndpoints = selectedEndpoints
        if (this.selectedEndpoints != selectedEndpoints) {
            this.selectedEndpoints = selectedEndpoints
            updateVideoConstraints(maxFrameHeight, selectedEndpoints)
            // selectedEndpoints is part of the snapshot, so it has changed no matter whether the constraints also
            // changed.
            allocationSettings = create()
            return true
        }
        return false
    }

    /**
     * Return `true` iff the [AllocationSettings] state changed.
     */
    fun setLastN(lastN: Int): Boolean {
        if (this.lastN != lastN) {
            this.lastN = lastN
            allocationSettings = create()
            return true
        }
        return false
    }

    /**
     * Computes the video constraints map (endpoint -> video constraints) for the selected endpoints and (global) max frame
     * height.
     *
     * For selected endpoints we set the "ideal" height to 720 reflecting the the receiver's "desire" to watch the track
     * in high resolution. We also set the "preferred" resolution and the "preferred" frame rate. Under the hood this
     * instructs the bandwidth allocator to and eagerly allocate bandwidth up to the preferred resolution and
     * preferred frame-rate.
     *
     * The max height constraint was added for tile-view back when everything was expressed as "selected" endpoints, the
     * idea being we mark everything as selected (so endpoints aren't limited to 180p) and set the max to 360p (so
     * endpoints are limited to 360p, instead of 720p which is normally used for selected endpoints. This was the quickest,
     * not the nicest way to implement the tile-view constraints signaling and it was subsequently used to implement
     * low-bandwidth mode.
     *
     * One negative side effect of this solution, other than being a hack, was that the eager bandwidth allocation that we
     * do for selected endpoints doesn't work well in tile-view because we end-up with a lot of ninjas.
     *
     * By simply setting an ideal height X as a global constraint, without setting a preferred resolution/frame-rate, we
     * signal to the bandwidth allocator that it needs to (evenly) distribute bandwidth across all participants, up to X.
     */
    @Deprecated("Use the ReceiverVideoConstraints msg - no legacy constraints in the multi-stream mode")
    private fun updateVideoConstraints(
        maxFrameHeight: Int,
        selectedEndpoints: List<String>
    ): Boolean {

        // This implements special handling for tile-view.
        // (selectedEndpoints.size() > 1) is equivalent to tile-view, so we use it as a clue to detect tile-view.
        //
        // (selectedEndpoints.size() > 1) implies tile-view because multiple "selected" endpoints has only ever
        // been used for tile-view.
        //
        // tile-view implies (selectedEndpoints.size() > 1) because,
        // (selectedEndpoints.size() <= 1) implies non tile-view because
        // soon as we click on a participant we exit tile-view, see:
        //
        // https://github.com/jitsi/jitsi-meet/commit/ebcde745ef34bd3d45a2d884825fdc48cfa16839
        // https://github.com/jitsi/jitsi-meet/commit/4cea7018f536891b028784e7495f71fc99fc18a0
        // https://github.com/jitsi/jitsi-meet/commit/29bc18df01c82cefbbc7b78f5aef7b97c2dee0e4
        // https://github.com/jitsi/jitsi-meet/commit/e63cd8c81bceb9763e4d57be5f2262c6347afc23
        //
        // In tile view we set the ideal height but not the preferred height nor the preferred frame-rate, because
        // we want even even distribution of bandwidth among all the tiles to avoid ninjas.
        val tileView = selectedEndpoints.size > 1

        val onStageConstraints = VideoConstraints(min(config.onstageIdealHeightPx(), maxFrameHeight))
        val newConstraints = selectedEndpoints.stream()
            .collect(Collectors.toMap({ e: String -> e }) { onStageConstraints })

        val newOnStageEndpoints = if (tileView) emptyList() else selectedEndpoints

        var changed = false
        if (newOnStageEndpoints != onStageEndpoints) {
            onStageEndpoints = newOnStageEndpoints
            changed = true
        }
        if (videoConstraints != newConstraints) {
            videoConstraints = newConstraints
            changed = true
        }
        // With the legacy signaling the client selects all endpoints in TileView, but does not want to override the
        // speaker order. In stage view, we use onStageEndpoints instead.
        if (this.selectedEndpoints.isNotEmpty()) {
            this.selectedEndpoints = emptyList()
            changed = true
        }

        return changed
    }
}
