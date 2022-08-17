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
import org.jitsi.videobridge.cc.config.BitrateControllerConfig.Companion.config
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import org.jitsi.videobridge.util.endpointIdToSourceName

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
internal class AllocationSettingsWrapper(private val useSourceNames: Boolean) {
    /**
     * The last selected endpoints set signaled by the receiving endpoint.
     */
    @Deprecated("", ReplaceWith("selectedSources"), DeprecationLevel.WARNING)
    private var selectedEndpoints = emptyList<String>()

    /**
     * The last selected sources set signaled by the receiving endpoint.
     */
    private var selectedSources = emptyList<String>()

    internal var lastN: Int = -1

    private var videoConstraints: Map<String, VideoConstraints> = emptyMap()

    private var defaultConstraints: VideoConstraints = VideoConstraints(config.thumbnailMaxHeightPx())

    @Deprecated("", ReplaceWith("onStageSources"), DeprecationLevel.WARNING)
    private var onStageEndpoints: List<String> = emptyList()

    private var onStageSources: List<String> = emptyList()

    private var allocationSettings = create()

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

    fun setBandwidthAllocationSettings(message: ReceiverVideoConstraintsMessage): Boolean {
        var changed = false

        message.lastN?.let {
            if (lastN != it) {
                lastN = it
                changed = true
            }
        }
        if (MultiStreamConfig.config.enabled) {
            if (useSourceNames) {
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
            } else {
                message.selectedEndpoints?.let {
                    val newSelectedSources = it.map { endpoint -> endpointIdToSourceName(endpoint) }
                    if (selectedSources != newSelectedSources) {
                        selectedSources = newSelectedSources
                        changed = true
                    }
                }
                message.onStageEndpoints?.let {
                    val newOnStageSources = it.map { endpoint -> endpointIdToSourceName(endpoint) }
                    if (onStageSources != newOnStageSources) {
                        onStageSources = newOnStageSources
                        changed = true
                    }
                }
            }
        } else {
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
        }
        message.defaultConstraints?.let {
            if (defaultConstraints != it) {
                defaultConstraints = it
                changed = true
            }
        }
        message.constraints?.let {
            var newConstraints = it

            // Convert endpoint IDs to source names
            if (MultiStreamConfig.config.enabled && !useSourceNames) {
                newConstraints = HashMap(it.size)
                it.entries.stream().forEach {
                        entry ->
                    newConstraints[endpointIdToSourceName(entry.key)] = entry.value
                }
            }

            if (this.videoConstraints != newConstraints) {
                this.videoConstraints = newConstraints
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
     */
    fun setLastN(lastN: Int): Boolean {
        if (this.lastN != lastN) {
            this.lastN = lastN
            allocationSettings = create()
            return true
        }
        return false
    }
}
