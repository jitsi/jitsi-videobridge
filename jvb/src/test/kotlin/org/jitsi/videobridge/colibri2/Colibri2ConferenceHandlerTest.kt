/*
 * Copyright @ 2026 - present 8x8, Inc.
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

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.ConfigTest
import org.jitsi.config.withNewConfig
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.Relay
import org.jitsi.videobridge.relay.RelayedEndpoint
import org.jitsi.videobridge.relay.SyntheticSourceKind
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.colibri2.Capability
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Endpoints
import org.jitsi.xmpp.extensions.colibri2.Media
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jitsi.xmpp.extensions.jingle.PayloadTypePacketExtension
import org.jitsi.xmpp.extensions.jingle.RTPHdrExtPacketExtension
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.impl.JidCreate
import java.net.URI

/**
 * Tests for [Colibri2ConferenceHandler]: the [SyntheticSourceKind] it gives a synthetic audio source, which follows
 * from whether the endpoint owning the source is synthetic, and its answer when the conference expires mid-request.
 */
class Colibri2ConferenceHandlerTest : ConfigTest() {
    private val conference = Conference(
        mockk<Videobridge>(relaxed = true),
        "id",
        JidCreate.entityBareFrom("room@example.com"),
        null,
        false
    )
    private val handler = Colibri2ConferenceHandler(conference, LoggerImpl("test"))

    /** Handles [iq], asserting it was accepted. */
    private fun handle(iq: ConferenceModifyIQ) {
        handler.handleConferenceModifyIQ(iq).first.type shouldBe IQ.Type.result
    }

    init {
        context("The kind of a synthetic audio source") {
            context("of a local endpoint") {
                should("be agent when the endpoint is synthetic") {
                    handle(
                        endpointRequest(createEndpoint("agent", synthetic = true, audioSource("agent-a0", 1L, true)))
                    )

                    conference.getLocalEndpoint("agent").shouldNotBeNull().audioSources shouldBe listOf(
                        AudioSourceDesc(1L, "agent", "agent-a0", synthetic = true, kind = SyntheticSourceKind.AGENT)
                    )
                }
                should("be translation when the endpoint is regular, and unset for a regular source") {
                    handle(
                        endpointRequest(
                            createEndpoint(
                                "ep",
                                synthetic = false,
                                audioSource("ep-a0", 1L, synthetic = false),
                                audioSource("ep-a0.hi", 2L, synthetic = true)
                            )
                        )
                    )

                    conference.getLocalEndpoint("ep").shouldNotBeNull().audioSources shouldBe listOf(
                        AudioSourceDesc(1L, "ep", "ep-a0"),
                        AudioSourceDesc(2L, "ep", "ep-a0.hi", synthetic = true, kind = SyntheticSourceKind.TRANSLATION)
                    )
                }
            }
            context("of a relayed endpoint") {
                should("follow the synthetic-endpoint capability of its relay create, also on a later update") {
                    withNewConfig(RELAY_CONFIG) {
                        handle(
                            relayRequest(
                                create = true,
                                createEndpoint("agent", synthetic = true, audioSource("agent-a0", 1L, true)),
                                createEndpoint(
                                    "ep",
                                    synthetic = false,
                                    audioSource("ep-a0", 2L, synthetic = false),
                                    audioSource("ep-a0.hi", 3L, synthetic = true)
                                )
                            )
                        )

                        val relay = conference.getRelay(RELAY_ID).shouldNotBeNull()
                        relay.getEndpoint("agent").shouldNotBeNull().apply {
                            synthetic shouldBe true
                            audioSources shouldBe listOf(
                                AudioSourceDesc(1L, "agent", "agent-a0", true, SyntheticSourceKind.AGENT)
                            )
                        }
                        relay.getEndpoint("ep").shouldNotBeNull().apply {
                            synthetic shouldBe false
                            audioSources shouldBe listOf(
                                AudioSourceDesc(2L, "ep", "ep-a0"),
                                AudioSourceDesc(3L, "ep", "ep-a0.hi", true, SyntheticSourceKind.TRANSLATION)
                            )
                        }

                        // An update doesn't repeat the capability; the kind still follows the endpoint.
                        handle(relayRequest(create = false, updateEndpoint("agent", audioSource("agent-a1", 4L, true))))
                        relay.getEndpoint("agent").shouldNotBeNull().audioSources shouldBe listOf(
                            AudioSourceDesc(4L, "agent", "agent-a1", true, SyntheticSourceKind.AGENT)
                        )
                    }
                }
            }
        }
        context("A relay's remote endpoints can not be added because the conference expired") {
            withNewConfig("videobridge.relay.enabled=true") {
                val relayedEndpoint = mockk<RelayedEndpoint>(relaxed = true) {
                    every { id } returns "e1"
                }
                val relay = mockk<Relay>(relaxed = true) {
                    every { addRemoteEndpoint("e1", any(), any(), any()) } returns relayedEndpoint
                }
                val conference = mockk<Conference>(relaxed = true) {
                    every { getRelay("r") } returns relay
                    every { addEndpoints(any()) } throws Conference.ConferenceExpiredException("conf")
                }
                val handler = Colibri2ConferenceHandler(conference, createLogger())

                val remoteEndpoint = Colibri2Endpoint.getBuilder().apply {
                    setId("e1")
                    setCreate(true)
                }.build()
                val relayModify = Colibri2Relay.getBuilder().apply {
                    setId("r")
                    setEndpoints(Endpoints.getBuilder().addEndpoint(remoteEndpoint).build())
                }.build()
                val iq = ConferenceModifyIQ.builder("1").setMeetingId("m").addRelay(relayModify).build()

                val (response, _) = handler.handleConferenceModifyIQ(iq)

                should("respond with conference_not_found") {
                    response.type shouldBe IQ.Type.error
                    response.error.condition shouldBe StanzaError.Condition.item_not_found
                    val reason = response.error.getExtension<Colibri2Error>(
                        Colibri2Error.ELEMENT,
                        Colibri2Error.NAMESPACE
                    )
                    reason.shouldNotBeNull().reason shouldBe Colibri2Error.Reason.CONFERENCE_NOT_FOUND
                }
                should("remove the endpoints it added to the relay") {
                    verify { relay.removeRemoteEndpoint("e1") }
                }
            }
        }

        context("A synthetic endpoint created with media, as jicofo signals it") {
            val media = Media.getBuilder().setType(MediaType.AUDIO)
                .addPayloadType(
                    PayloadTypePacketExtension().apply {
                        setId(111)
                        setName("opus")
                        setClockrate(48000)
                        setChannels(2)
                    }
                )
                .addRtpHdrExt(
                    RTPHdrExtPacketExtension().apply {
                        setID("1")
                        setURI(URI("urn:ietf:params:rtp-hdrext:ssrc-audio-level"))
                    }
                )
                .build()
            val endpoint = Colibri2Endpoint.getBuilder().apply {
                setId("agent")
                setCreate(true)
                addCapability(Capability.CAP_SOURCE_NAME_SUPPORT)
                addCapability(Capability.CAP_SYNTHETIC_ENDPOINT)
                setSources(sources(arrayOf(audioSource("agent-a0", 1L, true))))
                addMedia(media)
            }.build()
            handler.handleConferenceModifyIQ(endpointRequest(endpoint)).first.type shouldBe IQ.Type.result

            should("itself supply the payload type and audio-level extension the injected audio needs") {
                // Injection looks these up on the local endpoints, so an agent alone on its bridge (humans relayed
                // in) depends on its own media.
                val agent = conference.getLocalEndpoint("agent").shouldNotBeNull()
                agent.getOpusPayloadType().shouldNotBeNull().pt shouldBe 111
                agent.getAudioLevelExtensionId() shouldBe 1
            }
        }
        context("A request the bridge does not support") {
            should("be rejected as feature-not-implemented, before anything is applied") {
                // Both an endpoint and a relay in one request.
                val request = ConferenceModifyIQ.builder("id").apply {
                    setMeetingId("meeting")
                    addEndpoint(createEndpoint("ep", synthetic = false, audioSource("ep-a0", 1L, false)))
                    addRelay(Colibri2Relay.getBuilder().apply { setId(RELAY_ID) }.build())
                }.build()

                val response = handler.handleConferenceModifyIQ(request).first
                response.type shouldBe IQ.Type.error
                response.error.condition shouldBe StanzaError.Condition.feature_not_implemented
                val reason = response.error.getExtension<Colibri2Error>(Colibri2Error.ELEMENT, Colibri2Error.NAMESPACE)
                reason.shouldNotBeNull().reason shouldBe Colibri2Error.Reason.FEATURE_NOT_IMPLEMENTED
                conference.getLocalEndpoint("ep") shouldBe null
            }
        }
    }

