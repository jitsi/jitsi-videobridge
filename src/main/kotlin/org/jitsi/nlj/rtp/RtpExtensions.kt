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
package org.jitsi.nlj.rtp

/**
 * Represents a signalled RTP header extension.
 *
 * @author Boris Grozev
 */
data class RtpExtension(
    /**
     * The ID that was signalled.
     */
    val id: Byte,
    /**
     * The type.
     */
    val type: RtpExtensionType
)

/**
 * Represents an RTP header extension type.
 *
 * @author Boris Grozev
 */
enum class RtpExtensionType(val uri: String) {
    /**
     * The URN identifying the RTP extension that allows mixers to send to
     * conference participants the audio levels of all contributing sources.
     * Defined in RFC6465.
     */
    CSRC_AUDIO_LEVEL("urn:ietf:params:rtp-hdrext:csrc-audio-level"),

    /**
     * The URN identifying the RTP extension that allows clients to send to
     * conference mixers the audio level of their packet payload. Defined in
     * RFC6464.
     */
    SSRC_AUDIO_LEVEL("urn:ietf:params:rtp-hdrext:ssrc-audio-level"),

    /**
     * The URN identifying the abs-send-time RTP extension.
     * Defined at
     * {@link "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time"}
     */
    ABS_SEND_TIME("http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time"),

    /**
     * The URN which identifies the framemarking RTP extension defined at
     * {@link "https://tools.ietf.org/html/draft-ietf-avtext-framemarking-03"}
     */
    FRAME_MARKING("http://tools.ietf.org/html/draft-ietf-avtext-framemarking-07"),

    /**
     * The URN which identifies the Original Header Block RTP extension defined
     * in {@link "https://tools.ietf.org/html/draft-ietf-perc-double-02"}.
     */
    ORIGINAL_HEADER_BLOCK("urn:ietf:params:rtp-hdrext:ohb"),

    /**
     * The URN which identifies the Transport-Wide Congestion Control RTP
     * extension.
     */
    TRANSPORT_CC("http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01"),

    /**
     * The URN which identifies the rtp-stream-id extensions
     * in {@link "https://tools.ietf.org/html/draft-ietf-mmusic-rid-10"}.
     */
    RTP_STREAM_ID("urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id"),

    /**
     * The URN which identifies the transmission time-offset extensions
     * in {@link "https://tools.ietf.org/html/rfc5450"}.
     */
    TOF("urn:ietf:params:rtp-hdrext:toffset"),

    /**
     * The URN which identifies the RTP Header Extension for Video Content Type.
     */
    VIDEO_CONTENT_TYPE("http://www.webrtc.org/experiments/rtp-hdrext/video-content-type");

    companion object {
        private val uriMap = RtpExtensionType.values().associateBy(RtpExtensionType::uri)
        fun createFromUri(uri: String): RtpExtensionType? =
            uriMap.getOrDefault(uri, null)
    }
}
