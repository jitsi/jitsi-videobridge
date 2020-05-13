// /*
//  * Copyright @ 2018 - present 8x8, Inc.
//  *
//  * Licensed under the Apache License, Version 2.0 (the "License");
//  * you may not use this file except in compliance with the License.
//  * You may obtain a copy of the License at
//  *
//  *     http://www.apache.org/licenses/LICENSE-2.0
//  *
//  * Unless required by applicable law or agreed to in writing, software
//  * distributed under the License is distributed on an "AS IS" BASIS,
//  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  * See the License for the specific language governing permissions and
//  * limitations under the License.
//  */
//
// package org.jitsi.videobridge.api.types.vlater
//
// import com.fasterxml.jackson.annotation.JsonSubTypes
// import com.fasterxml.jackson.annotation.JsonTypeInfo
// import java.lang.IllegalArgumentException
//
// enum class MediaType(val type: String) {
//     AUDIO("audio"),
//     VIDEO("video")
// }
//
// enum class PayloadTypeEncoding {
//     OTHER,
//     VP8,
//     VP9,
//     H264,
//     RTX;
//
//     companion object {
//         @JvmStatic
//         fun fromString(str: String): PayloadTypeEncoding {
//             return try {
//                 valueOf(str.toUpperCase())
//             } catch (e: IllegalArgumentException) {
//                 OTHER
//             }
//         }
//     }
// }
//
// enum class RtpExtensionType(val uri: String) {
//     CSRC_AUDIO_LEVEL("urn:ietf:params:rtp-hdrext:csrc-audio-level"),
//     SSRC_AUDIO_LEVEL("urn:ietf:params:rtp-hdrext:ssrc-audio-level"),
//     ABS_SEND_TIME("http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time"),
//     FRAME_MARKING("http://tools.ietf.org/html/draft-ietf-avtext-framemarking-07"),
//     ORIGINAL_HEADER_BLOCK("urn:ietf:params:rtp-hdrext:ohb"),
//     TRANSPORT_CC("http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01"),
//     RTP_STREAM_ID("urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id"),
//     TOF("urn:ietf:params:rtp-hdrext:toffset"),
//     VIDEO_CONTENT_TYPE("http://www.webrtc.org/experiments/rtp-hdrext/video-content-type");
//
//     companion object {
//         private val values = RtpExtensionType.values().associateBy(RtpExtensionType::uri)
//
//         @JvmStatic
//         fun fromUri(uri: String): RtpExtensionType? = values[uri]
//     }
// }
//
// typealias PayloadTypeParams = Map<String, String>
//
// data class RtcpFb(
//     val type: String,
//     val subType: String? = null
// )
//
// typealias RtcpFeedbackSet = Set<RtcpFb>
//
// data class PayloadType(
//     val pt: Byte,
//     val encoding: PayloadTypeEncoding,
//     val mediaType: MediaType,
//     val params: PayloadTypeParams,
//     val feedback: RtcpFeedbackSet,
//     val channels: Int,
//     val clockRate: Int
// )
//
// data class RtpExtension(
//     val id: Byte,
//     val type: RtpExtensionType
// )
//
// data class Source(
//     val ssrc: Long,
//     val mediaType: MediaType
// )
//
// data class SourceGroup(
//     val sources: List<Source>,
//     val semantics: String
// )
//
// data class SourceInformation(
//     val sources: Set<Source>,
//     val sourceGroups: Set<SourceGroup>
// )
//
// enum class IceRole {
//     CONTROLLING,
//     CONTROLLED
// }
//
// data class MediaTypeInformation(
//     val payloadTypes: Set<PayloadType>? = null,
//     val rtpExtensions: Set<RtpExtension>? = null,
//     val sourceInformation: SourceInformation? = null
// )
//
// data class EndpointCreateRequest(
//     val iceRole: IceRole,
//     val mediaTypeInformation: MediaTypeInformation? = null
// )
//
// data class IceCandidate(
//     val component: Int,
//     val foundation: String,
//     val generation: Int,
//     val id: String,
//     val ip: String,
//     val network: Int,
//     val port: Int,
//     val priority: Long,
//     val protocol: String, // TODO: enum
//     val type: String, // change to enum
//     val relAddr: String? = null,
//     val relPort: Int? = null
// )
//
// //TODO: look into using a sealed class for different types of transport
// // here...jackson has support for serializing and deseriailzing sealed
// // classes correctly
// data class IceUdpTransportInformation(
//     val ufrag: String,
//     val password: String,
//     val candidates: List<IceCandidate>,
//     // TODO: is this the best place for this?
//     val dtlsFingerprint: String? = null,
//     val dtlsHash: String? = null
// )
//
// data class TransportInformation(
//     val iceUdpTransportInformation: IceUdpTransportInformation,
//     val websocketUrl: String? = null
// )
//
// // TODO: do we still need these? better way to represent it?
// data class BridgeSourceInformation(
//     val videoSsrc: Long,
//     val audioSsrc: Long
// )
//
// @JsonTypeInfo(use = JsonTypeInfo.Id.NAME)
// @JsonSubTypes(
//     JsonSubTypes.Type(EndpointCreateResponse.Error::class, name = "error"),
//     JsonSubTypes.Type(EndpointCreateResponse.Success::class, name = "success")
// )
// sealed class EndpointCreateResponse {
//     class Error(val errorMsg: String) : EndpointCreateResponse() {
//         override fun toString(): String = "Error: $errorMsg"
//     }
//     class Success(
//         // TODO: bridgeSourceInformation and candidates from inside TransportInformation
//         // can be retrieved a single time from the bridge from another call (stored in
//         // the Bridge class?)
//         val transportInformation: TransportInformation,
//         val bridgeSourceInformation: BridgeSourceInformation
//     ) : EndpointCreateResponse()
// }
//
// data class EndpointModifyRequest(
//     val mediaTypeInformation: MediaTypeInformation? = null,
//     val transportInformation: TransportInformation? = null
// )
//
// data class ConferenceCreateRequest(
//     /**
//      * Required when creating a conference
//      */
//     val name: String,
//     /**
//      * Optional.  If Endpoint information for this conference
//      * is already known, they can be created alongside the conference by
//      * filling out their information here.
//      */
//     val endpoints: Map<String, EndpointCreateRequest>? = null
// )
//
// data class ConferenceCreateResponse(
//     val id: String
// )
