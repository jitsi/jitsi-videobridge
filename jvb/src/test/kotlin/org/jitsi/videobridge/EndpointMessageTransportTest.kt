/*
 * Copyright @ 2026 - Present, 8x8, Inc.
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
package org.jitsi.videobridge

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.message.EndpointMessage

class EndpointMessageTransportTest : ShouldSpec() {
    init {
        context("Forwarding a broadcast endpoint message") {
            should("forward it with the sender's ID as from") {
                val (conference, transport) = setup("abcdef01")
                val message = EndpointMessage("")

                transport.endpointMessage(message)

                verify(exactly = 1) { conference.sendMessage(message, any(), true) }
                message.from shouldBe "abcdef01"
            }
            should("drop it when the sender's ID is the transcriber ID") {
                val (conference, transport) = setup(Conference.TRANSCRIBER_ID)

                transport.endpointMessage(EndpointMessage(""))

                verify(exactly = 0) { conference.sendMessage(any(), any(), any()) }
            }
        }
        context("Forwarding a 1:1 endpoint message") {
            should("drop it when the sender's ID is the transcriber ID") {
                val (conference, transport) = setup(Conference.TRANSCRIBER_ID)
                val target = mockk<Endpoint>(relaxed = true)
                every { conference.getEndpoint("abcdef02") } returns target

                transport.endpointMessage(EndpointMessage("abcdef02"))

                verify(exactly = 0) { target.sendMessage(any()) }
            }
        }
    }

    private fun setup(endpointId: String): Pair<Conference, EndpointMessageTransport> {
        val conference = mockk<Conference>(relaxed = true) {
            every { isExpired } returns false
            every { localEndpoints } returns emptyList()
        }
        val endpoint = mockk<Endpoint>(relaxed = true) {
            every { id } returns endpointId
            every { visitor } returns false
            every { this@mockk.conference } returns conference
        }

        return conference to EndpointMessageTransport(endpoint, mockk(relaxed = true), createLogger())
    }
}
