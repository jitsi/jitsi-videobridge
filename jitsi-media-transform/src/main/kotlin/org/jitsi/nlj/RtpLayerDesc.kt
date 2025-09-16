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
package org.jitsi.nlj

import org.jitsi.nlj.transform.node.incoming.BitrateCalculator
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.DataSize
import org.jitsi.utils.OrderedJsonObject

/**
 * Keeps track of its subjective quality index,
 * its last stable bitrate and other useful things for adaptivity/routing.
 *
 * @author George Politis
 */
abstract class RtpLayerDesc(
    /**
     * The index of this instance's encoding in the source encoding array.
     */
    val eid: Int,
    /**
     * The temporal layer ID of this instance.
     */
    val tid: Int,
    /**
     * The spatial layer ID of this instance.
     */
    val sid: Int,
    /**
     * The max "height" of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.  [NO_HEIGHT] for unknown.
     *
     * In order to handle portrait-mode video correctly, this should actually be the smaller of
     * the bitstream's height or width.
     *
     * Where possible we sniff the actual height from the RTP packets.
     */
    var height: Int,
    /**
     * The max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or
     * system load.  [NO_FRAME_RATE] for unknown.
     */
    var frameRate: Double,
) {
    abstract fun copy(height: Int = this.height, tid: Int = this.tid, inherit: Boolean = true): RtpLayerDesc

    /**
     * The [BitrateTracker] instance used to calculate the receiving bitrate of this RTP layer.
     */
    protected var bitrateTracker = BitrateCalculator.createBitrateTracker()

    var targetBitrate: Bandwidth? = null

    /**
     * @return the "id" of this layer within this encoding. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such as the
     * rid).
     */
    abstract val layerId: Int

    /**
     * A local index of this track.
     */
    abstract val index: Int

    /**
     * Inherit a [BitrateTracker] object
     */
    internal fun inheritStatistics(tracker: BitrateTracker) {
        bitrateTracker = tracker
    }

    /**
     * Inherit another layer description's [BitrateTracker] object.
     */
    internal open fun inheritFrom(other: RtpLayerDesc) {
        inheritStatistics(other.bitrateTracker)
        targetBitrate = other.targetBitrate
    }

    /**
     *
     * @param packetSize
     * @param nowMs
     * @return true if this caused the rate being tracked to transition from zero to nonzero
     */
    fun updateBitrate(packetSize: DataSize, nowMs: Long): Boolean {
        val wasInactive = hasZeroBitrate(nowMs)
        // Update rate stats (this should run after padding termination).
        bitrateTracker.update(packetSize, nowMs)
        return wasInactive && packetSize.bits > 0L
    }

    /**
     * Gets the cumulative bitrate (in bps) of this [RtpLayerDesc] and its dependencies.
     *
     * @param nowMs
     * @return the cumulative bitrate (in bps) of this [RtpLayerDesc] and its dependencies.
     */
    abstract fun getBitrate(nowMs: Long): Bandwidth

    /**
     * Recursively checks this layer and its dependencies to see if the bitrate is zero.
     * Note that unlike [calcBitrate] this does not avoid double-visiting layers; the overhead
     * of the hash table is usually more than the cost of any double-visits.
     */
    abstract fun hasZeroBitrate(nowMs: Long): Boolean

    open fun debugState(): OrderedJsonObject = OrderedJsonObject().apply {
        this["frameRate"] = frameRate
        this["height"] = height
        this["index"] = index
        this["bitrate_bps"] = getBitrate(System.currentTimeMillis()).bps
        this["target_bitrate"] = targetBitrate?.bps ?: 0
        this["indexString"] = indexString()
    }

    abstract fun indexString(): String

    companion object {
        /**
         * The index value that is used to represent that forwarding is suspended.
         */
        const val SUSPENDED_INDEX = -1

        /**
         * The encoding ID value that is used to represent that forwarding is suspended.
         */
        const val SUSPENDED_ENCODING_ID = -1

        /**
         * A value used to designate the absence of height information.
         */
        const val NO_HEIGHT = -1

        /**
         * A value used to designate the absence of frame rate information.
         */
        const val NO_FRAME_RATE = -1.0

        /**
         * Calculate the "index" of a layer based on its encoding, spatial, and temporal ID.
         * This is a server-side id and should not be confused with any encoding id defined
         * in the client (such as the rid) or the encodingId.  This is used by the videobridge's
         * adaptive source projection for filtering.
         */
        @JvmStatic
        fun getIndex(eid: Int, sid: Int, tid: Int): Int {
            val e = if (eid < 0) 0 else eid
            val s = if (sid < 0) 0 else sid
            val t = if (tid < 0) 0 else tid

            return (e shl 6) or (s shl 3) or t
        }

        /**
         * Get an encoding ID from a layer index.  If the index is [SUSPENDED_INDEX],
         * [SUSPENDED_ENCODING_ID] will be returned.
         */
        @JvmStatic
        fun getEidFromIndex(index: Int) = index shr 6

        /**
         * Get a spatial ID from a layer index.  If the index is [SUSPENDED_INDEX],
         * the value is unspecified.
         */
        @JvmStatic
        fun getSidFromIndex(index: Int) = (index and 0x38) shr 3

        /**
         * Get a temporal ID from a layer index.  If the index is [SUSPENDED_INDEX],
         * the value is unspecified.
         */
        @JvmStatic
        fun getTidFromIndex(index: Int) = index and 0x7

        /**
         * Get a string description of a layer index.
         */
        fun indexString(index: Int): String = if (index == SUSPENDED_INDEX) {
            "SUSP"
        } else {
            "E${getEidFromIndex(index)}S${getSidFromIndex(index)}T${getTidFromIndex(index)}"
        }
    }
}
