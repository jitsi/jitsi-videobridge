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

import org.jitsi.nlj.codec.vp8.Vp8Utils
import org.jitsi.nlj.rtp.ParsedVideoPacket
import org.jitsi.utils.logging2.cwarn
import org.jitsi.rtp.extensions.bytearray.hashCodeOfSegment
import org.jitsi.utils.logging2.createLogger
import org.jitsi_modified.impl.neomedia.codec.video.vp8.DePacketizer
import kotlin.properties.Delegates

/**
 * If this [Vp8Packet] instance is being created via a clone,
 * we've already parsed the packet itself and determined whether
 * or not its a keyframe and what its spatial layer index is,
 * so the constructor allows passing in those values if
 * they're already known.  If they're null, this instance
 * will do the parsing itself.
 */
class Vp8Packet private constructor (
    buffer: ByteArray,
    offset: Int,
    length: Int,
    isKeyframe: Boolean?,
    isStartOfFrame: Boolean?,
    encodingIndex: Int?,
    height: Int?,
    pictureId: Int?,
    TL0PICIDX: Int?
) : ParsedVideoPacket(buffer, offset, length, encodingIndex) {

    constructor(
        buffer: ByteArray,
        offset: Int,
        length: Int
    ) : this(buffer, offset, length,
        isKeyframe = null,
        isStartOfFrame = null,
        encodingIndex = null,
        height = null,
        pictureId = null,
        TL0PICIDX = null
    )

    /** Due to the format of the VP8 payload, this value is only reliable for packets where [isStartOfFrame] is true. */
    override val isKeyframe: Boolean = isKeyframe ?: DePacketizer.isKeyFrame(this.buffer, payloadOffset, payloadLength)

    override val isStartOfFrame: Boolean = isStartOfFrame ?: DePacketizer.VP8PayloadDescriptor.isStartOfFrame(buffer, payloadOffset)

    /** End of VP8 frame is the marker bit. */
    override val isEndOfFrame: Boolean
        /** This uses [get] rather than initialization because [isMarked] is a var. */
        get() = isMarked

    var TL0PICIDX: Int by Delegates.observable(TL0PICIDX ?: DePacketizer.VP8PayloadDescriptor.getTL0PICIDX(buffer, payloadOffset, payloadLength)) {
        _, _, newValue ->
            if (!DePacketizer.VP8PayloadDescriptor.setTL0PICIDX(
                    buffer, payloadOffset, payloadLength, newValue)) {
                logger.cwarn { "Failed to set the TL0PICIDX of a VP8 packet." }
            }
        }

    var pictureId: Int by Delegates.observable(pictureId ?: DePacketizer.VP8PayloadDescriptor.getPictureId(buffer, payloadOffset)) {
        _, _, newValue ->
            if (!DePacketizer.VP8PayloadDescriptor.setExtendedPictureId(
                    buffer, payloadOffset, payloadLength, newValue)) {
                logger.cwarn { "Failed to set the picture id of a VP8 packet." }
            }
        }

    val temporalLayerIndex: Int = Vp8Utils.getTemporalLayerIdOfFrame(this)
    /**
     * This is currently used as an overall spatial index, not an in-band spatial quality index a la vp9.  That is,
     * this index will correspond to an overall simulcast layer index across multiple simulcast stream.  e.g.
     * 180p stream packets will have 0, 360p -> 1, 720p -> 2
     */
    override var height: Int = height ?: if (this.isKeyframe) {
        Vp8Utils.getHeightFromKeyFrame(this)
    } else {
        -1
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
        return Vp8Packet(
            cloneBuffer(BYTES_TO_LEAVE_AT_START_OF_PACKET),
            BYTES_TO_LEAVE_AT_START_OF_PACKET,
            length,
            isKeyframe = isKeyframe,
            isStartOfFrame = isStartOfFrame,
            encodingIndex = qualityIndex,
            height = height,
            pictureId = pictureId,
            TL0PICIDX = TL0PICIDX
        )
    }

    companion object {
        private val logger = createLogger()
    }
}
