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

package org.jitsi.nlj.rtp.codec.vpx

import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.stats.NodeStatsBlock
import org.jitsi.nlj.util.Bandwidth
import org.jitsi.nlj.util.BitrateTracker
import org.jitsi.nlj.util.sum

/**
 * An RtpLayerDesc of the type needed to describe VP8 and VP9 scalability.
 */
class VpxRtpLayerDesc
@JvmOverloads
constructor(
    /**
     * The index of this instance's encoding in the source encoding array.
     */
    eid: Int,
    /**
     * The temporal layer ID of this instance, or negative for unknown.
     */
    tid: Int,
    /**
     * The spatial layer ID of this instance, or negative for unknown.
     */
    sid: Int,
    /**
     * The max height of the bitstream that this instance represents. The actual
     * height may be less due to bad network or system load.  [RtpLayerDesc.NO_HEIGHT] for unknown.
     *
     * XXX we should be able to sniff the actual height from the RTP
     * packets.
     */
    height: Int,
    /**
     * The max frame rate (in fps) of the bitstream that this instance
     * represents. The actual frame rate may be less due to bad network or
     * system load.  [RtpLayerDesc.NO_FRAME_RATE] for unknown.
     */
    frameRate: Double,
    /**
     * The [RtpLayerDesc]s on which this layer definitely depends.
     */
    val dependencyLayers: Array<VpxRtpLayerDesc> = emptyArray(),
    /**
     * The [RtpLayerDesc]s on which this layer possibly depends.
     * (The intended use case is K-SVC mode.)
     */
    val softDependencyLayers: Array<VpxRtpLayerDesc> = emptyArray()
) : RtpLayerDesc(eid, tid, sid, height, frameRate) {
    init {
        require(tid < 8) { "Invalid temporal ID $tid" }
        require(sid < 8) { "Invalid spatial ID $sid" }
    }

    /**
     * Clone an existing layer desc, inheriting its statistics if [inherit],
     * modifying only specific values.
     */
    override fun copy(height: Int, tid: Int, inherit: Boolean) = VpxRtpLayerDesc(
        eid = this.eid,
        tid = tid,
        sid = this.sid,
        height = height,
        frameRate = this.frameRate,
        dependencyLayers = this.dependencyLayers,
        softDependencyLayers = this.softDependencyLayers
    ).also {
        if (inherit) {
            it.inheritFrom(this)
        }
    }

    /**
     * Whether softDependencyLayers are to be used.
     */
    var useSoftDependencies = true

    /**
     * @return the "id" of this layer within this encoding. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such as the
     * rid).
     */
    override val layerId = getIndex(0, sid, tid)

    /**
     * A local index of this track.
     */
    override val index = getIndex(eid, sid, tid)

    /**
     * Inherit another layer description's [BitrateTracker] object.
     */
    override fun inheritFrom(other: RtpLayerDesc) {
        super.inheritFrom(other)
        if (other is VpxRtpLayerDesc) {
            useSoftDependencies = other.useSoftDependencies
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String {
        return "subjective_quality=$index,temporal_id=$tid,spatial_id=$sid,height=$height,frameRate=$frameRate"
    }

    /**
     * Gets the cumulative bitrate (in bps) of this [RtpLayerDesc] and its dependencies.
     *
     * This is left open for use in testing.
     *
     * @param nowMs
     * @return the cumulative bitrate (in bps) of this [RtpLayerDesc] and its dependencies.
     */
    override fun getBitrate(nowMs: Long): Bandwidth = calcBitrate(nowMs).values.sum()

    /**
     * Recursively adds the bitrate (in bps) of this [RtpLayerDesc] and
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
     *
     * This is left open for use in testing.
     */
    override fun hasZeroBitrate(nowMs: Long): Boolean {
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
    override fun debugState() = super.debugState().apply {
        this["tid"] = tid
        this["sid"] = sid
    }

    override fun indexString(): String = indexString(index)
}
