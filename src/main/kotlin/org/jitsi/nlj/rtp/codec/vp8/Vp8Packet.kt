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

import org.jitsi.impl.neomedia.codec.video.vp8.DePacketizer
import org.jitsi.nlj.codec.vp8.Vp8Utils
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.nlj.util.cwarn
import org.jitsi.nlj.util.getLogger
import org.jitsi.rtp.extensions.bytearray.hashCodeOfSegment

class Vp8Packet(
    data: ByteArray,
    offset: Int,
    length: Int
) : VideoRtpPacket(data, offset, length) {
    var TL0PICIDX: Int
            get() = DePacketizer.VP8PayloadDescriptor.getTL0PICIDX(buffer, payloadOffset, payloadLength)
            set(value) {
                if (!DePacketizer.VP8PayloadDescriptor.setTL0PICIDX(
                        buffer, payloadOffset, payloadLength, value)) {
                    logger.cwarn { "Failed to set the TL0PICIDX of a VP8 packet." }
                }
            }

    var pictureId: Int
        get() = DePacketizer.VP8PayloadDescriptor.getPictureId(buffer, payloadOffset)
        set(value) {
            if (!DePacketizer.VP8PayloadDescriptor.setExtendedPictureId(
                            buffer, payloadOffset, payloadLength, value)) {
                logger.cwarn { "Failed to set the picture id of a VP8 packet." }
            }
        }

    var temporalLayerIndex: Int = -1
    /**
     * This is currently used as an overall spatial index, not an in-band spatial quality index a la vp9.  That is,
     * this index will correspond to an overall simulcast layer index across multiple simulcast stream.  e.g.
     * 180p stream packets will have 0, 360p -> 1, 720p -> 2
     */
    var spatialLayerIndex: Int = -1
    init {
        isKeyframe = DePacketizer.isKeyFrame(data, payloadOffset, payloadLength)
        if (isKeyframe) {
            spatialLayerIndex = Vp8Utils.getSpatialLayerIndexFromKeyFrame(this)
        }
        temporalLayerIndex = Vp8Utils.getTemporalLayerIdOfFrame(this)
    }

    /**
     * For [Vp8Packet] the payload excludes the VP8 Payload Descriptor.
     */
    override val payloadVerification: String
        get() {
            val rtpPayloadLength = payloadLength
            val rtpPayloadOffset = payloadOffset
            val vp8pdSize = DePacketizer.VP8PayloadDescriptor.getSize(buffer, rtpPayloadOffset, rtpPayloadLength)
            val vp8PayloadLength = rtpPayloadLength - vp8pdSize
            val hashCode = buffer.hashCodeOfSegment(payloadOffset + vp8pdSize, rtpPayloadOffset + rtpPayloadLength)
            return "type=Vp8Packet len=$vp8PayloadLength hashCode=$hashCode"
        }

    override fun clone(): Vp8Packet {
        val clone = Vp8Packet(cloneBuffer(), offset, length)
        clone.isKeyframe = isKeyframe
        // TODO can we ask the superclass to clone its own fields?
        clone.qualityIndex = qualityIndex
        clone.spatialLayerIndex = spatialLayerIndex
        clone.temporalLayerIndex = temporalLayerIndex

        return clone
    }

    companion object {
        private val logger = getLogger(this.javaClass)
    }
}