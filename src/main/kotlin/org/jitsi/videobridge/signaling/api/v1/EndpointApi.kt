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

package org.jitsi.videobridge.signaling.api.v1

import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.patch
import io.ktor.routing.post
import io.ktor.routing.route
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.utils.MediaType
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.api.types.BridgeSourceInformation
import org.jitsi.videobridge.api.types.EndpointCreateRequest
import org.jitsi.videobridge.api.types.EndpointCreateResponse
import org.jitsi.videobridge.api.types.EndpointModifyRequest
import org.jitsi.videobridge.api.types.IceCandidate
import org.jitsi.videobridge.api.types.IceRole
import org.jitsi.videobridge.api.types.IceUdpTransportInformation
import org.jitsi.videobridge.api.types.MediaTypeInformation
import org.jitsi.videobridge.api.types.TransportInformation
import org.jitsi.videobridge.signaling.api.CONF_KEY
import org.jitsi.videobridge.signaling.api.getConfId
import org.jitsi.videobridge.signaling.api.getEpId
import org.jitsi.videobridge.signaling.api.validateConference
import org.jitsi.videobridge.util.PayloadTypeUtil
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.WebSocketPacketExtension
import org.jitsi.xmpp.extensions.jingle.DtlsFingerprintPacketExtension
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension
import org.jitsi.xmpp.extensions.jingle.RtcpFbPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension

fun Route.endpointsApi(videobridge: Videobridge) {
    route("endpoints/{epId}") {
        validateConference(videobridge, EndpointCreateResponse::Error)
        post {
            val epCreateReq = call.receive<EndpointCreateRequest>()
            println("Conf ${getConfId()}, ep ${getEpId()}")
            println("attributes: ${call.attributes}")
            println("got conf ${call.attributes[CONF_KEY]}")
            val conf = call.attributes[CONF_KEY]
            val ep = conf.createLocalEndpoint(getEpId(), epCreateReq.iceRole == IceRole.CONTROLLING)

            // Media information may be known up-front
            updateMediaInformation(ep, epCreateReq.mediaTypeInformation)
            ep.setAcceptsAudio(true)
            ep.setAcceptsVideo(true)

            val channelBundle = ColibriConferenceIQ.ChannelBundle(getEpId())
            ep.describe(channelBundle)

            val resp = EndpointCreateResponse.Success(
                TransportInformation(
                    IceUdpTransportInformation(
                        ufrag = channelBundle.transport.ufrag,
                        password = channelBundle.transport.password,
                        candidates = channelBundle.transport.candidateList.map {
                            IceCandidate(
                                component = it.component,
                                foundation = it.foundation,
                                generation = it.generation,
                                id = it.id,
                                ip = it.ip,
                                network = it.network,
                                port = it.port,
                                priority = it.priority.toLong(),
                                protocol = it.protocol,
                                relAddr = it.relAddr,
                                relPort = it.relPort,
                                type = it.type.toString()
                            )
                        },
                        dtlsFingerprint = channelBundle.transport.getFirstChildOfType(DtlsFingerprintPacketExtension::class.java)?.fingerprint,
                        dtlsHash = channelBundle.transport.getFirstChildOfType(DtlsFingerprintPacketExtension::class.java)?.hash
                    ),
                    websocketUrl = channelBundle.transport.getFirstChildOfType(WebSocketPacketExtension::class.java)?.url
                ),
                BridgeSourceInformation(
                    videoSsrc = Videobridge.RANDOM.nextLong() and 0xffffffffL,
                    audioSsrc = Videobridge.RANDOM.nextLong() and 0xffffffffL
                )
            )
            call.respond(resp)
        }
        patch {
            val epModifyReq = call.receive<EndpointModifyRequest>()

            val conf = call.attributes[CONF_KEY]

            val transportInfo = IceUdpTransportPacketExtension()
            transportInfo.password = epModifyReq.transportInformation?.iceUdpTransportInformation?.password
            transportInfo.ufrag = epModifyReq.transportInformation?.iceUdpTransportInformation?.ufrag
            transportInfo.addChildExtension(DtlsFingerprintPacketExtension().apply {
                this.fingerprint = epModifyReq.transportInformation?.iceUdpTransportInformation?.dtlsFingerprint
                this.hash = epModifyReq.transportInformation?.iceUdpTransportInformation?.dtlsHash
                // TODO:
                this.setup = "passive"
            })
            val ep = conf.getEndpoint(getEpId()!!)

            (ep as Endpoint).setTransportInfo(transportInfo)
            updateMediaInformation(ep, epModifyReq.mediaTypeInformation)
            call.respond(HttpStatusCode.OK)
        }
    }
//    post<Endpoint> { epParams ->
//
//        // Create a new endpoint with any information give
//        val epInfo = call.receive<EndpointCreateRequest>()
//
//        // TODO: can we automatically get this somehow?
//        val conference = videobridge.getConference(epParams.parent.id, JidCreate.bareFrom("focus"))
//
//        println("Conf ${epParams.parent.id}, ep ${epParams.epId}")
//        println(epInfo)
//        // TODO: next is to wire this call up for real to create an endpoint
//        call.respond(EndpointCreateResponse.Error("blah"))
//    }
//    route("endpoints/{epId}") {
//        post<Endpoint> { endpoint ->
//            // Create a new endpoint with any information give
//            val epInfo = call.receive<EndpointCreateRequest>()
//            println("Conf ${call.parameters["confGid"]}, ep ${endpoint.epId}")
//            println(epInfo)
//            // TODO: next is to wire this call up for real to create an endpoint
//            call.respond(EndpointCreateResponse.Error("blah"))
//        }
//        route("rtp_extensions") {
//            post {
//                // Modify the set of rtp extensions for this endpoint.
//                val epId = call.parameters["epId"]
//                println("epId: $epId")
//                val post = call.receive<List<RtpExtension>>()
//                println(post)
//                call.respond(HttpStatusCode.OK)
//            }
//        }
//        route("payload_types") {
//            post {
//                // Modify the set of payload types for this endpoint
//                val post = call.receive<List<RtpExtension>>()
//                call.respond(HttpStatusCode.OK)
//            }
//        }
//        route("source_information") {
//            post {
//                // Modify the source information for this endpoint
//                val post = call.receive<SourceInformation>()
//                call.respond(HttpStatusCode.OK)
//            }
//        }
//        delete {
//            // Remove an endpoint
//        }
//    }
}

