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
import org.jitsi.videobridge.cc.VideoConstraintsCompatibility

class AllocationSettings {
    /**
     * The last selected endpoints set signaled by the receiving endpoint.
     */
    private var selectedEndpoints = emptySet<String>()

    /**
     * The last max resolution signaled by the receiving endpoint.
     */
    private var maxFrameHeight = Int.MAX_VALUE

    private val videoConstraintsCompatibility = VideoConstraintsCompatibility().apply {
        setSelectedEndpoints(selectedEndpoints)
        setMaxFrameHeight(maxFrameHeight)
    }

    private var videoConstraints = videoConstraintsCompatibility.computeVideoConstraints()

    fun setMaxFrameHeight(maxFrameHeight: Int): Boolean {
        if (this.maxFrameHeight != maxFrameHeight)
        {
            this.maxFrameHeight = maxFrameHeight
            videoConstraintsCompatibility.setMaxFrameHeight(maxFrameHeight)
            return setVideoConstraints(videoConstraintsCompatibility.computeVideoConstraints())
        }
        return false
    }

    fun setSelectedEndpoints(selectedEndpoints: Set<String>): Boolean {
        if (this.selectedEndpoints != selectedEndpoints) {
            this.selectedEndpoints = selectedEndpoints
            videoConstraintsCompatibility.setSelectedEndpoints(selectedEndpoints)
            setVideoConstraints(videoConstraintsCompatibility.computeVideoConstraints())
            // selectedEndpoints is part of the snapshot, so it has changed no matter whether the constraints also
            // changed.
            return true
        }
        return false
    }

    private fun setVideoConstraints(videoConstraints: Map<String, VideoConstraints>): Boolean {
        if (this.videoConstraints != videoConstraints) {
            this.videoConstraints = videoConstraints
            return true
        }
        return false
    }


    fun snapshot() = Snapshot(selectedEndpoints, videoConstraints)

    data class Snapshot(
        val selectedEndpoints: Set<String>,
        val videoConstraints: Map<String, VideoConstraints>
    ) {
        override fun toString(): String = OrderedJsonObject().apply {
            put("selected_endpoints", selectedEndpoints)
            put("video_constraints", videoConstraints)
        }.toJSONString()
    }

}