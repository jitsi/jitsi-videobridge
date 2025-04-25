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

import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.util.bps
import org.jitsi.utils.OrderedJsonObject
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.cc.config.BitrateControllerConfig.Companion.config
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage

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
    val lastN: Int = defaultInitialLastN,
    val defaultConstraints: VideoConstraints,
    /** A non-negative value is assumed as the available bandwidth in bps. A negative value is ignored. */
    val assumedBandwidthBps: Long = -1
) {
    fun toJson() = OrderedJsonObject().apply {
        put("on_stage_sources", onStageSources)
        put("selected_sources", selectedSources)
        put("video_constraints", videoConstraints)
        put("last_n", lastN)
        put("default_constraints", defaultConstraints)
        put("assumed_bandwidth_bps", assumedBandwidthBps)
    }

    override fun toString(): String = toJson().toJSONString()

    fun getConstraints(endpointId: String) = videoConstraints.getOrDefault(endpointId, defaultConstraints)

    companion object {
        val defaultInitialLastN: Int by config {
            "videobridge.cc.initial-last-n".from(JitsiConfig.newConfig)
        }
    }
}

/**
 * Maintains an [AllocationSettings] instance and allows fields to be set individually, with an indication of whether
 * the overall state changed.
 */
internal class AllocationSettingsWrapper(
    parentLogger: Logger = LoggerImpl(AllocationSettingsWrapper::class.java.name)
) {
    private val logger = createChildLogger(parentLogger)

    /**
     * The last selected sources set signaled by the receiving endpoint.
     */
    private var selectedSources = emptyList<String>()

    internal var lastN: Int = AllocationSettings.defaultInitialLastN

    private var videoConstraints: Map<String, VideoConstraints> = emptyMap()

    private var defaultConstraints: VideoConstraints = VideoConstraints(config.defaultMaxHeightPx)

    private var assumedBandwidthBps: Long = -1

    private var onStageSources: List<String> = emptyList()

    private var allocationSettings = create()

    private fun create(): AllocationSettings = AllocationSettings(
        onStageSources = onStageSources,
        selectedSources = selectedSources,
        videoConstraints = videoConstraints,
        defaultConstraints = defaultConstraints,
        lastN = lastN,
        assumedBandwidthBps = assumedBandwidthBps
    )

    fun get() = allocationSettings

    fun setBandwidthAllocationSettings(message: ReceiverVideoConstraintsMessage): Boolean {
        var changed = false

        message.lastN?.let {
            if (lastN != it) {
                lastN = it
                changed = true
            }
        }
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
        message.assumedBandwidthBps?.let {
            config.assumedBandwidthLimit?.let { limit ->
                val limited = it.coerceAtMost(limit.bps.toLong())
                logger.warn("Setting assumed bandwidth ${limited.bps} (receiver asked for $it).")
                this.assumedBandwidthBps = limited
                changed = true
            } ?: run {
                if (it.bps >= 0.bps) {
                    logger.info("Ignoring assumed-bandwidth-bps, not allowed in config.")
                }
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