private fun updateMediaInformation(ep: AbstractEndpoint, mediaTypeInformation: MediaTypeInformation?) {
    mediaTypeInformation ?: return
    (ep as Endpoint).recreateMediaStreamTracks2(mediaTypeInformation.sourceInformation?.sources?.toList(), mediaTypeInformation.sourceInformation?.sourceGroups?.toList())
    mediaTypeInformation.sourceInformation?.sources?.forEach { src ->
        ep.addReceiveSsrc(src.ssrc, MediaType.valueOf(src.mediaType.toString()))
    }
    mediaTypeInformation.sourceInformation?.sourceGroups?.forEach { srcGroup ->
        val ssrcAssociationType = when {
            srcGroup.semantics.equals(SourceGroupPacketExtension.SEMANTICS_FID, ignoreCase = true) -> SsrcAssociationType.RTX
            srcGroup.semantics.equals(SourceGroupPacketExtension.SEMANTICS_SIMULCAST, ignoreCase = true) -> SsrcAssociationType.SIM
            srcGroup.semantics.equals(SourceGroupPacketExtension.SEMANTICS_FEC, ignoreCase = true) -> SsrcAssociationType.FEC
            else -> {
                println("Unknown semantics: ${srcGroup.semantics}")
                return@forEach
            }
        }
        if (ssrcAssociationType == SsrcAssociationType.SIM) {
            return@forEach
        }

        ep.conference.encodingsManager.addSsrcAssociation(
            ep.id,
            srcGroup.sources[0].ssrc,
            srcGroup.sources[1].ssrc,
            ssrcAssociationType
        )
    }

    // TODO: for now we create a PayloadTypePacketExtension and use that to create a PayloadType
    mediaTypeInformation.payloadTypes?.forEach {
        val pte = PayloadTypePacketExtension().apply {
            setId(it.pt.toInt())
            name = it.encoding.toString()
            it.params.forEach { param ->
                addParameter(ParameterPacketExtension(param.key, param.value))
            }
            it.feedback.forEach { fb ->
                addRtcpFeedbackType(RtcpFbPacketExtension().apply {
                    feedbackType = fb.type
                    feedbackSubtype = fb.subType
                })
            }
            clockrate = it.clockRate
            channels = it.channels
        }
        mediaTypeInformation.rtpExtensions?.forEach { ext ->
            ep.addRtpExtension(
                RtpExtension(
                    ext.id,
                    RtpExtensionType.valueOf(ext.type.toString())
                )
            )
        }
        ep.addPayloadType(PayloadTypeUtil.create(pte, MediaType.valueOf(it.mediaType.toString())))
    }
}
