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

package org.jitsi.nlj.rtp.codec.vp8

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.codec.VideoCodecParser
import org.jitsi.nlj.util.StateChangeLogger
import org.jitsi.rtp.extensions.toHex
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger

/**
 * Some [Vp8Packet] fields are not able to be determined by looking at a single VP8 packet (for example the frame
 * height can only be acquired from keyframes).  This class updates the layer descriptions with information
 * from frames, and also diagnoses packet format variants that the Jitsi videobridge won't be able to route.
 */
class Vp8Parser(
    sources: Array<MediaSourceDesc>,
    parentLogger: Logger
) : VideoCodecParser(sources) {
    private val logger = createChildLogger(parentLogger)

    // Consistency
    private val pictureIdState = StateChangeLogger("missing picture id", logger)
    private val extendedPictureIdState = StateChangeLogger("missing extended picture ID", logger)
    private val tidWithoutTl0PicIdxState = StateChangeLogger("TID with missing TL0PICIDX", logger)

    override fun parse(packetInfo: PacketInfo) {
        val vp8Packet = packetInfo.packetAs<Vp8Packet>()
        if (vp8Packet.height > -1) {
            // TODO: handle case where new height is from a packet older than the
            //  latest height we've seen.
            findRtpEncodingDesc(vp8Packet)?.let { enc ->
                val newLayers = enc.layers.map { layer -> layer.copy(height = vp8Packet.height) }
                enc.layers = newLayers.toTypedArray()
            }
        }

        pictureIdState.setState(vp8Packet.hasPictureId, vp8Packet) {
            "Packet Data: ${vp8Packet.toHex(80)}"
        }
        extendedPictureIdState.setState(vp8Packet.hasExtendedPictureId, vp8Packet) {
            "Packet Data: ${vp8Packet.toHex(80)}"
        }
        tidWithoutTl0PicIdxState.setState(
            vp8Packet.hasTL0PICIDX || !vp8Packet.hasTemporalLayerIndex, vp8Packet
        ) {
            "Packet Data: ${vp8Packet.toHex(80)}"
        }
    }
}
