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

import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.datachannel.DataChannel
import org.jitsi.videobridge.message.ConnectionStats
import org.jitsi.videobridge.message.EndpointMessage
import org.jitsi.videobridge.message.ForwardedSourcesMessage
import org.jitsi.videobridge.util.LogCapture

class EndpointMessageTransportTest : ShouldSpec() {
    override fun isolationMode() = IsolationMode.InstancePerLeaf

    init {
        context("The transcriber ID") {
            should("be the value clients match on") {
                // The bridge's transcription results carry it as from, and lib-jitsi-meet compares it as a literal.
                Conference.TRANSCRIBER_ID shouldBe "transcriber"
            }
        }
        context("Forwarding a broadcast endpoint message") {
            should("forward it with the sender's ID as from, overwriting a from set by the sender") {
                val (conference, transport) = setup("abcdef01")
                val message = EndpointMessage("").apply { from = Conference.TRANSCRIBER_ID }

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
            should("send it to the target with the sender's ID as from") {
                val (conference, transport) = setup("abcdef01")
                val target = mockk<Endpoint>(relaxed = true)
                every { conference.getEndpoint("abcdef02") } returns target
                val message = EndpointMessage("abcdef02")

                transport.endpointMessage(message)

                verify(exactly = 1) { target.sendMessage(message) }
                message.from shouldBe "abcdef01"
            }
            should("drop it when the sender's ID is the transcriber ID") {
                val (conference, transport) = setup(Conference.TRANSCRIBER_ID)
                val target = mockk<Endpoint>(relaxed = true)
                every { conference.getEndpoint("abcdef02") } returns target

                transport.endpointMessage(EndpointMessage("abcdef02"))

                verify(exactly = 0) { target.sendMessage(any()) }
            }
        }
        context("An incoming message that cannot be parsed") {
            should("not be quoted in the log") {
                LogCapture(EndpointMessageTransport::class.java.name).use { log ->
                    val (_, transport) = setup("abcdef01")
                    val secret = "SECRET-WORDS-31415"

                    transport.onMessage(
                        null,
                        """{"colibriClass":"EndpointMessage","msgPayload":{"name":"a","text":"$secret"}}"""
                    )

                    transport.onMessage(null, "$secret and more")

                    log.text shouldContain "Invalid message received"
                    log.text shouldNotContain secret
                }
            }
        }
        context("Messages that the data channel does not accept") {
            val (_, transport) = setup("abcdef01")
            // The transport keeps only a weak reference to the data channel.
            val dataChannel = mockk<DataChannel>(relaxed = true) {
                every { isReady } returns true
                every { sendString(any()) } returns true
            }
            transport.setDataChannel(dataChannel)

            should("not be reported when the data channel accepts every message") {
                transport.sendMessage(ConnectionStats(1000))
                verify(exactly = 1) { dataChannel.sendString(match { it.contains(ConnectionStats.TYPE) }) }
                transport.droppedMessagesSummary() shouldBe null
            }
            should("be counted by type, and not as sent") {
                transport.sendMessage(ConnectionStats(1000))
                every { dataChannel.sendString(any()) } returns false
                repeat(3) { transport.sendMessage(ConnectionStats(1000)) }
                transport.sendMessage(ForwardedSourcesMessage(emptyList()))

                transport.droppedMessagesSummary() shouldBe
                    "The data channel did not accept 4 messages, by type: {ConnectionStats=3, ForwardedSourcesMessage=1}"
                val debugState = transport.debugState
                debugState["data_channel_dropped_counts"]["ConnectionStats"].asLong() shouldBe 3
                debugState["data_channel_dropped_counts"]["ForwardedSourcesMessage"].asLong() shouldBe 1
                debugState["sent_counts"]["ConnectionStats"].asLong() shouldBe 1
                debugState["sent_counts"].has("ForwardedSourcesMessage") shouldBe false
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
