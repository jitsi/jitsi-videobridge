/*
 * Copyright @ 2023 - present 8x8, Inc.
 * Copyright @ 2023 - Vowel, Inc.
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

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.rtp.codec.VideoCodecParser
import org.jitsi.nlj.rtp.codec.av1.dd.FrameDependencyStructure
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

class Av1Parser(
    sources: Array<MediaSourceDesc>,
    parentLogger: Logger
) : VideoCodecParser(sources) {
    private val logger = createChildLogger(parentLogger)

    /** Encodings we've actually seen.  Used to clear out inferred-from-signaling encoding information. */
    private val ssrcsSeen = HashSet<Long>()
    private var numSpatialLayers = -1

    override fun parse(packetInfo: PacketInfo) {
        val av1packet = packetInfo.packetAs<Av1packet>()

        ssrcsSeen.add(av1packet.ssrc)

        val structure = av1packet.structure ?: return

        val packetSpatialLayers = structure.resolutions.size
        if (packetSpatialLayers > 0) {
            if (numSpatialLayers != -1 && numSpatialLayers != packetSpatialLayers) {
                packetInfo.layeringChanged = true
            }
            numSpatialLayers = packetSpatialLayers
        }

        findSourceDescAndRtpEncodingDesc(av1packet)?.let { (src, enc) ->
            val desc = getScalabilityStructure(
                eid = enc.eid,
                structure = structure,
                ssrc = av1packet.ssrc
            )
            src.setEncodingLayers(desc.layers, av1packet.ssrc)

            for (otherEnc in src.rtpEncodings) {
                if (!ssrcsSeen.contains(otherEnc.primarySSRC)) {
                    src.setEncodingLayers(emptyArray(), otherEnc.primarySSRC)
                }
            }
        }
    }

    fun getScalabilityStructure(eid: Int, structure: FrameDependencyStructure, ssrc: Long): RtpEncodingDesc {
        val spatialIds = structure.templates.map { it.spatialId }.distinct().sorted()
        val temporalIds = structure.templates.map { it.temporalId }.distinct().sorted()
        val layers = ArrayList<RtpLayerDesc>()

        for (s in spatialIds) {
            for (t in temporalIds) {
                val dependencies = ArrayList<RtpLayerDesc>()
                val softDependencies = ArrayList<RtpLayerDesc>()
                if (s > 0) {
                    /* Because of K-SVC, spatial layer dependencies are soft */
                    layers.find { it.sid == s - 1 && it.tid == t }?.let { softDependencies.add(it) }
                }
                if (t > 0) {
                    layers.find { it.sid == s && it.tid == t - 1 }?.let { dependencies.add(it) }
                }
                val layerDesc = RtpLayerDesc(
                    eid = eid,
                    tid = t,
                    sid = s,
                    height = structure.resolutions[s].height,
                    frameRate = RtpLayerDesc.NO_FRAME_RATE,
                    dependencyLayers = dependencies.toArray(arrayOf<RtpLayerDesc>()),
                    softDependencyLayers = softDependencies.toArray(arrayOf<RtpLayerDesc>())
                )
                layers.add(layerDesc)
            }
        }

        return RtpEncodingDesc(ssrc, layers.toArray(arrayOf()), eid)
    }
}
