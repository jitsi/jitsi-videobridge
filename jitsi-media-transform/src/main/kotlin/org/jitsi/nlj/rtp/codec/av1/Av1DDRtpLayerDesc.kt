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
package org.jitsi.nlj.rtp.codec.av1

import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.stats.NodeStatsBlock

/**
 * An RtpLayerDesc of the type needed to describe AV1 DD scalability.
 */

class Av1DDRtpLayerDesc(
    /**
     * The index of this instance's encoding in the source encoding array.
     */
    eid: Int,
    /**
     * The decoding target of this instance, or negative for unknown.
     */
    val dt: Int,
    /**
     * The max height of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.
     */
    // XXX we should be able to sniff the actual height from the RTP
    // packets.
    height: Int,
    /**
     * The max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or
     * system load.
     */
    frameRate: Double,
) : RtpLayerDesc(eid, height, frameRate) {
    override fun copy(height: Int): RtpLayerDesc = Av1DDRtpLayerDesc(eid, dt, height, frameRate)

    override val layerId = dt
    override val index = getIndex(eid, 0, dt)

    override fun getBitrate(nowMs: Long) = bitrateTracker.getRate(nowMs)

    override fun hasZeroBitrate(nowMs: Long) = bitrateTracker.getAccumulatedSize(nowMs).bits == 0L

    /**
     * Extracts a [NodeStatsBlock] from an [RtpLayerDesc].
     */
    override fun getNodeStats() = super.getNodeStats().apply {
        addNumber("dt", dt)
    }

    override fun indexString(): String =
        if (index == SUSPENDED_INDEX) "SUSP"
        else "E${eid}DT$dt"
}
