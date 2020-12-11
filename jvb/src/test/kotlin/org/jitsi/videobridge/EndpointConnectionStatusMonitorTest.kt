/*
 * Copyright @ 2018 - present 8x8, Inc.
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
import io.kotest.inspectors.forAll
import io.kotest.inspectors.forAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import org.jitsi.nlj.util.NEVER
import org.jitsi.test.concurrent.FakeScheduledExecutorService
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.mins
import org.jitsi.utils.secs
import org.jitsi.videobridge.message.EndpointConnectionStatusMessage
import org.jitsi.videobridge.octo.OctoEndpoint

class EndpointConnectionStatusMonitorTest : ShouldSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val executor: FakeScheduledExecutorService = spyk()
    val localEp1: Endpoint = mockk {
        every { id } returns "1"
    }
    val localEp2: Endpoint = mockk {
        every { id } returns "2"
    }
    val eps = listOf(localEp1, localEp2)

    val broadcastMessage = slot<EndpointConnectionStatusMessage>()
    val broadcastSendToOcto = slot<Boolean>()
    val broadcastCalls = mutableListOf<Pair<EndpointConnectionStatusMessage, Boolean>>()

    val sendMessageMessage = slot<EndpointConnectionStatusMessage>()
    val sendMessageDestinationEps = slot<List<AbstractEndpoint>>()
    val sendMessageSendToOcto = slot<Boolean>()
    val sendMessageCalls = mutableListOf<Triple<EndpointConnectionStatusMessage, List<AbstractEndpoint>, Boolean>>()

    val conference: Conference = mockk {
        every { localEndpoints } returns eps
        every { broadcastMessage(capture(broadcastMessage), capture(broadcastSendToOcto)) } answers {
            broadcastCalls += Pair(broadcastMessage.captured, broadcastSendToOcto.captured)
        }
        every {
            sendMessage(
                capture(sendMessageMessage),
                capture(sendMessageDestinationEps),
                capture(sendMessageSendToOcto)
            )
        } answers {
            sendMessageCalls +=
                Triple(sendMessageMessage.captured, sendMessageDestinationEps.captured, sendMessageSendToOcto.captured)
        }
    }

    val clock = executor.clock

    val monitor = EndpointConnectionStatusMonitor(conference, executor, LoggerImpl("test"), clock).apply {
        start()
    }

    context("EndpointConnectionStatusMonitor") {
        context("when endpoints have no activity") {
            eps.forEach {
                every { it.lastIncomingActivity } returns NEVER
            }
            context("but haven't been around longer than first transfer timeout") {
                eps.forEach {
                    every { it.mostRecentChannelCreatedTime } returns clock.instant()
                }
                executor.runOne()
                should("not fire any events") {
                    sendMessageCalls.shouldBeEmpty()
                    broadcastCalls.shouldBeEmpty()
                }
            }
            context("and have been around longer than first transfer timeout") {
                eps.forEach {
                    every { it.mostRecentChannelCreatedTime } returns clock.instant()
                }
                clock.elapse(1.mins)
                executor.runOne()
                should("fire broadcast events for the local endpoints") {
                    sendMessageCalls.shouldBeEmpty()
                    broadcastCalls shouldHaveSize 2
                    broadcastCalls.forAny { (msg, sendToOcto) ->
                        sendToOcto && msg.endpoint == "1" && msg.active == "false"
                    }
                    broadcastCalls.forAny { (msg, sendToOcto) ->
                        sendToOcto && msg.endpoint == "2" && msg.active == "false"
                    }
                }
            }
        }
        context("when the endpoints have had activity") {
            eps.forEach {
                every { it.mostRecentChannelCreatedTime } returns clock.instant()
                every { it.lastIncomingActivity } returns clock.instant()
            }
            context("that is within maxInactivityLimit") {
                clock.elapse(1.secs)
                executor.runOne()
                should("not fire any events") {
                    sendMessageCalls.shouldBeEmpty()
                    broadcastCalls.shouldBeEmpty()
                }
            }
            context("but not within maxInactivityLimit") {
                clock.elapse(1.mins)
                executor.runOne()
                should("fire inactive events") {
                    sendMessageCalls.shouldBeEmpty()
                    broadcastCalls shouldHaveSize 2
                    broadcastCalls.forAny { (msg, sendToOcto) ->
                        sendToOcto && msg.endpoint == "1" && msg.active == "false"
                    }
                    broadcastCalls.forAny { (msg, sendToOcto) ->
                        sendToOcto && msg.endpoint == "2" && msg.active == "false"
                    }
                }
                context("but then one becomes active") {
                    every { localEp1.lastIncomingActivity } returns clock.instant()
                    clock.elapse(1.secs)
                    executor.runOne()
                    should("fire an active event for that ep") {
                        sendMessageCalls.shouldBeEmpty()
                        broadcastCalls shouldHaveSize 3
                        broadcastCalls.last().let { (msg, sendToOcto) ->
                            sendToOcto shouldBe true
                            msg.endpoint shouldBe "1"
                            msg.active shouldBe "true"
                        }
                    }
                }
                context("and then a new ep joins") {
                    every { conference.getEndpoint("4") } returns mockk() { every { id } returns "4" }
                    monitor.endpointConnected("4")
                    should("update the new endpoint of the other endpoints' statuses") {
                        sendMessageCalls shouldHaveSize 2
                        sendMessageCalls.forAll { (_, destEps, sendToOcto) ->
                            destEps shouldHaveSize 1
                            destEps.first().id shouldBe "4"
                            sendToOcto shouldBe false
                        }
                        sendMessageCalls.forAny { (msg, _, _) ->
                            msg.endpoint shouldBe "1"
                            msg.active shouldBe "false"
                        }
                        sendMessageCalls.forAny { (msg, _, _) ->
                            msg.endpoint shouldBe "2"
                            msg.active shouldBe "false"
                        }
                    }
                }
                context("and then an ep expires") {
                    monitor.endpointExpired("1")
                    context("and then a new ep joins") {
                        every { conference.getEndpoint("4") } returns mockk() { every { id } returns "4" }
                        monitor.endpointConnected("4")
                        // We shouldn't get a notification for the expired endpoint
                        should("update the new endpoint of the other endpoints' statuses") {
                            sendMessageCalls shouldHaveSize 1
                            sendMessageCalls.forAll { (_, destEps, sendToOcto) ->
                                destEps shouldHaveSize 1
                                destEps.first().id shouldBe "4"
                                sendToOcto shouldBe false
                            }
                            sendMessageCalls.forAny { (msg, _, _) ->
                                msg.endpoint shouldBe "2"
                                msg.active shouldBe "false"
                            }
                        }
                    }
                }
            }
        }
    }
})
