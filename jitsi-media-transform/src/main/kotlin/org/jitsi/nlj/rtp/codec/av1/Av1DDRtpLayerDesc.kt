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
     * The temporal layer ID of this instance,.
     */
    tid: Int,
    /**
     * The spatial layer ID of this instance.
     */
    sid: Int,
    /**
     * The max height of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.
     */

    height: Int,
    /**
     * The max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or
     * system load.
     */
    frameRate: Double,
) : RtpLayerDesc(eid, tid, sid, height, frameRate) {
    override fun copy(height: Int, tid: Int, inherit: Boolean): RtpLayerDesc =
        Av1DDRtpLayerDesc(eid, dt, tid, sid, height, frameRate).also {
            if (inherit) {
                it.inheritFrom(this)
            }
        }

    override val layerId = dt
    override val index = getIndex(eid, dt)

    override fun getBitrate(nowMs: Long) = bitrateTracker.getRate(nowMs)

    override fun hasZeroBitrate(nowMs: Long) = bitrateTracker.getAccumulatedSize(nowMs).bits == 0L

    /**
     * Extracts a [NodeStatsBlock] from an [RtpLayerDesc].
     */
    override fun debugState() = super.debugState().apply {
        this["dt"] = dt
    }

    override fun indexString(): String = indexString(index)

    /**
     * {@inheritDoc}
     */
    override fun toString(): String {
        return "subjective_quality=$index,DT=$dt,height=$height,frameRate=$frameRate"
    }

    companion object {
        /**
         * The index value that is used to represent that forwarding is suspended.
         */
        const val SUSPENDED_INDEX = -1

        const val SUSPENDED_DT = -1

        /**
         * Calculate the "index" of a layer based on its encoding and decode target.
         * This is a server-side id and should not be confused with any encoding id defined
         * in the client (such as the rid) or the encodingId.  This is used by the videobridge's
         * adaptive source projection for filtering.
         */
        @JvmStatic
        fun getIndex(eid: Int, dt: Int): Int {
            val e = if (eid < 0) 0 else eid
            val d = if (dt < 0) 0 else dt

            return (e shl 6) or d
        }

        /**
         * Get a decode target ID from a layer index.  If the index is [SUSPENDED_INDEX],
         * the value is unspecified.
         */
        @JvmStatic
        fun getDtFromIndex(index: Int) = if (index == SUSPENDED_INDEX) SUSPENDED_DT else index and 0x3f

        /**
         * Get a string description of a layer index.
         */
        fun indexString(index: Int): String = if (index == SUSPENDED_INDEX) {
            "SUSP"
        } else {
            "E${getEidFromIndex(index)}DT${getDtFromIndex(index)}"
        }
    }
}
