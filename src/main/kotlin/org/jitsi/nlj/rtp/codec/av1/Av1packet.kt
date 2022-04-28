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

import org.jitsi.nlj.RtpLayerDesc
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.rtp.extensions.bytearray.hashCodeOfSegment
import org.jitsi.utils.logging2.createLogger
import org.jitsi_modified.impl.neomedia.codec.video.vp9.DePacketizer

class Av1packet(
    buffer: ByteArray,
    offset: Int,
    length: Int,
    override val isKeyframe: Boolean,
    override val isStartOfFrame: Boolean,
    override val isEndOfFrame: Boolean,
    val frameNumber: Int,
    val temporalLayerIndex: Int,
    val spatialLayerIndex: Int
) : ParsedVideoPacket(buffer, offset, length, null) {

    override val layerId: Int
        get() = RtpLayerDesc.getIndex(0, spatialLayerIndex, temporalLayerIndex)

    override val payloadVerification: String
        get() {
            val rtpPayloadLength = payloadLength
            val rtpPayloadOffset = payloadOffset
            val vp9pdSize = DePacketizer.VP9PayloadDescriptor.getSize(buffer, rtpPayloadOffset, rtpPayloadLength)
            val vp9PayloadLength = rtpPayloadLength - vp9pdSize
            val hashCode = buffer.hashCodeOfSegment(rtpPayloadOffset + vp9pdSize, rtpPayloadOffset + rtpPayloadLength)
            return "type=AV1Packet len=$vp9PayloadLength hashCode=$hashCode"
        }

    override fun toString(): String {
        return super.toString() + ", SID=$spatialLayerIndex, TID=$temporalLayerIndex, FrameNumber=$frameNumber"
    }

    override fun clone(): Av1packet {
        return Av1packet(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            isKeyframe = isKeyframe,
            isStartOfFrame = isStartOfFrame,
            isEndOfFrame = isEndOfFrame,
            frameNumber = frameNumber,
            temporalLayerIndex = temporalLayerIndex,
            spatialLayerIndex = spatialLayerIndex
        )
    }

    companion object {
        private val logger = createLogger()
    }
}
