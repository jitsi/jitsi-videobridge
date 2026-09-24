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
import org.jitsi.nlj.RtpEncodingDesc
import org.jitsi.nlj.codec.vp8.Vp8Utils
import org.jitsi.nlj.rtp.codec.VideoCodecParser
import org.jitsi.nlj.util.StateChangeLogger
import org.jitsi.rtp.extensions.toHex
import org.jitsi.rtp.util.RtpUtils
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import kotlin.math.min

/**
 * Some [Vp8Packet] fields are not able to be determined by looking at a single VP8 packet (for example the frame
 * height can only be acquired from keyframes).  This class updates the layer descriptions with information
 * from frames, and also diagnoses packet format variants that the Jitsi videobridge won't be able to route.
 */
class Vp8Parser(
    source: MediaSourceDesc,
    parentLogger: Logger
) : VideoCodecParser(source) {
    private val logger = createChildLogger(parentLogger)

    // Consistency
    private val pictureIdState = StateChangeLogger("missing picture id", logger)
    private val extendedPictureIdState = StateChangeLogger("missing extended picture ID", logger)
    private val tidWithoutTl0PicIdxState = StateChangeLogger("TID with missing TL0PICIDX", logger)

    /**
     * The RTP timestamp of the most recent keyframe whose size was applied to each encoding, by the encoding's primary
     * SSRC, so that a reordered or retransmitted older keyframe does not undo a newer one's size.
     */
    private val lastSizedKeyframeTimestamps = HashMap<Long, Long>()

    override fun parse(packetInfo: PacketInfo) {
        val vp8Packet = packetInfo.packetAs<Vp8Packet>()
        if (vp8Packet.height > -1) {
            findRtpEncodingDesc(vp8Packet)?.let { enc -> updateHeight(enc, vp8Packet, packetInfo) }
        }

        pictureIdState.setState(vp8Packet.hasPictureId, vp8Packet) {
            "Packet Data: ${vp8Packet.toHex(80)}"
        }
        extendedPictureIdState.setState(vp8Packet.hasExtendedPictureId, vp8Packet) {
            "Packet Data: ${vp8Packet.toHex(80)}"
        }
        tidWithoutTl0PicIdxState.setState(
            vp8Packet.hasTL0PICIDX || !vp8Packet.hasTemporalLayerIndex,
            vp8Packet
        ) {
            "Packet Data: ${vp8Packet.toHex(80)}"
        }
    }

    /**
     * Sets the height of [enc]'s layers from the keyframe [vp8Packet] starts. The height is the lesser of the frame's
     * width and height, as [org.jitsi.nlj.RtpLayerDesc.height] is defined and the other codecs' parsers compute it, so
     * that portrait video is sized like landscape. The layers are updated in place rather than replaced: VP8's layer
     * structure is signaled, and only the frame size comes from the bitstream. A change of height flags the layering
     * as changed, so that the allocation is recomputed.
     */
    private fun updateHeight(enc: RtpEncodingDesc, vp8Packet: Vp8Packet, packetInfo: PacketInfo) {
        val last = lastSizedKeyframeTimestamps[enc.primarySSRC]
        if (last != null) {
            val diff = RtpUtils.getTimestampDiff(vp8Packet.timestamp, last)
            if (diff < 0 && diff >= -MAX_REORDER_TIMESTAMP_DELTA) {
                /* An older keyframe, reordered or retransmitted: the newer one's size stands. */
                return
            }
        }
        lastSizedKeyframeTimestamps[enc.primarySSRC] = vp8Packet.timestamp
        val height = min(vp8Packet.height, Vp8Utils.getWidthFromKeyFrame(vp8Packet))
        if (enc.updateHeight(height)) {
            packetInfo.layeringChanged = true
        }
    }

    companion object {
        /** VP8's RTP clock rate (RFC 7741). */
        private const val VP8_RTP_CLOCK_RATE = 90_000L

        /**
         * How far behind the most recent sized keyframe's RTP timestamp an older keyframe's can be and still be taken
         * for a reordered or retransmitted one, in RTP timestamp units: two seconds at [VP8_RTP_CLOCK_RATE]. A
         * keyframe further behind is from a sender which restarted its timestamps, and its size is applied.
         */
        private const val MAX_REORDER_TIMESTAMP_DELTA = 2 * VP8_RTP_CLOCK_RATE
    }
}
