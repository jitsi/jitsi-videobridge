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
import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.config.withNewConfig
import org.jitsi.utils.MediaType
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.relay.AudioSourceDesc
import org.jitsi.videobridge.relay.SyntheticSourceKind
import org.jitsi.xmpp.extensions.colibri.SourcePacketExtension
import org.jitsi.xmpp.extensions.colibri2.Capability
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Endpoints
import org.jitsi.xmpp.extensions.colibri2.MediaSource
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.extensions.colibri2.Transport
import org.jivesoftware.smack.packet.IQ
import org.jxmpp.jid.impl.JidCreate

/**
 * Tests for [Colibri2ConferenceHandler]: the [SyntheticSourceKind] it gives a synthetic audio source, which follows
 * from whether the endpoint owning the source is synthetic.
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
