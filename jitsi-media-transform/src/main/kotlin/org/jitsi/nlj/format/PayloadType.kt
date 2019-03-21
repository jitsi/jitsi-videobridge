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

import org.jitsi.utils.MediaType
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
    val encoding: PayloadTypeEncoding,
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
}

enum class PayloadTypeEncoding {
    OTHER,
    VP8,
    VP9,
    H264,
    RTX,
    OPUS;

    var unknownVal: String? = null

    companion object {
        /**
         * [valueOf] does not allow for case-insensitivity and can't be overridden, so this
         * method should be used when creating an instance of this enum from a string
         */
        fun createFrom(value: String): PayloadTypeEncoding {
            return try {
                PayloadTypeEncoding.valueOf(value.toUpperCase())
            } catch (e: IllegalArgumentException) {
                return PayloadTypeEncoding.OTHER.also { it.unknownVal = value }
            }
        }
    }

    override fun toString(): String = with (StringBuffer()) {
        append(super.toString())
        unknownVal?.let { append(" (").append(it).append(")")}
        toString()
    }
}

abstract class VideoPayloadType(
    pt: Byte,
    encoding: PayloadTypeEncoding,
    clockRate: Int = 90000,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : PayloadType(pt, encoding, MediaType.VIDEO, clockRate, parameters)

class Vp8PayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, PayloadTypeEncoding.VP8, parameters = parameters)

class Vp9PayloadType(
        pt: Byte,
        parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, PayloadTypeEncoding.VP9, parameters = parameters)

class H264PayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, PayloadTypeEncoding.H264, parameters = parameters)

class RtxPayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, PayloadTypeEncoding.RTX, parameters = parameters)

abstract class AudioPayloadType(
    pt: Byte,
    encoding: PayloadTypeEncoding,
    clockRate: Int = 48000,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : PayloadType(pt, encoding, MediaType.AUDIO, clockRate, parameters)

class OpusPayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : AudioPayloadType(pt, PayloadTypeEncoding.OPUS, parameters = parameters)

class OtherAudioPayloadType(
    pt: Byte,
    clockRate: Int,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : AudioPayloadType(pt, PayloadTypeEncoding.OTHER, clockRate, parameters)

class OtherVideoPayloadType(
    pt: Byte,
    clockRate: Int,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, PayloadTypeEncoding.OTHER, clockRate, parameters)
