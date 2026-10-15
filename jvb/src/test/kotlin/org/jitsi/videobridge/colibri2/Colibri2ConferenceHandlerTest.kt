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
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.relay.Relay
import org.jitsi.videobridge.relay.RelayedEndpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Endpoints
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError

class Colibri2ConferenceHandlerTest : ConfigTest() {
    init {
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
    }
}
