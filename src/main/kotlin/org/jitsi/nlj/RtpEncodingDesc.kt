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
import org.jitsi.nlj.rtp.codec.vp8.Vp8Packet
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
    initialLayers: Array<RtpLayerDesc> = arrayOf()
) {
    /**
     * The ssrcs associated with this encoding (for example, RTX or FLEXFEC)
     * Maps ssrc -> type [SsrcAssociationType] (rtx, etc.)
     */
    private val secondarySsrcs: MutableMap<Long, SsrcAssociationType> = HashMap()

    fun addSecondarySsrc(ssrc: Long, type: SsrcAssociationType) {
        secondarySsrcs[ssrc] = type
    }

    internal var layers = initialLayers
        set(newLayers) {
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
                    newLayer.inheritStatistics(it)
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
     * {@inheritDoc}
     */
    override fun toString(): String {
        return "primary_ssrc=$primarySSRC,secondary_ssrcs=$secondarySsrcs," +
            "layers=${layers.joinToString(separator = "\n    ")}"
    }

    fun findRtpLayerDesc(packet: VideoRtpPacket): RtpLayerDesc? =
        layers.find { it.matches(packet) }

    fun matches(packet: VideoRtpPacket): Boolean {
        if (!matches(packet.ssrc)) {
            return false
        } else if (layers.isEmpty()) {
            return true // ???
        } else for (layer in layers) {
            if (layer.matches(packet)) {
                return true
            }
        }
        return false
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
    val layerId =
        if (this is Vp8Packet) {
            // note(george) we've observed that a client may announce but not
            // send simulcast (it is not clear atm who's to blame for this
            // "bug", chrome or our client code). In any case, when this happens
            // we "pretend" that the encoding of the packet is the base temporal
            // layer of the encoding.
            val tid = temporalLayerIndex
            if (tid >= 0) {
                tid
            } else {
                0
            }
        } else {
            0
        }
    return RtpEncodingDesc.calcEncodingId(ssrc, layerId)
}
