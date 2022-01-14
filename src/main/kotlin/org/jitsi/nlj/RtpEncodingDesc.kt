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

import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.stats.NodeStatsBlock

/**
 * Keeps track of information specific to an RTP encoded stream
 * (and its associated secondary sources).
 *
 * @author Jonathan Lennox
 */
class RtpEncodingDesc
@JvmOverloads
constructor(
    /**
     * The primary SSRC for this encoding.
     */
    val primarySSRC: Long,
    /**
     * The [RtpLayerDesc]s describing the encoding's layers.
     */
    initialLayers: Array<RtpLayerDesc>,
    /**
     * The ID of this encoding.
     */
    val eid: Int = requireNotNull(initialLayers.getOrNull(0)?.eid) {
        "initialLayers may not be empty if no explicit EID is provided"
    }
) {
    constructor(primarySSRC: Long, eid: Int) : this(primarySSRC, arrayOf(), eid)

    /**
     * The ssrcs associated with this encoding (for example, RTX or FLEXFEC)
     * Maps ssrc -> type [SsrcAssociationType] (rtx, etc.)
     */
    private val secondarySsrcs: MutableMap<Long, SsrcAssociationType> = HashMap()

    fun addSecondarySsrc(ssrc: Long, type: SsrcAssociationType) {
        secondarySsrcs[ssrc] = type
    }

    /**
     * All SSRCs (primary and secondary) associated with this encoding.
     */
    val ssrcs: Collection<Long>
        get() = HashSet<Long>().also { set ->
            set.add(primarySSRC)
            set.addAll(secondarySsrcs.keys)
        }

    private fun validateLayerEids(layers: Array<RtpLayerDesc>) {
        for (layer in layers) {
            require(layer.eid == eid) { "Cannot add layer with EID ${layer.eid} to encoding with EID $eid" }
        }
    }
    init { validateLayerEids(initialLayers) }

    internal var layers = initialLayers
        set(newLayers) {
            validateLayerEids(newLayers)
            /* Copy the rate statistics objects from the old layers to the new layers
             * with matching layer IDs.
             */
            /* Note: because layer arrays are sorted by ID we could avoid creating this
             * intermediate map object, and do this in a single pass in O(1) space.
             * The number of layers is small enough that this more complicated code
             * is probably unnecessary, though.
             */
            val oldLayerMap = field.associateBy { it.layerId }
            for (newLayer in newLayers) {
                oldLayerMap[newLayer.layerId]?.let {
                    newLayer.inheritFrom(it)
                }
            }
            field = newLayers
        }

    /**
     * @return the "id" of a layer within this source, across all encodings. This is a server-side id and should
     * not be confused with any encoding id defined in the client (such as the
     * rid). This server-side id is used in the layer lookup table that is
     * maintained in [MediaSourceDesc].
     */
    fun encodingId(layer: RtpLayerDesc): Long =
        calcEncodingId(primarySSRC, layer.layerId)

    /**
     * Get the secondary ssrc for this encoding that corresponds to the given
     * type
     * @param type the type of the secondary ssrc (e.g. RTX)
     * @return the ssrc for the encoding that corresponds to the given type,
     * if it exists; otherwise -1
     */
    fun getSecondarySsrc(type: SsrcAssociationType): Long {
        for ((key, value) in secondarySsrcs) {
            if (value == type) {
                return key
            }
        }
        return -1
    }

    /**
     * Clone an existing encoding desc, inheriting layer descs' statistics,
     * modifying only specific values.
     */
    fun copy(
        primarySSRC: Long = this.primarySSRC,
        layers: Array<RtpLayerDesc> = Array(this.layers.size) { i -> this.layers[i].copy() }
    ) = RtpEncodingDesc(primarySSRC, layers, eid).also {
        this.secondarySsrcs.forEach { (ssrc, type) -> it.addSecondarySsrc(ssrc, type) }
    }

    /**
     * {@inheritDoc}
     */
    override fun toString(): String {
        return "primary_ssrc=$primarySSRC,secondary_ssrcs=$secondarySsrcs," +
            "layers=\n    ${layers.joinToString(separator = "\n    ")}"
    }

    /**
     * Gets a boolean indicating whether or not the SSRC specified in the
     * arguments matches this encoding or not.
     *
     * @param ssrc the SSRC to match.
     */
    fun matches(ssrc: Long): Boolean {
        return if (primarySSRC == ssrc) {
            true
        } else secondarySsrcs.containsKey(ssrc)
    }

    /**
     * Extracts a [NodeStatsBlock] from an [RtpEncodingDesc].
     */
    fun getNodeStats() = NodeStatsBlock(primarySSRC.toString()).apply {
        addNumber("rtx_ssrc", getSecondarySsrc(SsrcAssociationType.RTX))
        addNumber("fec_ssrc", getSecondarySsrc(SsrcAssociationType.FEC))
        for (layer in layers) {
            addBlock(layer.getNodeStats())
        }
    }

    companion object {
        fun calcEncodingId(ssrc: Long, layerId: Int) =
            ssrc or (layerId.toLong() shl 32)
    }
}

fun VideoRtpPacket.getEncodingId(): Long {
    return RtpEncodingDesc.calcEncodingId(ssrc, this.layerId)
}
