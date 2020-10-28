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

import java.nio.ByteBuffer
import org.jitsi.rtp.rtp.RtpPacket
import org.jitsi_modified.impl.neomedia.codec.video.vp8.DePacketizer

class Vp8Utils {
    companion object {
        private const val VP8_PAYLOAD_HEADER_LEN = 3
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
            // Copied from VP8QUalityFilter#getSpatialLayerIndexFromKeyframe
            val payloadDescriptorLen =
                DePacketizer.VP8PayloadDescriptor.getSize(
                    vp8Payload.array(),
                    vp8Payload.arrayOffset(),
                    vp8Payload.limit()
                )
            val height = DePacketizer.VP8KeyframeHeader.getHeight(
                vp8Payload.array(),
                vp8Payload.arrayOffset() + payloadDescriptorLen + VP8_PAYLOAD_HEADER_LEN
            )
            return when {
                height >= MIN_HD_HEIGHT -> HD_LAYER_ID
                height >= MIN_SD_HEIGHT -> SD_LAYER_ID
                height > -1 -> LD_LAYER_ID
                else -> -1
            }
        }

        fun getHeightFromKeyFrame(vp8Packet: RtpPacket): Int {
            val payloadDescriptorLen =
                DePacketizer.VP8PayloadDescriptor.getSize(
                    vp8Packet.buffer,
                    vp8Packet.payloadOffset,
                    vp8Packet.payloadLength
                )
            return DePacketizer.VP8KeyframeHeader.getHeight(
                vp8Packet.buffer, vp8Packet.payloadOffset + payloadDescriptorLen + VP8_PAYLOAD_HEADER_LEN
            )
        }

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

        fun getTemporalLayerIdOfFrame(vp8Payload: ByteBuffer) =
            DePacketizer.VP8PayloadDescriptor.getTemporalLayerIndex(
                vp8Payload.array(), vp8Payload.arrayOffset(), vp8Payload.limit()
            )

        fun getTemporalLayerIdOfFrame(vp8Packet: RtpPacket) =
            DePacketizer.VP8PayloadDescriptor.getTemporalLayerIndex(
                vp8Packet.buffer, vp8Packet.payloadOffset, vp8Packet.payloadLength
            )

        /**
         * Returns the delta between two VP8 extended picture IDs, taking into account
         * rollover.  This will return the 'shortest' delta between the two
         * picture IDs in the form of the number you'd add to b to get a. e.g.:
         * getExtendedPictureIdDelta(1, 10) -> -9 (10 + -9 = 1)
         * getExtendedPictureIdDelta(1, 32760) -> 9 (32760 + 9 = 1)
         * @return the delta between two extended picture IDs (modulo 2^15).
         */
        @JvmStatic
        fun getExtendedPictureIdDelta(a: Int, b: Int): Int {
            val diff = a - b
            return when {
                diff < -(1 shl 14) -> diff + (1 shl 15)
                diff > (1 shl 14) -> diff - (1 shl 15)
                else -> diff
            }
        }

        /**
         * Apply a delta to a given extended picture ID and return the result (taking
         * rollover into account)
         * @param start the starting extended picture ID
         * @param delta the delta to be applied
         * @return the extended picture ID resulting from doing "start + delta"
         */
        @JvmStatic
        fun applyExtendedPictureIdDelta(start: Int, delta: Int): Int =
            (start + delta) and DePacketizer.VP8PayloadDescriptor.EXTENDED_PICTURE_ID_MASK

        /**
         * Returns the delta between two VP8 Tl0PicIdx values, taking into account
         * rollover.  This will return the 'shortest' delta between the two
         * picture IDs in the form of the number you'd add to b to get a. e.g.:
         * getTl0PicIdxDelta(1, 10) -> -9 (10 + -9 = 1)
         * getTl0PicIdxDelta(1, 250) -> 7 (250 + 7 = 1)
         *
         * If either value is -1 (meaning tl0picidx not found) return 0.
         * @return the delta between two extended picture IDs (modulo 2^8).
         */
        @JvmStatic
        fun getTl0PicIdxDelta(a: Int, b: Int): Int {
            if (a < 0 || b < 0) return 0
            val diff = a - b
            return when {
                diff < -(1 shl 7) -> diff + (1 shl 8)
                diff > (1 shl 7) -> diff - (1 shl 8)
                else -> diff
            }
        }

        /**
         * Apply a delta to a given Tl0PidIcx and return the result (taking
         * rollover into account)
         * @param start the starting Tl0PicIdx
         * @param delta the delta to be applied
         * @return the Tl0PicIdx resulting from doing "start + delta"
         */
        @JvmStatic
        fun applyTl0PicIdxDelta(start: Int, delta: Int): Int =
            (start + delta) and DePacketizer.VP8PayloadDescriptor.TL0PICIDX_MASK
    }
}
