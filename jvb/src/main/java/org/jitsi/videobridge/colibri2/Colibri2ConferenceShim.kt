package org.jitsi.videobridge.colibri2

import org.jitsi.nlj.MediaSourceDesc
import org.jitsi.nlj.rtp.RtpExtension
import org.jitsi.nlj.rtp.RtpExtensionType.Companion.createFromUri
import org.jitsi.nlj.rtp.SsrcAssociationType
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.Logger
import org.jitsi.utils.logging2.createChildLogger
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.Relay
import org.jitsi.videobridge.shim.IqProcessingException
import org.jitsi.videobridge.util.PayloadTypeUtil.Companion.create
import org.jitsi.videobridge.xmpp.MediaSourceFactory
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.RTPHdrExtPacketExtension
import org.jitsi.xmpp.extensions.jingle.SourceGroupPacketExtension
import org.jitsi.xmpp.util.IQUtils
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError.Condition
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay

class Colibri2ConferenceShim(
    private val conference: Conference,
    parentLogger: Logger
) {
    private val logger = createChildLogger(parentLogger)

    /**
     * Whether the local SSRCs have been reported in a colibri2 response.
     */
    private var localSsrcsReported = false

    fun handleConferenceModifyIQ(conferenceModifyIQ: ConferenceModifyIQ): IQ = try {
        val responseBuilder =
            ConferenceModifiedIQ.builder(ConferenceModifiedIQ.Builder.createResponse(conferenceModifyIQ))

        /* TODO: rename colibri2.Endpoint and colibri2.Relay so as not to conflict here? */
        /* TODO: is there any reason we might need to handle Endpoints and Relays in in-message order? */
        for (e in conferenceModifyIQ.endpoints) {
            responseBuilder.addEndpoint(handleColibri2Endpoint(e))
        }
        for (r in conferenceModifyIQ.relays) {
            responseBuilder.addRelay(handleColibri2Relay(r))
        }

        /* Report feedback sources if we haven't reported them yet. */
        if (!localSsrcsReported) {
            val feedbackSourcesBuilder = Sources.getBuilder()
            feedbackSourcesBuilder.addMediaSource(
                MediaSource.getBuilder()
                    .setType(MediaType.AUDIO)
                    .addSource(SourcePacketExtension().apply { ssrc = conference.localAudioSsrc })
                    .build()
            )
            feedbackSourcesBuilder.addMediaSource(
                MediaSource.getBuilder()
                    .setType(MediaType.VIDEO)
                    .addSource(SourcePacketExtension().apply { ssrc = conference.localVideoSsrc })
                    .build()
            )

            responseBuilder.setSources(feedbackSourcesBuilder.build())
            localSsrcsReported = true
        }

        responseBuilder.build()
    } catch (e: IqProcessingException) {
        // Item not found conditions are assumed to be less critical, as they often happen in case a request
        // arrives late for an expired endpoint.
        if (Condition.item_not_found == e.condition) {
            logger.warn("Error processing conference-modify IQ: $e")
        } else {
            logger.error("Error processing conference-modify IQ: $e")
        }
        IQUtils.createError(conferenceModifyIQ, e.condition, e.message)
    }

    /**
     * Process a colibri2 Endpoint in a conference-modify, return the response to be put in
     * the conference-modified.
     */
    @Throws(IqProcessingException::class)
    private fun handleColibri2Endpoint(eDesc: Colibri2Endpoint): Colibri2Endpoint {
        val id = eDesc.id ?: throw IqProcessingException(Condition.bad_request, "endpoint id is null")
        val t = eDesc.transport
        val respBuilder = Colibri2Endpoint.getBuilder()
        respBuilder.setId(eDesc.id)
        if (eDesc.expire) {
            conference.getLocalEndpoint(id)?.expire()
            respBuilder.setExpire(true)
            return respBuilder.build()
        }

        val iceControlling: Boolean = if (t != null) {
            /* TODO: if a message seems to be creating an endpoint but it doesn't have a <transport> section,
             *  something has almost certainly gone wrong; return an error in this case.
             */
            t.initiator == true
        } else {
            false
        }

        // TODO handle the create/update case separately.
        val ep: Endpoint = getOrCreateEndpoint(id, iceControlling)
        for (m: Media in eDesc.media) {
            /* TODO: organize these data structures more sensibly for Colibri2 */
            m.payloadTypes.forEach {
                val pt = create(it, m.type)
                if (pt != null) {
                    ep.addPayloadType(pt)
                } else {
                    logger.warn("Ignoring unrecognized payload type extension: ${it.toXML()}")
                }
            }

            m.rtpHdrExts.forEach {
                val rtpExtension = it.toRtpExtension()
                if (rtpExtension != null) {
                    ep.addRtpExtension(rtpExtension)
                } else {
                    logger.warn("Ignoring unrecognized RTP header extension: ${it.toXML()}")
                }
            }

            /* No need to put media in conference-modified. */
        }

        if (t != null) {
            val udpTransportPacketExtension = t.iceUdpTransport
            if (udpTransportPacketExtension != null) {
                ep.setTransportInfo(udpTransportPacketExtension)
            }
        }
        if (!ep.transportDescribed) {
            val transBuilder = Transport.getBuilder()
            transBuilder.setIceUdpExtension(ep.describeTransport())
            respBuilder.setTransport(transBuilder.build())
        }

        eDesc.sources?.let { sources ->
            sources.mediaSources.forEach { mediaSource ->
                mediaSource.sources.forEach {
                    ep.addReceiveSsrc(it.ssrc, mediaSource.type)
                }
                mediaSource.ssrcGroups.forEach { sourceGroup ->
                    if (sourceGroup.sources.size < 2) {
                        logger.warn("Ignoring source group with <2 sources: ${sourceGroup.toXML()}")
                    } else {

                        val primarySsrc: Long = sourceGroup.sources[0].ssrc
                        val secondarySsrc: Long = sourceGroup.sources[1].ssrc

                        val ssrcAssociationType = sourceGroup.semantics.parseAssociationType()
                        if (ssrcAssociationType != null &&
                            ssrcAssociationType != SsrcAssociationType.SIM
                        ) {
                            ep.conference.encodingsManager
                                .addSsrcAssociation(
                                    ep.id,
                                    primarySsrc,
                                    secondarySsrc,
                                    ssrcAssociationType
                                )
                        }
                    }
                }
            }

            // Assume a message can only contain on source per media type.
            // If "sources" was signaled, but it didn't contain any video sources, clear the endpoint's video sources
            val newMediaSources = sources.mediaSources.find { it.type == MediaType.VIDEO }?.let {
                MediaSourceFactory.createMediaSources(it.sources, it.ssrcGroups)
            } ?: emptyArray()
            ep.mediaSources = newMediaSources
        }

        return respBuilder.build()
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
     * @return the endpoint with [endpointId], creating it if it doesn't exist.
     */
    private fun getOrCreateEndpoint(endpointId: String, iceControlling: Boolean): Endpoint =
        conference.getLocalEndpoint(endpointId) ?: conference.createLocalEndpoint(endpointId, iceControlling)
    private fun getOrCreateRelay(relayId: String, iceControlling: Boolean, useUniquePort: Boolean): Relay =
        conference.getRelay(relayId) ?: conference.createRelay(relayId, iceControlling, useUniquePort)

    /**
     * Process a colibri2 Relay in a conference-modify, return the response to be put in
     * the conference-modified.
     */
    @Throws(IqProcessingException::class)
    private fun handleColibri2Relay(rDesc: Colibri2Relay): Colibri2Relay {
        /* TODO: enforce this in xmpp-extensions? */
        val id = rDesc.id ?: throw IqProcessingException(Condition.bad_request, "Missing Relay ID")
        val t = rDesc.transport
        val respBuilder = Colibri2Relay.getBuilder()
        respBuilder.setId(id)
        if (rDesc.expire) {
            conference.getRelay(id)?.expire()
            respBuilder.setExpire(true)
            return respBuilder.build()
        }
        val iceControlling: Boolean
        val useUniquePort: Boolean
        /* TODO: if a message seems to be creating a relay but it doesn't have a <transport> section,
         *  something has almost certainly gone wrong; return an error in this case.
         */if (t != null) {
            iceControlling = java.lang.Boolean.TRUE == t.initiator
            useUniquePort = java.lang.Boolean.TRUE == t.useUniquePort
        } else {
            iceControlling = false
            useUniquePort = false
        }
        val r: Relay = getOrCreateRelay(id, iceControlling, useUniquePort)
        if (t != null) {
            val udpTransportPacketExtension = t.iceUdpTransport
            if (udpTransportPacketExtension != null) {
                r.setTransportInfo(udpTransportPacketExtension)
            }
        }
        if (!r.transportDescribed) {
            val transBuilder = Transport.getBuilder()
            transBuilder.setIceUdpExtension(r.describeTransport())
            respBuilder.setTransport(transBuilder.build())
        }
        rDesc.endpoints?.endpoints?.forEach { e ->
            val eId = e.id ?: throw IqProcessingException(Condition.bad_request, "relay endpoint with id=null")

            if (e.expire) {
                r.removeRemoteEndpoint(eId)
            } else {
                val sources = e.parseSourceDescs()
                r.addRemoteEndpoint(eId, sources.first, sources.second)
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
                        if (m.sources.size > 1) {
                            logger.warn(
                                "Audio source ${m.id} in endpoint $id of a relay has ${m.sources.size} " +
                                    "SSRCs, ignoring all but first"
                            )
                        }
                        val audioSource = AudioSourceDesc(m.sources[0].ssrc, id, m.id)
                        audioSources.add(audioSource)
                    }
                } else if (m.type == MediaType.VIDEO) {
                    val descs = MediaSourceFactory.createMediaSources(m.sources, m.ssrcGroups, id, m.id)
                    videoSources.addAll(listOf(*descs))
                } else {
                    logger.warn("Ignoring source ${m.id} in endpoint $id of a relay: unsupported type ${m.type}")
                }
            }
        }
        return Pair(listOf(), listOf())
    }
}
