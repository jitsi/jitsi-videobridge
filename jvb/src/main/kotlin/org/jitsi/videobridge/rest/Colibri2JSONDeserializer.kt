/*
 * Copyright @ 2015 - Present, 8x8 Inc
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
package org.jitsi.videobridge.rest

import org.jitsi.utils.MediaType
import org.jitsi.xmpp.extensions.colibri2.AbstractConferenceEntity
import org.jitsi.xmpp.extensions.colibri2.AbstractConferenceModificationIQ
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Endpoints
import org.jitsi.xmpp.extensions.colibri2.ForceMute
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sctp
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.jivesoftware.smack.packet.IQ
import org.json.simple.JSONArray
import org.json.simple.JSONObject

object Colibri2JSONDeserializer {
    private fun deserializeMedia(media: JSONObject): Media {
        return Media.getBuilder().apply {
            media[Media.TYPE_ATTR_NAME]?.let {
                if (it is String) { setType(MediaType.parseString(it)) }
            }

            media[JSONSerializer.PAYLOAD_TYPES]?.let { payloadTypes ->
                if (payloadTypes is JSONArray) {
                    JSONDeserializer.deserializePayloadTypes(payloadTypes).forEach { addPayloadType(it) }
                }
            }

            media[JSONSerializer.RTP_HEADER_EXTS]?.let { rtpHdrExts ->
                if (rtpHdrExts is JSONArray) {
                    JSONDeserializer.deserializeHeaderExtensions(rtpHdrExts).forEach { addRtpHdrExt(it) }
                }
            }
        }.build()
    }

    private fun deserializeSctp(sctp: JSONObject): Sctp {
        return Sctp.Builder().apply {
            sctp[Sctp.ROLE_ATTR_NAME]?.let {
                if (it is String) { setRole(Sctp.Role.parseString(it)) }
            }

            sctp[Sctp.PORT_ATTR_NAME]?.let {
                if (it is Number) { setPort(it.toInt()) }
            }
        }.build()
    }

    private fun deserializeTransport(transport: JSONObject): Transport {
        return Transport.getBuilder().apply {
            transport[Transport.ICE_CONTROLLING_ATTR_NAME]?.let {
                if (it is Boolean) { setIceControlling(it) }
            }

            transport[Transport.USE_UNIQUE_PORT_ATTR_NAME]?.let {
                if (it is Boolean) { setUseUniquePort(it) }
            }

            transport[IceUdpTransportPacketExtension.ELEMENT]?.let {
                if (it is JSONObject) { setIceUdpExtension(JSONDeserializer.deserializeTransport(it)) }
            }

            transport[Sctp.ELEMENT]?.let {
                if (it is JSONObject) { setSctp(deserializeSctp(it)) }
            }
        }.build()
    }

    private fun deserializeMediaSource(mediaSource: JSONObject): MediaSource {
        return MediaSource.getBuilder().apply {
            mediaSource[MediaSource.TYPE_ATTR_NAME]?.let {
                if (it is String) { setType(MediaType.parseString(it)) }
            }

            mediaSource[MediaSource.ID_NAME]?.let {
                if (it is String) { setId(it) }
            }

            mediaSource[JSONSerializer.SOURCES]?.let { sources ->
                if (sources is JSONArray) {
                    sources.forEach { addSource(JSONDeserializer.deserializeSource(it)) }
                }
            }

            mediaSource[JSONSerializer.SOURCE_GROUPS]?.let { sourceGroups ->
                if (sourceGroups is JSONArray) {
                    sourceGroups.forEach { addSsrcGroup(JSONDeserializer.deserializeSourceGroup(it)) }
                }
            }
        }.build()
    }

    private fun deserializeMedias(medias: JSONArray): Collection<Media> {
        return ArrayList<Media>().apply {
            medias.forEach {
                if (it is JSONObject) { add(deserializeMedia(it)) }
            }
        }
    }

    private fun deserializeSources(sources: JSONArray): Sources {
        return Sources.getBuilder().apply {
            sources.forEach {
                if (it is JSONObject) {
                    addMediaSource(deserializeMediaSource(it))
                }
            }
        }.build()
    }

    private fun deserializeAbstractConferenceEntityToBuilder(
        entity: JSONObject,
        builder: AbstractConferenceEntity.Builder
    ) {
        entity[AbstractConferenceEntity.ID_ATTR_NAME]?.let {
            if (it is String) { builder.setId(it) }
        }

        entity[AbstractConferenceEntity.CREATE_ATTR_NAME]?.let {
            if (it is Boolean) { builder.setCreate(it) }
        }

        entity[AbstractConferenceEntity.EXPIRE_ATTR_NAME]?.let {
            if (it is Boolean) { builder.setExpire(it) }
        }

        entity[Colibri2JSONSerializer.MEDIA_LIST]?.let { medias ->
            if (medias is JSONArray) {
                deserializeMedias(medias).forEach { builder.addMedia(it) }
            }
        }

        entity[Transport.ELEMENT]?.let {
            if (it is JSONObject) { builder.setTransport(deserializeTransport(it)) }
        }

        entity[Sources.ELEMENT]?.let {
            if (it is JSONArray) { builder.setSources(deserializeSources(it)) }
        }
    }

    private fun deserializeForceMute(forceMute: JSONObject): ForceMute {
        val audio = forceMute[ForceMute.AUDIO_ATTR_NAME]
        val video = forceMute[ForceMute.VIDEO_ATTR_NAME]

        return ForceMute(
            if (audio is Boolean) { audio } else { ForceMute.AUDIO_DEFAULT },
            if (video is Boolean) { video } else { ForceMute.VIDEO_DEFAULT }
        )
    }

    private fun deserializeEndpoint(endpoint: JSONObject): Colibri2Endpoint {
        return Colibri2Endpoint.getBuilder().apply {
            deserializeAbstractConferenceEntityToBuilder(endpoint, this)

            endpoint[Colibri2Endpoint.STATS_ID_ATTR_NAME]?.let {
                if (it is String) { setStatsId(it) }
            }

            endpoint[ForceMute.ELEMENT]?.let {
                if (it is JSONObject) { setForceMute(deserializeForceMute(it)) }
            }

            endpoint[Colibri2JSONSerializer.CAPABILITIES_LIST]?.let { capabilities ->
                if (capabilities is JSONArray) {
                    capabilities.forEach { if (it is String) { addCapability(it) } }
                }
            }
        }.build()
    }

    private fun deserializeRelay(relay: JSONObject): Colibri2Relay {
        return Colibri2Relay.getBuilder().apply {
            deserializeAbstractConferenceEntityToBuilder(relay, this)

            relay[JSONSerializer.ENDPOINTS]?.let { endpoints ->
                if (endpoints is JSONArray) {
                    setEndpoints(
                        Endpoints.getBuilder().apply {
                            deserializeEndpoints(endpoints).forEach { addEndpoint(it) }
                        }.build()
                    )
                }
            }
        }.build()
    }

    private fun deserializeEndpoints(endpoints: JSONArray): Collection<Colibri2Endpoint> {
        return ArrayList<Colibri2Endpoint>().apply {
            endpoints.forEach {
                if (it is JSONObject) { add(deserializeEndpoint(it)) }
            }
        }
    }

    private fun deserializeRelays(relays: JSONArray): Collection<Colibri2Relay> {
        return ArrayList<Colibri2Relay>().apply {
            relays.forEach {
                if (it is JSONObject) { add(deserializeRelay(it)) }
            }
        }
    }

    private fun deserializeAbstractConferenceModificationToBuilder(
        modification: JSONObject,
        builder: AbstractConferenceModificationIQ.Builder<*>
    ) {
        modification[Colibri2JSONSerializer.ENDPOINT_LIST].let { endpoints ->
            if (endpoints is JSONArray) {
                deserializeEndpoints(endpoints).forEach { builder.addConferenceEntity(it) }
            }
        }

        modification[Colibri2JSONSerializer.RELAY_LIST].let { relays ->
            if (relays is JSONArray) {
                deserializeRelays(relays).forEach { builder.addConferenceEntity(it) }
            }
        }
    }

    fun deserializeConferenceModify(conferenceModify: JSONObject): ConferenceModifyIQ {
        return ConferenceModifyIQ.builder("id").apply {
            deserializeAbstractConferenceModificationToBuilder(conferenceModify, this)

            conferenceModify[ConferenceModifyIQ.MEETING_ID_ATTR_NAME]?.let {
                if (it is String) { setMeetingId(it) }
            }

            conferenceModify[ConferenceModifyIQ.NAME_ATTR_NAME]?.let {
                if (it is String) { setConferenceName(it) }
            }

            conferenceModify[ConferenceModifyIQ.CREATE_ATTR_NAME]?.let {
                if (it is Boolean) { setCreate(it) }
            }

            conferenceModify[ConferenceModifyIQ.CALLSTATS_ENABLED_ATTR_NAME]?.let {
                if (it is Boolean) { setCallstatsEnabled(it) }
            }

            conferenceModify[ConferenceModifyIQ.RTCSTATS_ENABLED_ATTR_NAME]?.let {
                if (it is Boolean) { setRtcstatsEnabled(it) }
            }
        }.build()
    }

    fun deserializeConferenceModified(conferenceModified: JSONObject): ConferenceModifiedIQ {
        return ConferenceModifiedIQ.builder("id").apply {
            ofType(IQ.Type.result)
            deserializeAbstractConferenceModificationToBuilder(conferenceModified, this)
            conferenceModified[Sources.ELEMENT]?.let {
                if (it is JSONArray) { setSources(deserializeSources(it)) }
            }
        }.build()
    }
}
