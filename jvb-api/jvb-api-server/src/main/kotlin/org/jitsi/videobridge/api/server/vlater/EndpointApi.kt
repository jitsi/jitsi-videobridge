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
// package org.jitsi.videobridge.api.server.vlater
//
// import io.ktor.application.call
// import io.ktor.http.HttpStatusCode
// import io.ktor.request.receive
// import io.ktor.response.respond
// import io.ktor.routing.Route
// import io.ktor.routing.delete
// import io.ktor.routing.patch
// import io.ktor.routing.post
// import io.ktor.routing.route
// import org.jitsi.nlj.rtp.RtpExtension
// import org.jitsi.nlj.rtp.RtpExtensionType
// import org.jitsi.nlj.rtp.SsrcAssociationType
// import org.jitsi.utils.MediaType
// import org.jitsi.videobridge.AbstractEndpoint
// import org.jitsi.videobridge.Endpoint
// import org.jitsi.videobridge.Videobridge
// import org.jitsi.videobridge.api.server.v1.conference
// import org.jitsi.videobridge.api.types.vlater.BridgeSourceInformation
// import org.jitsi.videobridge.api.types.vlater.EndpointCreateRequest
// import org.jitsi.videobridge.api.types.vlater.EndpointCreateResponse
// import org.jitsi.videobridge.api.types.vlater.EndpointModifyRequest
// import org.jitsi.videobridge.api.types.vlater.IceCandidate
// import org.jitsi.videobridge.api.types.vlater.IceRole
// import org.jitsi.videobridge.api.types.vlater.IceUdpTransportInformation
// import org.jitsi.videobridge.api.types.vlater.MediaTypeInformation
// import org.jitsi.videobridge.api.types.vlater.TransportInformation
// import org.jitsi.videobridge.signaling.api.getEpId
// import org.jitsi.videobridge.util.PayloadTypeUtil
// import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
// import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
// import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
// import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
// import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
// import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension
// import org.jitsi.xmpp.extensions.jingle.RtcpFbPacketExtension
// import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
//
// fun Route.endpointsApi() {
//     route("endpoints/{epId}") {
//         post {
//             val epCreateReq = call.receive<EndpointCreateRequest>()
//             val conf = call.conference
//             val ep = conf.createLocalEndpoint(getEpId(), epCreateReq.iceRole == IceRole.CONTROLLING)
//
//             // Media information may be known up-front
//             updateMediaInformation(ep, epCreateReq.mediaTypeInformation)
//             ep.setAcceptsAudio(true)
//             ep.setAcceptsVideo(true)
//
//             val channelBundle = ColibriConferenceIQ.ChannelBundle(getEpId())
//             ep.describe(channelBundle)
//
//             val resp = EndpointCreateResponse.Success(
//                 TransportInformation(
//                     IceUdpTransportInformation(
//                         ufrag = channelBundle.transport.ufrag,
//                         password = channelBundle.transport.password,
//                         candidates = channelBundle.transport.candidateList.map {
//                             IceCandidate(
//                                 component = it.component,
//                                 foundation = it.foundation,
//                                 generation = it.generation,
//                                 id = it.id,
//                                 ip = it.ip,
//                                 network = it.network,
//                                 port = it.port,
//                                 priority = it.priority.toLong(),
//                                 protocol = it.protocol,
//                                 relAddr = it.relAddr,
//                                 relPort = it.relPort,
//                                 type = it.type.toString()
//                             )
//                         },
//                         dtlsFingerprint = channelBundle.transport.getFirstChildOfType(DtlsFingerprintPacketExtension::class.java)?.fingerprint,
//                         dtlsHash = channelBundle.transport.getFirstChildOfType(DtlsFingerprintPacketExtension::class.java)?.hash
//                     ),
//                     websocketUrl = channelBundle.transport.getFirstChildOfType(WebSocketPacketExtension::class.java)?.url
//                 ),
//                 BridgeSourceInformation(
//                     videoSsrc = Videobridge.RANDOM.nextLong() and 0xffffffffL,
//                     audioSsrc = Videobridge.RANDOM.nextLong() and 0xffffffffL
//                 )
//             )
//             call.respond(resp)
//         }
//         patch {
//             val epModifyReq = call.receive<EndpointModifyRequest>()
//
// //            val conf = call.attributes[CONF_KEY]
//             val conf = call.conference ?: TODO()
//
//             val transportInfo = IceUdpTransportPacketExtension()
//             transportInfo.password = epModifyReq.transportInformation?.iceUdpTransportInformation?.password
//             transportInfo.ufrag = epModifyReq.transportInformation?.iceUdpTransportInformation?.ufrag
//             transportInfo.addChildExtension(DtlsFingerprintPacketExtension().apply {
//                 this.fingerprint = epModifyReq.transportInformation?.iceUdpTransportInformation?.dtlsFingerprint
//                 this.hash = epModifyReq.transportInformation?.iceUdpTransportInformation?.dtlsHash
//                 // TODO:
//                 this.setup = "passive"
//             })
//             val ep = conf.getEndpoint(getEpId()!!)
//
//             (ep as Endpoint).setTransportInfo(transportInfo)
//             updateMediaInformation(ep, epModifyReq.mediaTypeInformation)
//             call.respond(HttpStatusCode.OK)
//         }
//         delete {
//
//
//         }
//     }
// }
//
// private fun updateMediaInformation(ep: AbstractEndpoint, mediaTypeInformation: MediaTypeInformation?) {
//     mediaTypeInformation ?: return
//     (ep as Endpoint).recreateMediaStreamTracks2(mediaTypeInformation.sourceInformation?.sources?.toList(), mediaTypeInformation.sourceInformation?.sourceGroups?.toList())
//     mediaTypeInformation.sourceInformation?.sources?.forEach { src ->
//         ep.addReceiveSsrc(src.ssrc, MediaType.valueOf(src.mediaType.toString()))
//     }
//     mediaTypeInformation.sourceInformation?.sourceGroups?.forEach { srcGroup ->
//         val ssrcAssociationType = when {
//             srcGroup.semantics.equals(SourceGroupPacketExtension.SEMANTICS_FID, ignoreCase = true) -> SsrcAssociationType.RTX
//             srcGroup.semantics.equals(SourceGroupPacketExtension.SEMANTICS_SIMULCAST, ignoreCase = true) -> SsrcAssociationType.SIM
//             srcGroup.semantics.equals(SourceGroupPacketExtension.SEMANTICS_FEC, ignoreCase = true) -> SsrcAssociationType.FEC
//             else -> {
//                 println("Unknown semantics: ${srcGroup.semantics}")
//                 return@forEach
//             }
//         }
//         if (ssrcAssociationType == SsrcAssociationType.SIM) {
//             return@forEach
//         }
//
//         ep.conference.encodingsManager.addSsrcAssociation(
//             ep.id,
//             srcGroup.sources[0].ssrc,
//             srcGroup.sources[1].ssrc,
//             ssrcAssociationType
//         )
//     }
//
//     // TODO: for now we create a PayloadTypePacketExtension and use that to create a PayloadType
//     mediaTypeInformation.payloadTypes?.forEach {
//         val pte = PayloadTypePacketExtension().apply {
//             setId(it.pt.toInt())
//             name = it.encoding.toString()
//             it.params.forEach { param ->
//                 addParameter(ParameterPacketExtension(param.key, param.value))
//             }
//             it.feedback.forEach { fb ->
//                 addRtcpFeedbackType(RtcpFbPacketExtension().apply {
//                     feedbackType = fb.type
//                     feedbackSubtype = fb.subType
//                 })
//             }
//             clockrate = it.clockRate
//             channels = it.channels
//         }
//         mediaTypeInformation.rtpExtensions?.forEach { ext ->
//             ep.addRtpExtension(
//                 RtpExtension(
//                     ext.id,
//                     RtpExtensionType.valueOf(ext.type.toString())
//                 )
//             )
//         }
//         ep.addPayloadType(PayloadTypeUtil.create(pte, MediaType.valueOf(it.mediaType.toString())))
//     }
// }
