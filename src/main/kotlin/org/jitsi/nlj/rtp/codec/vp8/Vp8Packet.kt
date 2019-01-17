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
import org.jitsi.rtp.Packet
import org.jitsi.rtp.extensions.clone
import java.nio.ByteBuffer

class Vp8Packet : VideoRtpPacket {
    var temporalLayerIndex: Int = -1
    var spatialLayerIndex: Int = -1

    constructor (buf: ByteBuffer) : super(buf) {
        isKeyFrame = Vp8Utils.isKeyFrame(payload)
        if (isKeyFrame) {
            spatialLayerIndex = Vp8Utils.getSpatialLayerIndexFromKeyFrame(payload)
        }
        temporalLayerIndex = Vp8Utils.getTemporalLayerIdOfFrame(payload)
    }

    override fun clone(): Packet {
        val clone = Vp8Packet(getBuffer().clone())
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