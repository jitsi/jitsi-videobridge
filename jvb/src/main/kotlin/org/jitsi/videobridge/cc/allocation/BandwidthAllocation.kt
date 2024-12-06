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
import org.json.simple.JSONObject

/**
 * The result of bandwidth allocation.
 */
class BandwidthAllocation @JvmOverloads constructor(
    val allocations: Set<SingleAllocation>,
    val oversending: Boolean = false,
    val idealBps: Long = -1,
    val targetBps: Long = -1,
    /** Whether any of the requested sources were suspended (no layer at all was selected) due to BWE. */
    private val suspendedSources: List<String> = emptyList()
) {
    val hasSuspendedSources: Boolean = suspendedSources.isNotEmpty()

    val forwardedSources: Set<String> =
        allocations.filter { it.isForwarded() }.mapNotNull { it.mediaSource?.sourceName }.toSet()

    /**
     * Whether the two allocations have the same endpoints and same layers.
     */
    fun isTheSameAs(other: BandwidthAllocation) = allocations.size == other.allocations.size &&
        oversending == other.oversending &&
        allocations.all { allocation ->
            other.allocations.any { otherAllocation ->
                allocation.endpointId == otherAllocation.endpointId &&
                    allocation.mediaSource?.primarySSRC == otherAllocation.mediaSource?.primarySSRC &&
                    allocation.mediaSource?.videoType == otherAllocation.mediaSource?.videoType &&
                    allocation.targetLayer?.index == otherAllocation.targetLayer?.index
            }
        }

    override fun toString(): String = "oversending=$oversending " + allocations.joinToString()

    val debugState: JSONObject
        get() = JSONObject().apply {
            put("idealBps", idealBps)
            put("targetBps", targetBps)
            put("oversending", oversending)
            put("has_suspended_sources", hasSuspendedSources)
            put("suspended_sources", suspendedSources)
            val allocations = JSONObject().apply {
                allocations.forEach {
                    val name = it.mediaSource?.sourceName ?: it.endpointId
                    put(name, it.debugState)
                }
            }
            put("allocations", allocations)
        }
}

/**
 * The result of bandwidth allocation for a specific endpoint and a [MediaSourceDesc].
 */
data class SingleAllocation(
    /** The ID of the endpoint which owns the media source */
    val endpointId: String,
    /** The media source */
    val mediaSource: MediaSourceDesc?,
    /**
     * The layer which has been selected to be forwarded.
     */
    val targetLayer: RtpLayerDesc? = null,
    /**
     * The layer which would have been selected without bandwidth constraints.
     */
    val idealLayer: RtpLayerDesc? = null
) {
    constructor(endpoint: MediaSourceContainer, targetLayer: RtpLayerDesc? = null, idealLayer: RtpLayerDesc? = null) :
        this(
            endpoint.id,
            endpoint.mediaSources.firstOrNull(),
            targetLayer,
            idealLayer
        )
    private val targetIndex: Int
        get() = targetLayer?.index ?: -1
    fun isForwarded(): Boolean = targetIndex > -1

    override fun toString(): String = "[epId=$endpointId sourceName=${mediaSource?.sourceName} " +
        "target=${targetLayer?.height}/${targetLayer?.frameRate} " +
        "(${targetLayer?.indexString()}) " +
        "ideal=${idealLayer?.height}/${idealLayer?.frameRate} (${idealLayer?.indexString()})]"

    val debugState: JSONObject
        get() = JSONObject().apply {
            put("target", targetLayer?.debugState())
            put("ideal", idealLayer?.debugState())
        }
}
