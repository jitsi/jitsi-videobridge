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

import org.jitsi.xmpp.extensions.colibri2.AbstractConferenceEntity
import org.jitsi.xmpp.extensions.colibri2.AbstractConferenceModificationIQ
import org.jitsi.xmpp.extensions.colibri2.Capability
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifiedIQ
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.ForceMute
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sctp
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.IceUdpTransportPacketExtension
import org.json.simple.JSONArray
import org.json.simple.JSONObject

object Colibri2JSONSerializer {
    val MEDIA_LIST = Media.ELEMENT + "s"

    val ENDPOINT_LIST = Colibri2Endpoint.ELEMENT + "s"
    val RELAY_LIST = Colibri2Relay.ELEMENT + "s"
    val CAPABILITIES_LIST = "capabilities"

    private fun serializeMedia(media: Media): JSONObject {
        return JSONObject().apply {
            put(Media.TYPE_ATTR_NAME, media.type)
            if (media.payloadTypes.isNotEmpty()) {
                put(JSONSerializer.PAYLOAD_TYPES, JSONSerializer.serializePayloadTypes(media.payloadTypes))
            }
            if (media.rtpHdrExts.isNotEmpty()) {
                put(JSONSerializer.RTP_HEADER_EXTS, JSONSerializer.serializeRtpHdrExts(media.rtpHdrExts))
            }
        }
    }

    private fun serializeSctp(sctp: Sctp): JSONObject {
        return JSONObject().apply {
            sctp.port?.let { put(Sctp.PORT_ATTR_NAME, it) }
            sctp.role?.let { put(Sctp.ROLE_ATTR_NAME, it) }
        }
    }

    private fun serializeTransport(transport: Transport): JSONObject {
        return JSONObject().apply {
            if (transport.iceControlling != Transport.ICE_CONTROLLING_DEFAULT) {
                put(Transport.ICE_CONTROLLING_ATTR_NAME, transport.iceControlling)
            }
            if (transport.useUniquePort != Transport.USE_UNIQUE_PORT_DEFAULT) {
                put(Transport.USE_UNIQUE_PORT_ATTR_NAME, transport.useUniquePort)
            }
            transport.iceUdpTransport?.let {
                put(IceUdpTransportPacketExtension.ELEMENT, JSONSerializer.serializeTransport(it))
            }
            transport.sctp?.let {
                put(Sctp.ELEMENT, serializeSctp(it))
            }
        }
    }

    private fun serializeMediaSource(source: MediaSource): JSONObject {
        return JSONObject().apply {
            put(MediaSource.TYPE_ATTR_NAME, source.type)
            put(MediaSource.ID_NAME, source.id)
            if (source.sources.isNotEmpty()) {
                put(JSONSerializer.SOURCES, JSONSerializer.serializeSources(source.sources))
            }
            if (source.ssrcGroups.isNotEmpty()) {
                put(JSONSerializer.SOURCE_GROUPS, JSONSerializer.serializeSourceGroups(source.ssrcGroups))
            }
        }
    }

    private fun serializeMedias(medias: Collection<Media>): JSONArray {
        return JSONArray().apply {
            medias.forEach { add(serializeMedia(it)) }
        }
    }

    private fun serializeSources(sources: Sources): JSONArray {
        return JSONArray().apply {
            sources.mediaSources.forEach { add(serializeMediaSource(it)) }
        }
    }

    private fun serializeAbstractConferenceEntity(entity: AbstractConferenceEntity): JSONObject {
        return JSONObject().apply {
            put(AbstractConferenceEntity.ID_ATTR_NAME, entity.id)

            if (entity.create != AbstractConferenceEntity.CREATE_DEFAULT) {
                put(AbstractConferenceEntity.CREATE_ATTR_NAME, entity.create)
            }

            if (entity.expire != AbstractConferenceEntity.EXPIRE_DEFAULT) {
                put(AbstractConferenceEntity.EXPIRE_ATTR_NAME, entity.expire)
            }

            if (entity.media.isNotEmpty()) {
                put(MEDIA_LIST, serializeMedias(entity.media))
            }

            entity.transport?.let { put(Transport.ELEMENT, serializeTransport(it)) }

            entity.sources?.let { put(Sources.ELEMENT, serializeSources(it)) }
        }
    }

