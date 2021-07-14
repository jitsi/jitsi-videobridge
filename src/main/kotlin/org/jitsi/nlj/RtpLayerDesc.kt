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
import org.jitsi.nlj.util.sum

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
@JvmOverloads
constructor(
    /**
     * The index of this instance's encoding in the source encoding array.
     */
    val eid: Int,
    /**
     * The temporal layer ID of this instance, or negative for unknown.
     */
    val tid: Int,
    /**
     * The spatial layer ID of this instance, or negative for unknown.
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
     * The [RtpLayerDesc]s on which this layer definitely depends.
     */
    private val dependencyLayers: Array<RtpLayerDesc> = emptyArray(),
    /**
     * The [RtpLayerDesc]s on which this layer possibly depends.
     * (The intended use case is K-SVC mode.)
     */
    private val softDependencyLayers: Array<RtpLayerDesc> = emptyArray()
) {
    init {
        require(tid < 8) { "Invalid temporal ID $tid" }
        require(sid < 8) { "Invalid spatial ID $sid" }
    }

    /**
     * Clone an existing layer desc, inheriting its statistics,
     * modifying only specific values.
     */
    fun copy(
        eid: Int = this.eid,
        tid: Int = this.tid,
        sid: Int = this.sid,
        height: Int = this.height,
        frameRate: Double = this.frameRate,
        dependencyLayers: Array<RtpLayerDesc> = this.dependencyLayers,
        softDependencyLayers: Array<RtpLayerDesc> = this.softDependencyLayers
    ) = RtpLayerDesc(eid, tid, sid, height, frameRate, dependencyLayers, softDependencyLayers).also {
        it.inheritFrom(this)
    }

    /**
     * Whether softDependencyLayers are to be used.
     */
    var useSoftDependencies = true

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
    internal fun inheritFrom(other: RtpLayerDesc) {
        inheritStatistics(other.bitrateTracker)
        useSoftDependencies = other.useSoftDependencies
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
     * Gets the cumulative bitrate (in bps) of this [RtpLayerDesc] and
     * its dependencies.
     *
     * @param nowMs
     * @return the cumulative bitrate (in bps) of this [RtpLayerDesc]
     * and its dependencies.
     */
    open fun getBitrate(nowMs: Long): Bandwidth = calcBitrate(nowMs).values.sum()

    /**
     * Expose [getBitrate] as a [Double] in order to make it accessible from java (since [Bandwidth] is an inline
     * class).
     */
    fun getBitrateBps(nowMs: Long): Double = getBitrate(nowMs).bps

    /**
     * Recursively adds the bitrate (in bps) of this [RTPLayerDesc] and
     * its dependencies in the map passed in as an argument.
     *
     * This is necessary to ensure we don't double-count layers in cases
     * of multiple dependencies.
     *
     * @param nowMs
     */
    private fun calcBitrate(nowMs: Long, rates: MutableMap<Int, Bandwidth> = HashMap()): MutableMap<Int, Bandwidth> {
        if (rates.containsKey(index)) {
            return rates
        }
        rates[index] = bitrateTracker.getRate(nowMs)

        dependencyLayers.forEach { it.calcBitrate(nowMs, rates) }

        if (useSoftDependencies) {
            softDependencyLayers.forEach { it.calcBitrate(nowMs, rates) }
        }

        return rates
    }

    /**
     * Returns true if this layer, alone, has a zero bitrate.
     */
    private fun layerHasZeroBitrate(nowMs: Long) = bitrateTracker.getAccumulatedSize(nowMs).bits == 0L

    /**
     * Recursively checks this layer and its dependencies to see if the bitrate is zero.
     * Note that unlike [calcBitrate] this does not avoid double-visiting layers; the overhead
     * of the hash table is usually more than the cost of any double-visits.
     */
    fun hasZeroBitrate(nowMs: Long): Boolean {
        if (!layerHasZeroBitrate(nowMs)) {
            return false
        }
        if (dependencyLayers.any { !it.layerHasZeroBitrate(nowMs) }) {
            return false
        }
        if (useSoftDependencies && softDependencyLayers.any { !it.layerHasZeroBitrate(nowMs) }) {
            return false
        }
        return true
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
         * Get an spatial ID from a layer index.  If the index is [SUSPENDED_INDEX],
         * the value is unspecified.
         */
        @JvmStatic
        fun getSidFromIndex(index: Int) = (index and 0x38) shr 3

        /**
         * Get an temporal ID from a layer index.  If the index is [SUSPENDED_INDEX],
         * the value is unspecified.
         */
        @JvmStatic
        fun getTidFromIndex(index: Int) = index and 0x7

        /**
         * Get a string description of a layer index.
         */
        @JvmStatic
        fun indexString(index: Int): String =
            if (index == SUSPENDED_INDEX) "SUSP"
            else "E${getEidFromIndex(index)}S${getSidFromIndex(index)}T${getTidFromIndex(index)}"
    }
}
