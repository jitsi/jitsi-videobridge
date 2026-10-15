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

package org.jitsi.nlj.codec.vp8

import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi_modified.impl.neomedia.codec.video.vp8.DePacketizer
import java.nio.ByteBuffer

class Vp8Utils {
    companion object {
        private const val VP8_PAYLOAD_HEADER_LEN = 3

        /** The keyframe header: a three-byte start code, then two bytes each of width and height (RFC 6386). */
        private const val VP8_KEYFRAME_HEADER_LEN = 7

        // TODO(brian): should move these elsewhere probably
        private const val MIN_HD_HEIGHT = 540
        private const val MIN_SD_HEIGHT = 360
        private const val HD_LAYER_ID = 2
        private const val SD_LAYER_ID = 1
        private const val LD_LAYER_ID = 0
        private const val SUSPENDED_LAYER_ID = -1

        fun isKeyFrame(vp8Payload: ByteBuffer): Boolean =
            DePacketizer.isKeyFrame(vp8Payload.array(), vp8Payload.arrayOffset(), vp8Payload.limit())

        fun getSpatialLayerIndexFromKeyFrame(vp8Payload: ByteBuffer): Int {
            // Copied from VP8QualityFilter#getSpatialLayerIndexFromKeyframe
            val height = DePacketizer.VP8KeyframeHeader.getHeight(
                vp8Payload.array(),
                keyframeHeaderOffset(vp8Payload.array(), vp8Payload.arrayOffset(), vp8Payload.limit())
            )
            return when {
                height >= MIN_HD_HEIGHT -> HD_LAYER_ID
                height >= MIN_SD_HEIGHT -> SD_LAYER_ID
                height > -1 -> LD_LAYER_ID
                else -> -1
            }
        }

        fun getHeightFromKeyFrame(vp8Packet: RtpPacket): Int =
            DePacketizer.VP8KeyframeHeader.getHeight(vp8Packet.buffer, keyframeHeaderOffset(vp8Packet))

        /** The width of the keyframe which [vp8Packet] starts; only meaningful if it starts one. */
        fun getWidthFromKeyFrame(vp8Packet: RtpPacket): Int =
            DePacketizer.VP8KeyframeHeader.getWidth(vp8Packet.buffer, keyframeHeaderOffset(vp8Packet))

        /**
         * Whether [vp8Packet], the first packet of a keyframe, carries a whole keyframe header with the start code
         * RFC 6386 requires. A truncated or corrupt packet does not, and its width and height must not be read.
         */
        fun hasKeyframeHeader(vp8Packet: RtpPacket): Boolean {
            val offset = keyframeHeaderOffset(vp8Packet)
            val end = vp8Packet.payloadOffset + vp8Packet.payloadLength
            if (offset + VP8_KEYFRAME_HEADER_LEN > end) {
                return false
            }
            val buf = vp8Packet.buffer
            return buf[offset] == 0x9d.toByte() && buf[offset + 1] == 0x01.toByte() && buf[offset + 2] == 0x2a.toByte()
        }

        private fun keyframeHeaderOffset(vp8Packet: RtpPacket): Int =
            keyframeHeaderOffset(vp8Packet.buffer, vp8Packet.payloadOffset, vp8Packet.payloadLength)

        /** The offset of the keyframe header in the VP8 payload at [offset] of [buffer], after the payload header. */
        private fun keyframeHeaderOffset(buffer: ByteArray, offset: Int, length: Int): Int =
            offset + DePacketizer.VP8PayloadDescriptor.getSize(buffer, offset, length) + VP8_PAYLOAD_HEADER_LEN

        fun getSpatialLayerIndexFromKeyFrame(vp8Packet: RtpPacket): Int {
            // Copied from VP8QUalityFilter#getSpatialLayerIndexFromKeyframe
            val height = getHeightFromKeyFrame(vp8Packet)
            return when {
                height >= MIN_HD_HEIGHT -> HD_LAYER_ID
                height >= MIN_SD_HEIGHT -> SD_LAYER_ID
                height > -1 -> LD_LAYER_ID
                else -> -1
            }
        }

        fun getTemporalLayerIdOfFrame(vp8Payload: ByteBuffer) = DePacketizer.VP8PayloadDescriptor.getTemporalLayerIndex(
            vp8Payload.array(),
            vp8Payload.arrayOffset(),
            vp8Payload.limit()
        )

        fun getTemporalLayerIdOfFrame(vp8Packet: RtpPacket) = DePacketizer.VP8PayloadDescriptor.getTemporalLayerIndex(
            vp8Packet.buffer,
            vp8Packet.payloadOffset,
            vp8Packet.payloadLength
        )
    }
}
