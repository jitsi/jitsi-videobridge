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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import org.jitsi.utils.MediaType

typealias PayloadTypeParams = Map<String, String>

typealias RtcpFeedbackSet = Set<String>

fun RtcpFeedbackSet.supportsPli(): Boolean = this.contains("nack pli")
fun RtcpFeedbackSet.supportsFir(): Boolean = this.contains("ccm fir")
fun RtcpFeedbackSet.supportsRemb(): Boolean = this.contains("goog-remb")
fun RtcpFeedbackSet.supportsTcc(): Boolean = this.contains("transport-cc")
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
    val parameters: PayloadTypeParams = ConcurrentHashMap(),

    /**
     * The rtcp feedback messages associated with the payload type (e.g. nack, nack pli, transport-cc, goog-remb, ccm fir, etc).
     */
    rtcpFeedbackSet: RtcpFeedbackSet = emptySet()
) {
    val rtcpFeedbackSet = CopyOnWriteArraySet(rtcpFeedbackSet)

    override fun toString(): String = with(StringBuffer()) {
        append(pt).append(" -> ").append(encoding).append(" (").append(clockRate).append("): ").append(parameters)

        toString()
    }
}

enum class PayloadTypeEncoding {
    OTHER,
    VP8,
    VP9,
    H264,
    RED,
    RTX,
    OPUS;

    companion object {
        /**
         * [valueOf] does not allow for case-insensitivity and can't be overridden, so this
         * method should be used when creating an instance of this enum from a string
         */
        fun createFrom(value: String): PayloadTypeEncoding {
            return try {
                valueOf(value.toUpperCase())
            } catch (e: IllegalArgumentException) {
                return OTHER
            }
        }
    }

    override fun toString(): String = with(StringBuffer()) {
        append(super.toString())
        toString()
    }
}

abstract class VideoPayloadType(
    pt: Byte,
    encoding: PayloadTypeEncoding,
    clockRate: Int = 90000,
    parameters: PayloadTypeParams = ConcurrentHashMap(),
    rtcpFeedbackSet: RtcpFeedbackSet = emptySet()
) : PayloadType(pt, encoding, MediaType.VIDEO, clockRate, parameters, rtcpFeedbackSet)

class Vp8PayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap(),
    rtcpFeedbackSet: RtcpFeedbackSet = emptySet()
) : VideoPayloadType(pt, PayloadTypeEncoding.VP8, parameters = parameters, rtcpFeedbackSet = rtcpFeedbackSet)

class Vp9PayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap(),
    rtcpFeedbackSet: RtcpFeedbackSet = emptySet()
) : VideoPayloadType(pt, PayloadTypeEncoding.VP9, parameters = parameters, rtcpFeedbackSet = rtcpFeedbackSet)

class H264PayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap(),
    rtcpFeedbackSet: RtcpFeedbackSet = emptySet()
) : VideoPayloadType(pt, PayloadTypeEncoding.H264, parameters = parameters, rtcpFeedbackSet = rtcpFeedbackSet)

class RtxPayloadType(
    pt: Byte,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : VideoPayloadType(pt, PayloadTypeEncoding.RTX, parameters = parameters) {
    val associatedPayloadType: Int =
        parameters["apt"]?.toInt() ?: error("RtxPayloadType must contain 'apt'")
}

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

class AudioRedPayloadType(
    pt: Byte,
    clockRate: Int = 48000,
    parameters: PayloadTypeParams = ConcurrentHashMap()
) : AudioPayloadType(pt, PayloadTypeEncoding.RED, clockRate, parameters)

class VideoRedPayloadType(
    pt: Byte,
    clockRate: Int = 90000,
    parameters: PayloadTypeParams = ConcurrentHashMap(),
    rtcpFeedbackSet: RtcpFeedbackSet = emptySet()
) : VideoPayloadType(pt, PayloadTypeEncoding.RED, clockRate, parameters, rtcpFeedbackSet)

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
