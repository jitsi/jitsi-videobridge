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

package org.jitsi.nlj.rtp.codec.vp9

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.rtp.codec.VideoCodecParser
import org.jitsi.nlj.rtp.codec.vpx.VpxRtpLayerDesc
import org.jitsi.nlj.util.StateChangeLogger
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import kotlin.math.max

/**
 * Some [Vp9Packet] fields are not able to be determined by looking at a single VP9 packet (for example the scalability
 * structure is only carried in keyframes).  This class updates the layer descriptions with information
 * from frames, and also diagnoses packet format variants that the Jitsi videobridge won't be able to route.
 */
class Vp9Parser(
    source: MediaSourceDesc,
    parentLogger: Logger
) : VideoCodecParser(source) {
    private val logger = createChildLogger(parentLogger)

    private val pictureIdState = StateChangeLogger("missing picture id", logger)
    private val extendedPictureIdState = StateChangeLogger("missing extended picture ID", logger)
    private var numSpatialLayers = -1

    /** Encodings we've actually seen, and the layers seen for each one.
     * Used to clear out inferred-from-signaling encoding information, and to synthesize temporal layers
     * for flexible-mode encodings. */
    private val ssrcsInfo = HashMap<Long, HashMap<Int, Int>>()

    override fun parse(packetInfo: PacketInfo) {
        val vp9Packet = packetInfo.packetAs<Vp9Packet>()

        val layerMap = ssrcsInfo.getOrPut(vp9Packet.ssrc) {
            HashMap()
        }

        layerMap[vp9Packet.spatialLayerIndex]?.let {
            layerMap[vp9Packet.spatialLayerIndex] = max(it, vp9Packet.temporalLayerIndex)
        } ?: run {
            layerMap[vp9Packet.spatialLayerIndex] = vp9Packet.temporalLayerIndex
        }

        if (vp9Packet.hasScalabilityStructure) {
            // TODO: handle case where new SS is from a packet older than the
            //  latest SS we've seen.
            val packetSpatialLayers = vp9Packet.scalabilityStructureNumSpatial
            if (packetSpatialLayers != -1) {
                if (numSpatialLayers != -1 && numSpatialLayers != packetSpatialLayers) {
                    packetInfo.layeringChanged = true
                }
                numSpatialLayers = packetSpatialLayers
            }
            val ss = findRtpEncodingDesc(vp9Packet)?.let { enc ->
                vp9Packet.getScalabilityStructure(eid = enc.eid)
            }

            if (ss != null) {
                val layers =
                    if (vp9Packet.isFlexibleMode) {
                        /* In flexible mode, the number of temporal layers isn't announced in the keyframe.
                         * Thus, add temporal layer information to the source's encoding layers based on the temporal
                         * layers we've seen previously.
                         */
                        val layersList = ss.layers.toMutableList()

                        for ((sid, maxTid) in layerMap) {
                            addTemporalLayers(layersList, sid, maxTid)
                        }
                        layersList.toTypedArray()
                    } else {
                        ss.layers
                    }

                source.setEncodingLayers(layers, vp9Packet.ssrc)

                for (otherEnc in source.rtpEncodings) {
                    if (!ssrcsInfo.contains(otherEnc.primarySSRC)) {
                        source.setEncodingLayers(emptyArray(), otherEnc.primarySSRC)
                    }
                }
            }
        }
        if (vp9Packet.spatialLayerIndex > 0 && vp9Packet.isInterPicturePredicted) {
            /* Check if this layer is using K-SVC. */
            /* Note: In K-SVC mode, this entirely ignores the bitrate of lower-layer keyframes
             * when calculating layers' bitrates.  These values are small enough this is probably
             * fine, but revisit this if it turns out to be a problem.
             */
            findRtpLayerDescs(vp9Packet).forEach {
                if (it is VpxRtpLayerDesc) {
                    it.useSoftDependencies = vp9Packet.usesInterLayerDependency
                }
            }
        }

        if (vp9Packet.isFlexibleMode && findRtpLayerDescs(vp9Packet).isEmpty()) {
            val layers = source.getEncodingLayers(vp9Packet.ssrc).toMutableList()
            /* In flexible mode, the number of temporal layers isn't announced in the keyframe.
             * Thus, add temporal layer information to the source's encoding layers as we see packets with
             * temporal layers.
             */
            val changed = addTemporalLayers(layers, vp9Packet.spatialLayerIndex, vp9Packet.temporalLayerIndex)
            if (changed) {
                source.setEncodingLayers(layers.toTypedArray(), vp9Packet.ssrc)
                packetInfo.layeringChanged = true
            }
        }

        pictureIdState.setState(vp9Packet.hasPictureId, vp9Packet) {
            "Packet Data: ${vp9Packet.toHex(80)}"
        }
        extendedPictureIdState.setState(vp9Packet.hasExtendedPictureId, vp9Packet) {
            "Packet Data: ${vp9Packet.toHex(80)}"
        }
    }

    /** Add temporal layers to the list of layers.  Needed if VP9 is encoded in flexible mode, because
     * in flexible mode the scalability structure doesn't describe the temporal layers.
     */
    private fun addTemporalLayers(layers: MutableList<RtpLayerDesc>, sid: Int, maxTid: Int): Boolean {
        var changed = false

        for (tid in 1..maxTid) {
            val layer = layers.find { it.sid == sid && it.tid == tid }
            if (layer == null) {
                val prevLayer = layers.find { it.sid == sid && it.tid == tid - 1 }
                if (prevLayer != null) {
                    val newLayer = prevLayer.copy(tid = tid, inherit = false)
                    layers.add(newLayer)
                    changed = true
                }
            }
        }
        return changed
    }
}
