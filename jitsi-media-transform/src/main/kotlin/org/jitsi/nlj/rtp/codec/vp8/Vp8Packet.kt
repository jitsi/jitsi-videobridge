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
import org.jitsi.nlj.rtp.VideoRtpPacket
import org.jitsi.rtp.rtp.RtpHeader
import org.jitsi.rtp.util.ByteBufferUtils
import java.nio.ByteBuffer

class Vp8Packet(
    header: RtpHeader = RtpHeader(),
    payload: ByteBuffer = ByteBufferUtils.EMPTY_BUFFER,
    backingBuffer: ByteBuffer? = null
) : VideoRtpPacket(header, payload, backingBuffer) {
    var temporalLayerIndex: Int = -1
    /**
     * This is currently used as an overall spatial index, not an in-band spatial quality index a la vp9.  That is,
     * this index will correspond to an overall simulcast layer index across multiple simulcast stream.  e.g.
     * 180p stream packets will have 0, 360p -> 1, 720p -> 2
     */
    var spatialLayerIndex: Int = -1

    init {
        isKeyFrame = Vp8Utils.isKeyFrame(payload)
        if (isKeyFrame) {
            spatialLayerIndex = Vp8Utils.getSpatialLayerIndexFromKeyFrame(payload)
        }
        temporalLayerIndex = Vp8Utils.getTemporalLayerIdOfFrame(payload)
    }

    override fun clone(): Vp8Packet {
        val clone = Vp8Packet(header.clone(), cloneMutablePayload())
        clone.temporalLayerIndex = temporalLayerIndex
        clone.spatialLayerIndex = spatialLayerIndex

        return clone
    }

    override fun toString(): String = with (StringBuffer()) {
        append(super.toString())
        appendln("temporal layer index: $temporalLayerIndex")
        appendln("spatial layer index: $spatialLayerIndex")

        toString()
    }
}