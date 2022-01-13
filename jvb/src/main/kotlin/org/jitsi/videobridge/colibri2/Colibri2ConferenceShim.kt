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
import org.jitsi.videobridge.shim.IqProcessingException
import org.jitsi.videobridge.util.PayloadTypeUtil.Companion.create
import org.jitsi.videobridge.xmpp.MediaSourceFactory
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.ParameterPacketExtension
import org.jitsi.xmpp.extensions.jingle.RTPHdrExtPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.jitsi.xmpp.util.IQUtils
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError.Condition

class Colibri2ConferenceShim(
    private val conference: Conference,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger)

    /**
     * @return A pair with an IQ to be sent as a response, and a boolean incicating if the conference needs to be
     * expired.
     */
    fun handleConferenceModifyIQ(conferenceModifyIQ: ConferenceModifyIQ): Pair<IQ, Boolean> = try {
        val responseBuilder =
            ConferenceModifiedIQ.builder(ConferenceModifiedIQ.Builder.createResponse(conferenceModifyIQ))
        var expire = false

        /* TODO: is there any reason we might need to handle Endpoints and Relays in in-message order? */
        for (e in conferenceModifyIQ.endpoints) {
            responseBuilder.addEndpoint(handleColibri2Endpoint(e))
        }
        for (r in conferenceModifyIQ.relays) {
            responseBuilder.addRelay(handleColibri2Relay(r))
        }

        /* Report feedback sources if we haven't reported them yet. */
        if (conferenceModifyIQ.create) {
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
        Pair(IQUtils.createError(conferenceModifyIQ, e.condition, e.message), false)
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
                        addParameter(
                            ParameterPacketExtension().apply {
                                name = "msid"
                                value = "mixedmslabel mixedlabelaudio0"
                            }
                        )
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
                        addParameter(
                            ParameterPacketExtension().apply {
                                name = "msid"
                                value = "mixedmslabel mixedlabelvideo0"
                            }
                        )
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
            conference.createLocalEndpoint(c2endpoint.id, transport.iceControlling)
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

        endpoint.updateAcceptedMediaTypes(
            acceptAudio = endpoint.transceiver.readOnlyStreamInformationStore.rtpPayloadTypes.values.any {
                it.mediaType == MediaType.AUDIO
            },
            acceptVideo = endpoint.transceiver.readOnlyStreamInformationStore.rtpPayloadTypes.values.any {
                it.mediaType == MediaType.VIDEO
            }
        )

        c2endpoint.transport?.iceUdpTransport?.let { endpoint.setTransportInfo(it) }
        if (c2endpoint.create) {
            val transBuilder = Transport.getBuilder()
            transBuilder.setIceUdpExtension(endpoint.describeTransport())
            respBuilder.setTransport(transBuilder.build())
        }

        c2endpoint.sources?.let { sources ->
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
            val newMediaSources = sources.mediaSources.find { it.type == MediaType.VIDEO }?.let {
                MediaSourceFactory.createMediaSources(it.sources, it.ssrcGroups)
            } ?: emptyArray()
            endpoint.mediaSources = newMediaSources
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

            relay = conference.createRelay(c2relay.id, transport.iceControlling, transport.useUniquePort)
        } else {
            relay = conference.getRelay(c2relay.id) ?: throw IqProcessingException(
                // TODO: this should be Condition.item_not_found but this conflicts with some error codes from the Muc.
                Condition.bad_request,
                "Unknown relay ${c2relay.id}"
            )
        }

        c2relay.transport?.iceUdpTransport?.let { relay.setTransportInfo(it) }
        if (c2relay.create) {
            val transBuilder = Transport.getBuilder()
            transBuilder.setIceUdpExtension(relay.describeTransport())
            respBuilder.setTransport(transBuilder.build())
        }

        for (media: Media in c2relay.media) {
            // TODO: support removing payload types/header extensions
            media.payloadTypes.forEach { ptExt ->
                create(ptExt, media.type)?.let { relay.transceiver.addPayloadType(it) }
                    ?: logger.warn("Ignoring unrecognized payload type extension: ${ptExt.toXML()}")
            }

            media.rtpHdrExts.forEach { rtpHdrExt ->
                rtpHdrExt.toRtpExtension()?.let { relay.transceiver.addRtpExtension(it) }
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
                    val descs = MediaSourceFactory.createMediaSources(m.sources, m.ssrcGroups, id, m.id)
                    videoSources.addAll(listOf(*descs))
                } else {
                    logger.warn("Ignoring source ${m.id} in endpoint $id of a relay: unsupported type ${m.type}")
                }
            }
        }
        return Pair(audioSources, videoSources)
    }
}
