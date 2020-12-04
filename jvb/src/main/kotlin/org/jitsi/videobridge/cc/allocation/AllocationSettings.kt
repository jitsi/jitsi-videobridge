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

import org.jitsi.nlj.util.OrderedJsonObject
import org.jitsi.videobridge.VideoConstraints
import org.jitsi.videobridge.cc.config.BitrateControllerConfig as config
import java.util.stream.Collectors
import kotlin.math.min

/**
 * This class encapsulates all of the client-controller settings for bitrate allocation.
 */
data class AllocationSettings(
    val selectedEndpoints: List<String> = emptyList(),
    val videoConstraints: Map<String, VideoConstraints> = emptyMap(),
    val lastN: Int = -1
) {
    override fun toString(): String = OrderedJsonObject().apply {
        put("selected_endpoints", selectedEndpoints)
        put("video_constraints", videoConstraints)
        put("last_n", lastN)
    }.toJSONString()
}

/**
 * Maintains an [AllocationSettings] instance and allows fields to be set individually, with an indication of whether
 * the overall state changed.
 */
internal class AllocationSettingsWrapper {
    /**
     * The last selected endpoints set signaled by the receiving endpoint.
     */
    private var selectedEndpoints = emptyList<String>()

    /**
     * The last max resolution signaled by the receiving endpoint.
     */
    private var maxFrameHeight = Int.MAX_VALUE

    internal var lastN: Int = -1

    private var videoConstraints = computeVideoConstraints(maxFrameHeight, selectedEndpoints)

    private var allocationSettings = create()

    private fun create() = AllocationSettings(selectedEndpoints, videoConstraints, lastN)
    fun get() = allocationSettings

    private fun computeVideoConstraints() = computeVideoConstraints(maxFrameHeight, selectedEndpoints)
    /**
     * Return `true` iff the [AllocationSettings] state changed.
     */
    fun setMaxFrameHeight(maxFrameHeight: Int): Boolean {
        if (this.maxFrameHeight != maxFrameHeight) {
            this.maxFrameHeight = maxFrameHeight
            return setVideoConstraints(computeVideoConstraints()).also {
                if (it) {
                    allocationSettings = create()
                }
            }
        }
        return false
    }

    /**
     * Return `true` iff the [AllocationSettings] state changed.
     */
    fun setSelectedEndpoints(selectedEndpoints: List<String>): Boolean {
        if (this.selectedEndpoints != selectedEndpoints) {
            this.selectedEndpoints = selectedEndpoints
            setVideoConstraints(computeVideoConstraints())
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
     * Return `true` iff the [AllocationSettings] state changed.
     */
    private fun setVideoConstraints(videoConstraints: Map<String, VideoConstraints>): Boolean {
        if (this.videoConstraints != videoConstraints) {
            this.videoConstraints = videoConstraints
            return true
        }
        return false
    }
}

/**
 * Computes the video constraints map (endpoint -> video constraints) for the selected endpoints and (global) max frame
 * height.
 *
 * For selected endpoints we set the "ideal" height to 720 reflecting the the receiver's "desire" to watch the track
 * in high resolution. We also set the "preferred" resolution and the "preferred" frame rate. Under the hood this
 * instructs the bitrate allocator to and eagerly allocate bandwidth up to the preferred resolution and
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
 * signal to the bitrate allocator that it needs to (evenly) distribute bandwidth across all participants, up to X.
 */
internal fun computeVideoConstraints(
    maxFrameHeight: Int,
    selectedEndpoints: List<String>
): Map<String, VideoConstraints> {
    val newVideoConstraints: MutableMap<String, VideoConstraints> = HashMap()
    if (selectedEndpoints.isNotEmpty()) {
        val selectedEndpointConstraints = if (selectedEndpoints.size > 1) {
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
            VideoConstraints(min(config.onstageIdealHeightPx(), maxFrameHeight))
        } else {
            // Stage view
            VideoConstraints(
                min(config.onstageIdealHeightPx(), maxFrameHeight),
                config.onstagePreferredHeightPx(),
                config.onstagePreferredFramerate()
            )
        }

        val selectedVideoConstraintsMap: Map<String, VideoConstraints> = selectedEndpoints
            .stream()
            .collect(Collectors.toMap({ e: String -> e }) { selectedEndpointConstraints })

        // Add video constraints for all the selected endpoints.
        newVideoConstraints.putAll(selectedVideoConstraintsMap)
    }
    return newVideoConstraints
}