    private fun serializeForceMute(forceMute: ForceMute): JSONObject {
        return JSONObject().apply {
            put(ForceMute.AUDIO_ATTR_NAME, forceMute.audio)
            put(ForceMute.VIDEO_ATTR_NAME, forceMute.video)
        }
    }

    private fun serializeCapabilities(capabilities: Collection<Capability>): JSONArray {
        return JSONArray().apply {
            capabilities.forEach { add(it.name) }
        }
    }

    private fun serializeEndpoint(endpoint: Colibri2Endpoint): JSONObject {
        return serializeAbstractConferenceEntity(endpoint).apply {
            endpoint.statsId?.apply { put(Colibri2Endpoint.STATS_ID_ATTR_NAME, this) }
            endpoint.forceMute?.apply { put(ForceMute.ELEMENT, serializeForceMute(this)) }
            if (endpoint.capabilities.isNotEmpty()) {
                put(CAPABILITIES_LIST, serializeCapabilities(endpoint.capabilities))
            }
        }
    }

    private fun serializeRelay(relay: Colibri2Relay): JSONObject {
        return serializeAbstractConferenceEntity(relay).apply {
            relay.endpoints?.let { put(ENDPOINT_LIST, serializeEndpoints(it.endpoints)) }
        }
    }

    private fun serializeEndpoints(endpoints: Collection<Colibri2Endpoint>): JSONArray {
        return JSONArray().apply {
            endpoints.forEach { add(serializeEndpoint(it)) }
        }
    }

    private fun serializeRelays(relays: Collection<Colibri2Relay>): JSONArray {
        return JSONArray().apply {
            relays.forEach { add(serializeRelay(it)) }
        }
    }

    private fun serializeAbstractConferenceModificationIQ(iq: AbstractConferenceModificationIQ<*>): JSONObject {
        return JSONObject().apply {
            if (iq.endpoints.isNotEmpty()) {
                put(ENDPOINT_LIST, serializeEndpoints(iq.endpoints))
            }
            if (iq.relays.isNotEmpty()) {
                put(RELAY_LIST, serializeRelays(iq.relays))
            }
        }
    }

    fun serializeConferenceModify(iq: ConferenceModifyIQ): JSONObject {
        return serializeAbstractConferenceModificationIQ(iq).apply {
            if (iq.create != ConferenceModifyIQ.CREATE_DEFAULT) {
                put(ConferenceModifyIQ.CREATE_ATTR_NAME, iq.create)
            }

            if (iq.isCallstatsEnabled != ConferenceModifyIQ.CALLSTATS_ENABLED_DEFAULT) {
                put(ConferenceModifyIQ.CALLSTATS_ENABLED_ATTR_NAME, iq.isCallstatsEnabled)
            }

            if (iq.isRtcstatsEnabled != ConferenceModifyIQ.RTCSTATS_ENABLED_DEFAULT) {
                put(ConferenceModifyIQ.RTCSTATS_ENABLED_ATTR_NAME, iq.isRtcstatsEnabled)
            }

            put(ConferenceModifyIQ.MEETING_ID_ATTR_NAME, iq.meetingId)

            iq.conferenceName?.let { put(ConferenceModifyIQ.NAME_ATTR_NAME, it) }
        }
    }

    fun serializeConferenceModified(iq: ConferenceModifiedIQ): JSONObject {
        return serializeAbstractConferenceModificationIQ(iq).apply {
            iq.sources?.let { put(Sources.ELEMENT, serializeSources(it)) }
        }
    }
}
