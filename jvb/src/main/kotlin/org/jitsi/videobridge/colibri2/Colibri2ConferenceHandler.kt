/*
 * Copyright @ 2022 - Present, 8x8 Inc
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
package org.jitsi.videobridge.colibri2

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType.Companion.createFromUri
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.nlj.util.LocalSsrcAssociation
import org.jitsi.nlj.util.SsrcAssociation
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.Relay
import org.jitsi.videobridge.relay.RelayConfig
import org.jitsi.videobridge.sctp.SctpConfig
import org.jitsi.videobridge.sctp.SctpManager
import org.jitsi.videobridge.util.PayloadTypeUtil.Companion.create
import org.jitsi.videobridge.websocket.config.WebsocketServiceConfig
import org.jitsi.videobridge.xmpp.MediaSourceFactory
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.colibri2.Capability
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sctp
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.RTPHdrExtPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.jitsi.xmpp.util.createError
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError.Condition
import org.jivesoftware.smackx.muc.MUCRole

class Colibri2ConferenceHandler(
    private val conference: Conference,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger)

    /**
     * @return A pair with an IQ to be sent as a response, and a boolean indicating if the conference needs to be
     * expired.
     */
    fun handleConferenceModifyIQ(conferenceModifyIQ: ConferenceModifyIQ): Pair<IQ, Boolean> = try {
        val responseBuilder =
            ConferenceModifiedIQ.builder(ConferenceModifiedIQ.Builder.createResponse(conferenceModifyIQ))
        var expire = conferenceModifyIQ.expire.also {
            if (it) logger.info("Received request to expire conference.")
        }

        /* TODO: is there any reason we might need to handle Endpoints and Relays in in-message order? */
        for (e in conferenceModifyIQ.endpoints) {
            responseBuilder.addEndpoint(handleColibri2Endpoint(e))
        }
        for (r in conferenceModifyIQ.relays) {
            if (!RelayConfig.config.enabled) {
                throw IqProcessingException(Condition.feature_not_implemented, "Octo is disable in configuration.")
            }
            if (!WebsocketServiceConfig.config.enabled) {
                logger.warn(
                    "Can not use a colibri2 relay, because colibri web sockets are not enabled. See " +
                        "https://github.com/jitsi/jitsi-videobridge/blob/master/doc/octo.md"
                )
                throw UnsupportedOperationException("Colibri websockets need to be enabled to use a colibri2 relay.")
            }
            responseBuilder.addRelay(handleColibri2Relay(r))
        }

        // Include feedback sources with any "create conference" or "create endpoint" request. This allows async
        // handling of responses in jicofo without potentially losing the feedback sources.
        // TODO: perhaps colibri clients should be required to process responses in order?
        if (conferenceModifyIQ.create || conferenceModifyIQ.endpoints.any { it.create }) {
            responseBuilder.setSources(buildFeedbackSources())
        }

        if (!conferenceModifyIQ.create && conference.endpointCount == 0 && conference.relayCount == 0) {
            logger.info("All endpoints and relays removed, expiring.")
            expire = true
        }

        Pair(responseBuilder.build(), expire)
    } catch (e: IqProcessingException) {
        // Item not found conditions are assumed to be less critical, as they often happen in case a request
        // arrives late for an expired endpoint.
        if (Condition.item_not_found == e.condition) {
            logger.warn("Error processing conference-modify IQ: $e")
        } else {
            logger.error("Error processing conference-modify IQ: $e")
        }
        Pair(createError(conferenceModifyIQ, e.condition, e.message), false)
    }

    private fun buildFeedbackSources(): Sources = Sources.getBuilder().apply {
        addMediaSource(
            MediaSource.getBuilder()
                .setType(MediaType.AUDIO)
                .setId("jvb-a0")
                .addSource(
                    SourcePacketExtension().apply {
                        ssrc = conference.localAudioSsrc
                        name = "jvb-a0"
                    }
                )
                .build()
        )
        addMediaSource(
            MediaSource.getBuilder()
                .setType(MediaType.VIDEO)
                .setId("jvb-v0")
                .addSource(
                    SourcePacketExtension().apply {
                        ssrc = conference.localVideoSsrc
                        name = "jvb-v0"
                    }
                )
                .build()
        )
    }.build()

    /**
     * Process a colibri2 Endpoint in a conference-modify, return the response to be put in
     * the conference-modified.
     */
    @Throws(IqProcessingException::class)
    private fun handleColibri2Endpoint(c2endpoint: Colibri2Endpoint): Colibri2Endpoint {
        val respBuilder = Colibri2Endpoint.getBuilder().apply { setId(c2endpoint.id) }
        if (c2endpoint.expire) {
            conference.getLocalEndpoint(c2endpoint.id)?.expire()
            respBuilder.setExpire(true)
            return respBuilder.build()
        }

        val endpoint = if (c2endpoint.create) {
            if (conference.getLocalEndpoint(c2endpoint.id) != null) {
                throw IqProcessingException(Condition.conflict, "Endpoint with ID ${c2endpoint.id} already exists")
            }
            val transport = c2endpoint.transport ?: throw IqProcessingException(
                Condition.bad_request,
                "Attempt to create endpoint ${c2endpoint.id} with no <transport>"
            )
            val sourceNames = c2endpoint.hasCapability(Capability.CAP_SOURCE_NAME_SUPPORT)
            val ssrcRewriting = sourceNames && c2endpoint.hasCapability(Capability.CAP_SSRC_REWRITING_SUPPORT)
            conference.createLocalEndpoint(
                c2endpoint.id,
                transport.iceControlling,
                sourceNames,
                ssrcRewriting,
                c2endpoint.mucRole == MUCRole.visitor
            ).apply {
                c2endpoint.statsId?.let {
                    statsId = it
                }
                transport.sctp?.let { sctp ->
                    if (!SctpConfig.config.enabled) {
                        throw IqProcessingException(
                            Condition.feature_not_implemented,
                            "SCTP support is not configured"
                        )
                    }
                    if (sctp.role != null && sctp.role != Sctp.Role.SERVER) {
                        throw IqProcessingException(
                            Condition.feature_not_implemented,
                            "Unsupported SCTP role: ${sctp.role}"
                        )
                    }
                    if (sctp.port != null && sctp.port != SctpManager.DEFAULT_SCTP_PORT) {
                        throw IqProcessingException(
                            Condition.bad_request,
                            "Specific SCTP port requested, not supported."
                        )
                    }

                    createSctpConnection()
                }
            }
        } else {
            conference.getLocalEndpoint(c2endpoint.id) ?: throw IqProcessingException(
                // TODO: this should be Condition.item_not_found but this conflicts with some error codes from the Muc.
                Condition.bad_request,
                "Unknown endpoint ${c2endpoint.id}"
            )
        }

        for (media in c2endpoint.media) {
            // TODO: support removing payload types/header extensions
            media.payloadTypes.forEach { ptExt ->
                create(ptExt, media.type)?.let { endpoint.addPayloadType(it) }
                    ?: logger.warn("Ignoring unrecognized payload type extension: ${ptExt.toXML()}")
            }

            media.rtpHdrExts.forEach { rtpHdrExt ->
                rtpHdrExt.toRtpExtension()?.let { endpoint.addRtpExtension(it) }
                    ?: logger.warn("Ignoring unrecognized RTP header extension: ${rtpHdrExt.toXML()}")
            }

            /* No need to put media in conference-modified. */
        }

        endpoint.acceptAudio = endpoint.transceiver.readOnlyStreamInformationStore.rtpPayloadTypes.values.any {
            it.mediaType == MediaType.AUDIO
        }
        endpoint.acceptVideo = endpoint.transceiver.readOnlyStreamInformationStore.rtpPayloadTypes.values.any {
            it.mediaType == MediaType.VIDEO
        }

        c2endpoint.transport?.iceUdpTransport?.let { endpoint.setTransportInfo(it) }
        if (c2endpoint.create) {
            val transBuilder = Transport.getBuilder()
            transBuilder.setIceUdpExtension(endpoint.describeTransport())
            if (c2endpoint.transport?.sctp != null) {
                transBuilder.setSctp(
                    Sctp.Builder()
                        .setPort(SctpManager.DEFAULT_SCTP_PORT)
                        .setRole(Sctp.Role.SERVER)
                        .build()
                )
            }
            respBuilder.setTransport(transBuilder.build())
        }

        c2endpoint.sources?.let { sources ->
            if (endpoint.visitor && sources.mediaSources.isNotEmpty()) {
                throw IqProcessingException(
                    Condition.bad_request,
                    "Attempt to set sources for visitor endpoint ${c2endpoint.id}"
                )
            }

            sources.mediaSources.forEach { mediaSource ->
                mediaSource.sources.forEach {
                    endpoint.addReceiveSsrc(it.ssrc, mediaSource.type)
                }
                // TODO: remove any old associations for this endpoint
                mediaSource.ssrcGroups.mapNotNull { it.toSsrcAssociation() }.forEach {
                    addSsrcAssociation(endpoint.id, it)
                }
            }

            // Assume a message can only contain one source per media type.
            // If "sources" was signaled, but it didn't contain any video sources, clear the endpoint's video sources
            val newMediaSources = sources.mediaSources.filter { it.type == MediaType.VIDEO }.mapNotNull {
                MediaSourceFactory.createMediaSource(it.sources, it.ssrcGroups, c2endpoint.id, it.id)
            }
            endpoint.mediaSources = newMediaSources.toTypedArray()

            val audioSources: ArrayList<AudioSourceDesc> = ArrayList()
            sources.mediaSources.filter { it.type == MediaType.AUDIO }.forEach {
                it.sources.forEach { s ->
                    audioSources.add(AudioSourceDesc(s.ssrc, c2endpoint.id, it.id))
                }
            }
            endpoint.audioSources = audioSources
        }

        c2endpoint.forceMute?.let {
            endpoint.updateForceMute(it.audio, it.video)
        }

        return respBuilder.build()
    }

    private fun addSsrcAssociation(endpointId: String, ssrcAssociation: SsrcAssociation) {
        conference.encodingsManager.addSsrcAssociation(
            endpointId,
            ssrcAssociation.primarySsrc,
            ssrcAssociation.secondarySsrc,
            ssrcAssociation.type
        )
    }

    private fun SourceGroupPacketExtension.toSsrcAssociation(): SsrcAssociation? {
        if (sources.size < 2) {
            logger.warn("Ignoring source group with <2 sources: ${toXML()}")
            return null
        }

        val type = semantics.parseAssociationType() ?: return null
        if (type == SsrcAssociationType.SIM) return null

        return LocalSsrcAssociation(sources[0].ssrc, sources[1].ssrc, type)
    }

    private fun String.parseAssociationType(): SsrcAssociationType? = when {
        this.equals(SourceGroupPacketExtension.SEMANTICS_FID, ignoreCase = true) -> SsrcAssociationType.RTX
        this.equals(SourceGroupPacketExtension.SEMANTICS_SIMULCAST, ignoreCase = true) -> SsrcAssociationType.SIM
        this.equals(SourceGroupPacketExtension.SEMANTICS_FEC, ignoreCase = true) -> SsrcAssociationType.FEC
        else -> null
    }

    private fun RTPHdrExtPacketExtension.toRtpExtension(): RtpExtension? {
        val type = createFromUri(uri.toString()) ?: return null
        return RtpExtension(java.lang.Byte.valueOf(id), type)
    }

    /**
     * Process a colibri2 Relay in a conference-modify, return the response to be put in
     * the conference-modified.
     */
    @Throws(IqProcessingException::class)
    private fun handleColibri2Relay(c2relay: Colibri2Relay): Colibri2Relay {
        val respBuilder = Colibri2Relay.getBuilder()
        respBuilder.setId(c2relay.id)
        if (c2relay.expire) {
            conference.getRelay(c2relay.id)?.expire()
            respBuilder.setExpire(true)
            return respBuilder.build()
        }

        val relay: Relay
        if (c2relay.create) {
            if (conference.getRelay(c2relay.id) != null) {
                throw IqProcessingException(Condition.conflict, "Relay with ID ${c2relay.id} already exists")
            }
            val transport = c2relay.transport ?: throw IqProcessingException(
                Condition.bad_request,
                "Attempt to create relay ${c2relay.id} with no <transport>"
            )

            relay = conference.createRelay(
                c2relay.id,
                c2relay.meshId,
                transport.iceControlling,
                transport.useUniquePort
            )
        } else {
            relay = conference.getRelay(c2relay.id) ?: throw IqProcessingException(
                // TODO: this should be Condition.item_not_found but this conflicts with some error codes from the Muc.
                Condition.bad_request,
                "Unknown relay ${c2relay.id}"
            )
        }

        if (c2relay.transport?.sctp != null) throw IqProcessingException(
            Condition.feature_not_implemented,
            "SCTP is not supported for relays."
        )

        c2relay.transport?.iceUdpTransport?.let { relay.setTransportInfo(it) }
        if (c2relay.create) {
            val transBuilder = Transport.getBuilder()
            transBuilder.setIceUdpExtension(relay.describeTransport())
            respBuilder.setTransport(transBuilder.build())
        }

        for (media: Media in c2relay.media) {
            // TODO: support removing payload types/header extensions
            media.payloadTypes.forEach { ptExt ->
                create(ptExt, media.type)?.let { relay.addPayloadType(it) }
                    ?: logger.warn("Ignoring unrecognized payload type extension: ${ptExt.toXML()}")
            }

            media.rtpHdrExts.forEach { rtpHdrExt ->
                rtpHdrExt.toRtpExtension()?.let { relay.addRtpExtension(it) }
                    ?: logger.warn("Ignoring unrecognized RTP header extension: ${rtpHdrExt.toXML()}")
            }

            /* No need to put media in conference-modified. */
        }

        c2relay.endpoints?.endpoints?.forEach { endpoint ->
            if (endpoint.expire) {
                relay.removeRemoteEndpoint(endpoint.id)
            } else {
                val sources = endpoint.parseSourceDescs()
                if (endpoint.create) {
                    relay.addRemoteEndpoint(endpoint.id, endpoint.statsId, sources.first, sources.second)
                } else {
                    relay.updateRemoteEndpoint(endpoint.id, sources.first, sources.second)
                }

                // TODO: remove any old associations for this endpoint
                endpoint.sources?.mediaSources?.forEach { mediaSource ->
                    mediaSource.ssrcGroups.mapNotNull { it.toSsrcAssociation() }.forEach {
                        addSsrcAssociation(endpoint.id, it)
                    }
                }
            }
        }

        /* TODO: handle the rest of the relay's fields: feedback sources. */
        return respBuilder.build()
    }

    private fun Colibri2Endpoint.parseSourceDescs(): Pair<List<AudioSourceDesc>, List<MediaSourceDesc>> {
        val audioSources: MutableList<AudioSourceDesc> = ArrayList()
        val videoSources: MutableList<MediaSourceDesc> = ArrayList()
        sources?.let {
            it.mediaSources.forEach { m ->
                if (m.type == MediaType.AUDIO) {
                    if (m.sources.isEmpty()) {
                        logger.warn("Ignoring audio source ${m.id} in endpoint $id of a relay (no SSRCs): ${toXML()}")
                    } else {
                        m.sources.forEach { audioSources.add(AudioSourceDesc(it.ssrc, id, m.id)) }
                    }
                } else if (m.type == MediaType.VIDEO) {
                    val desc = MediaSourceFactory.createMediaSource(m.sources, m.ssrcGroups, id, m.id)
                    if (desc != null) {
                        videoSources.add(desc)
                    }
                } else {
                    logger.warn("Ignoring source ${m.id} in endpoint $id of a relay: unsupported type ${m.type}")
                }
            }
        }
        return Pair(audioSources, videoSources)
    }
}
