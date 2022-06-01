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
import org.jitsi.nlj.findRtpLayerDesc
import org.jitsi.nlj.rtp.codec.VideoCodecParser
import org.jitsi.nlj.util.StateChangeLogger
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Some [Vp9Packet] fields are not able to be determined by looking at a single VP9 packet (for example the scalability
 * structure is only carried in keyframes).  This class updates the layer descriptions with information
 * from frames, and also diagnoses packet format variants that the Jitsi videobridge won't be able to route.
 */
class Vp9Parser(
    sources: Array<MediaSourceDesc>,
    parentLogger: Logger
) : VideoCodecParser(sources) {
    private val logger = createChildLogger(parentLogger)

    private val pictureIdState = StateChangeLogger("missing picture id", logger)
    private val extendedPictureIdState = StateChangeLogger("missing extended picture ID", logger)
    private var numSpatialLayers = -1

    /** Encodings we've actually seen.  Used to clear out inferred-from-signaling encoding information. */
    private val ssrcsSeen = HashSet<Long>()

    override fun parse(packetInfo: PacketInfo) {
        val vp9Packet = packetInfo.packetAs<Vp9Packet>()

        ssrcsSeen.add(vp9Packet.ssrc)

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
            findSourceDescAndRtpEncodingDesc(vp9Packet)?.let { (src, enc) ->
                vp9Packet.getScalabilityStructure(eid = enc.eid)?.let {
                    src.setEncodingLayers(it.layers, vp9Packet.ssrc)
                }
                for (otherEnc in src.rtpEncodings) {
                    if (!ssrcsSeen.contains(otherEnc.primarySSRC)) {
                        src.setEncodingLayers(emptyArray(), otherEnc.primarySSRC)
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
            findRtpLayerDesc(vp9Packet)?.useSoftDependencies = vp9Packet.usesInterLayerDependency
        }

        pictureIdState.setState(vp9Packet.hasPictureId, vp9Packet) {
            "Packet Data: ${vp9Packet.toHex(80)}"
        }
        extendedPictureIdState.setState(vp9Packet.hasExtendedPictureId, vp9Packet) {
            "Packet Data: ${vp9Packet.toHex(80)}"
        }
    }
}