    private companion object {
        const val RELAY_ID = "relay"
        val RELAY_CONFIG = """
            videobridge.relay.enabled = true
            videobridge.relay.relay-id = "test-relay"
            videobridge.relay.region = "test-region"
        """.trimIndent()

        /** An audio media source with a single SSRC, as jicofo signals it. */
        fun audioSource(name: String, ssrc: Long, synthetic: Boolean): MediaSource = MediaSource.getBuilder()
            .setType(MediaType.AUDIO)
            .setId(name)
            .setSynthetic(synthetic)
            .addSource(
                SourcePacketExtension().apply {
                    this.ssrc = ssrc
                    this.name = name
                }
            )
            .build()

        fun sources(mediaSources: Array<out MediaSource>): Sources = Sources.getBuilder().apply {
            mediaSources.forEach { addMediaSource(it) }
        }.build()

        /** An endpoint create as jicofo sends it: a synthetic endpoint has no transport, a regular one does. */
        fun createEndpoint(id: String, synthetic: Boolean, vararg mediaSources: MediaSource): Colibri2Endpoint =
            Colibri2Endpoint.getBuilder().apply {
                setId(id)
                setCreate(true)
                addCapability(Capability.CAP_SOURCE_NAME_SUPPORT)
                if (synthetic) {
                    addCapability(Capability.CAP_SYNTHETIC_ENDPOINT)
                } else {
                    setTransport(Transport.getBuilder().apply { setIceControlling(true) }.build())
                }
                setSources(sources(mediaSources))
            }.build()

        /** An update of an existing endpoint's sources, which (as from jicofo) repeats none of the capabilities. */
        fun updateEndpoint(id: String, vararg mediaSources: MediaSource): Colibri2Endpoint =
            Colibri2Endpoint.getBuilder().apply {
                setId(id)
                setSources(sources(mediaSources))
            }.build()

        fun endpointRequest(endpoint: Colibri2Endpoint): ConferenceModifyIQ = ConferenceModifyIQ.builder("id").apply {
            setMeetingId("meeting")
            addEndpoint(endpoint)
        }.build()

        fun relayRequest(create: Boolean, vararg endpoints: Colibri2Endpoint): ConferenceModifyIQ =
            ConferenceModifyIQ.builder("id").apply {
                setMeetingId("meeting")
                addRelay(
                    Colibri2Relay.getBuilder().apply {
                        setId(RELAY_ID)
                        if (create) {
                            setCreate(true)
                            setTransport(Transport.getBuilder().apply { setIceControlling(true) }.build())
                        }
                        setEndpoints(Endpoints.getBuilder().apply { endpoints.forEach { addEndpoint(it) } }.build())
                    }.build()
                )
            }.build()
    }
}
