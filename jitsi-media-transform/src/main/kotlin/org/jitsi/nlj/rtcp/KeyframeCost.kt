/*
 * Copyright @ 2018 - present 8x8, Inc.
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

package org.jitsi.nlj.rtcp

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.DataSize
import org.jitsi.nlj.util.atRate
import org.jitsi.nlj.util.bps
import java.time.Duration

/**
 * The measured cost of requesting a keyframe from a media source: the mean size of one keyframe, summed over the
 * encodings the source is currently sending, the total bitrate of those encodings, and the rate at which keyframe
 * bytes have actually been arriving from them recently.
 */
data class KeyframeCost(
    val keyframeSize: DataSize,
    val sourceBitrate: Bandwidth,
    val keyframeBitrate: Bandwidth = 0.bps
) {
    /** The share of the source's bitrate currently going to keyframes, as measured. */
    val keyframeFraction: Double
        get() = if (sourceBitrate.bps > 0) keyframeBitrate.bps.toDouble() / sourceBitrate.bps else 0.0

    /** The share of the source's bitrate keyframes would take if requested once every [interval]. */
    fun keyframeFractionAt(interval: Duration): Double = if (sourceBitrate.bps > 0 && !interval.isZero) {
        keyframeSize.bits / (interval.toNanos() / 1e9) / sourceBitrate.bps
    } else {
        0.0
    }

    /**
     * The interval at which keyframes would cost [fraction] of the source's bitrate, or null if it can not be
     * computed.
     */
    fun intervalAt(fraction: Double): Duration? {
        val budget = sourceBitrate * fraction
        return if (budget.bps > 0 && keyframeSize.bits > 0) keyframeSize atRate budget else null
    }

    fun toJson(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        put("keyframe_bits", keyframeSize.bits)
        put("source_bitrate_bps", sourceBitrate.bps)
        put("keyframe_bitrate_bps", keyframeBitrate.bps)
        put("keyframe_fraction", keyframeFraction)
    }
}
