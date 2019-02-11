/*
 * Copyright @ 2019-Present 8x8, Inc
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
package org.jitsi.nlj.format

import org.jitsi.service.neomedia.MediaType
import java.util.concurrent.ConcurrentHashMap

typealias PayloadTypeParams = Map<String, String>
/**
 * Represents an RTP payload type.
 *
 * @author Boris Grozev
 * @author Brian Baldino
 */
abstract class PayloadType(
    /**
     *  The 7-bit RTP payload type number.
     */
    val pt: Byte,
    /**
     * The encoding name.
     */
    val encoding: String,
    /**
     * The media type (audio or video).
     */
    val mediaType: MediaType,
    /**
     * The RTP clock rate.
     */
    val clockRate: Int,
    /**
     * Additional parameters associated with the payload type (e.g. the "apt" used for RTX).
     */
    val parameters: PayloadTypeParams = ConcurrentHashMap()) {

    override fun toString(): String = with (StringBuffer()) {
        append(pt).append(" -> ").append(encoding).append(" (").append(clockRate).append("): ").append(parameters)

        toString()
    }

    companion object {
        const val VP8 = "vp8"
        const val VP9 = "vp9"
        const val H264 = "h264"
        const val RTX = "rtx"
        const val RTX_APT = "apt"
        const val OPUS = "opus"
    }
}

open class VideoPayloadType(
    pt: Byte,
    encoding: String,
    clockRate: Int = 90000,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : PayloadType(pt, encoding, MediaType.VIDEO, clockRate, parameters)

class Vp8PayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, VP8, parameters = parameters)

class Vp9PayloadType(
        pt: Byte,
        parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, VP9, parameters = parameters)

class H264PayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, H264, parameters = parameters)

class RtxPayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, RTX, parameters = parameters)

open class AudioPayloadType(
    pt: Byte,
    encoding: String,
    clockRate: Int = 48000,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : PayloadType(pt, encoding, MediaType.AUDIO, clockRate, parameters)

class OpusPayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : AudioPayloadType(pt, OPUS, parameters = parameters)
