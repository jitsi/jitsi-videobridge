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
package org.jitsi.videobridge.util

import org.jitsi.nlj.format.AudioRedPayloadType
import org.jitsi.nlj.format.H264PayloadType
import org.jitsi.nlj.format.OpusPayloadType
import org.jitsi.nlj.format.OtherAudioPayloadType
import org.jitsi.nlj.format.OtherVideoPayloadType
import org.jitsi.nlj.format.PayloadType
import org.jitsi.nlj.format.PayloadTypeEncoding.Companion.createFrom
import org.jitsi.nlj.format.PayloadTypeEncoding.H264
import org.jitsi.nlj.format.PayloadTypeEncoding.OPUS
import org.jitsi.nlj.format.PayloadTypeEncoding.OTHER
import org.jitsi.nlj.format.PayloadTypeEncoding.RED
import org.jitsi.nlj.format.PayloadTypeEncoding.RTX
import org.jitsi.nlj.format.PayloadTypeEncoding.VP8
import org.jitsi.nlj.format.PayloadTypeEncoding.VP9
import org.jitsi.nlj.format.RtxPayloadType
import org.jitsi.nlj.format.VideoRedPayloadType
import org.jitsi.nlj.format.Vp8PayloadType
import org.jitsi.nlj.format.Vp9PayloadType
import org.jitsi.utils.MediaType
import org.jitsi.utils.MediaType.AUDIO
import org.jitsi.utils.MediaType.VIDEO
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension
import java.util.concurrent.ConcurrentHashMap

/**
 * Utilities to deserialize [PayloadTypePacketExtension] into a
 * {PayloadType}. This is currently in `jitsi-videobridge` in order to
 * avoid adding the XML extensions as a dependency to
 * `jitsi-media-transform`.
 *
 * @author Boris Grozev
 * @author Brian Baldino
 */
class PayloadTypeUtil {
    companion object {
        private val logger: Logger = LoggerImpl(PayloadTypeUtil::class.java.name)

        /**
         * Creates a [PayloadType] for the payload type described in the
         * given [PayloadTypePacketExtension].
         * @param ext the XML extension which describes the payload type.
         */
        @JvmStatic
        fun create(
            ext: PayloadTypePacketExtension,
            mediaType: MediaType
        ): PayloadType? {
            val parameters: MutableMap<String, String> = ConcurrentHashMap()
            ext.parameters.forEach { parameter ->
                // In SDP, format parameters don't necessarily come in name=value pairs (see e.g. the format used in
                // RFC2198). However XEP-0167 requires a name and a value. Our SDP-to-Jingle implementation in
                // lib-jitsi-meet translates a non-name=value SDP string into a parameter extension with a value but no
                // name. Here we'll just ignore such parameters, because we don't currently support any and changing the
                // implementation would be inconvenient (we store them mapped by name).
                if (parameter.name != null) {
                    parameters[parameter.name] = parameter.value
                } else {
                    logger.warn("Ignoring a format parameter with no name: " + parameter.toXML())
                }
            }

            val rtcpFeedbackSet = ext.rtcpFeedbackTypeList.map { rtcpExtension ->
                buildString {
                    append(rtcpExtension.feedbackType)
                    if (!rtcpExtension.feedbackSubtype.isNullOrBlank()) {
                        append(" ${rtcpExtension.feedbackSubtype}")
                    }
                }
            }.toSet()

            val id = ext.id.toByte()
            val encoding = createFrom(ext.name)
            val clockRate = ext.clockrate

            return when (encoding) {
                VP8 -> Vp8PayloadType(id, parameters, rtcpFeedbackSet)
                VP9 -> Vp9PayloadType(id, parameters, rtcpFeedbackSet)
                H264 -> H264PayloadType(id, parameters, rtcpFeedbackSet)
                RTX -> RtxPayloadType(id, parameters)
                OPUS -> OpusPayloadType(id, parameters)
                RED -> when (mediaType) {
                    AUDIO -> AudioRedPayloadType(id, clockRate, parameters)
                    VIDEO -> VideoRedPayloadType(id, clockRate, parameters, rtcpFeedbackSet)
                    else -> null
                }
                OTHER -> when (mediaType) {
                    AUDIO -> OtherAudioPayloadType(id, clockRate, parameters)
                    VIDEO -> OtherVideoPayloadType(id, clockRate, parameters)
                    else -> null
                }
            }
        }
    }
}
