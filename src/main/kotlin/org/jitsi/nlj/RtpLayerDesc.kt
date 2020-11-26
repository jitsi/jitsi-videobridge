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

import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.transform.node.incoming.BitrateCalculator
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.DataSize

/**
 * Keeps track of its subjective quality index,
 * its last stable bitrate and other useful things for adaptivity/routing.
 *
 * Note: this class and [getBitrate] are only open to allow to be overridden for testing. We found that mocking has
 * severe overhead and is not suitable for performance tests.
 *
 * @author George Politis
 */
open class RtpLayerDesc
constructor(
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
     * The max height of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.
     */
    // XXX we should be able to sniff the actual height from the RTP
    // packets.
    val height: Int,
    /**
     * The max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or
     * system load.
     */
    val frameRate: Double,
    /**
     * The [RtpLayerDesc] on which this layer depends.
     */
    private val dependencyLayers: Array<RtpLayerDesc>?
) {
    init {
        if (tid > 7) throw IllegalArgumentException("Invalid temporal ID $tid")
        if (sid > 7) throw IllegalArgumentException("Invalid spatial ID $sid")
    }

    /**
     * The [BitrateTracker] instance used to calculate the receiving bitrate of this RTP layer.
     */
    private var bitrateTracker = BitrateCalculator.createBitrateTracker()

    /**
     * @return the "id" of this layer within this encoding. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such as the
     * rid).
     */
    val layerId = getIndex(0, sid, tid)

    /**
     * A local index of this track.
     */
    val index = getIndex(eid, sid, tid)

    /**
     * {@inheritDoc}
     */
    override fun toString(): String {
        return "subjective_quality=" + index +
            ",temporal_id=" + tid +
            ",spatial_id=" + sid
    }

    /**
     * Inherit a [BitrateTracker] object
     */
    internal fun inheritStatistics(tracker: BitrateTracker) {
        bitrateTracker = tracker
    }

    /**
     * Inherit another layer description's [BitrateTracker] object.
     */
    internal fun inheritStatistics(other: RtpLayerDesc) {
        inheritStatistics(other.bitrateTracker)
    }

    /**
     *
     * @param packetSize
     * @param nowMs
     */
    fun updateBitrate(packetSize: DataSize, nowMs: Long) {
        // Update rate stats (this should run after padding termination).
        bitrateTracker.update(packetSize, nowMs)
    }

    /**
     * Gets the cumulative bitrate (in bps) of this [RtpLayerDesc] and
     * its dependencies.
     *
     * @param nowMs
     * @return the cumulative bitrate (in bps) of this [RtpLayerDesc]
     * and its dependencies.
     */
    open fun getBitrate(nowMs: Long): Bandwidth {
        var bitrate = bitrateTracker.getRate(nowMs)

        /* TODO: does the wrong thing if we have multiple dependencies */
        if (dependencyLayers != null) {
            for (dependency in dependencyLayers) {
                bitrate += dependency.getBitrate(nowMs)
            }
        }

        return bitrate
    }

    /**
     * Extracts a [NodeStatsBlock] from an [RtpLayerDesc].
     */
    fun getNodeStats() = NodeStatsBlock(layerId.toString()).apply {
        addNumber("frameRate", frameRate)
        addNumber("height", height)
        addNumber("index", index)
        addNumber("bitrate_bps", getBitrate(System.currentTimeMillis()).bps)
        addNumber("tid", tid)
        addNumber("sid", sid)
    }

    companion object {
        /**
         * The quality that is used to represent that forwarding is suspended.
         */
        const val SUSPENDED_INDEX = -1

        /**
         * A value used to designate the absence of height information.
         */
        private const val NO_HEIGHT = -1

        /**
         * A value used to designate the absence of frame rate information.
         */
        private const val NO_FRAME_RATE = -1.0

        /**
         * Calculate the "id" of a layer based on its encoding, spatial, and temporal ID.
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

        @JvmStatic
        fun getEidFromIndex(index: Int) = index shr 6

        @JvmStatic
        fun getSidFromIndex(index: Int) = (index and 0x38) shr 3

        @JvmStatic
        fun getTidFromIndex(index: Int) = index and 0x7
    }
}
