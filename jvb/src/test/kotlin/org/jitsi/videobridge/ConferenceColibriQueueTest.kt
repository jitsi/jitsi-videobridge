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
package org.jitsi.videobridge

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.jitsi.ConfigTest
import org.jitsi.nlj.stats.DelayStats
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.xmpp.XmppConnection
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.impl.JidCreate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests that a colibri request accepted by a [Conference] is always answered, even if the conference expires before
 * the request is handled (see #2462), and the related handling of conference expiration.
 */
class ConferenceColibriQueueTest : ConfigTest() {
    /** Counts down when the conference is expired via the (mock) videobridge, i.e. by a colibri request. */
    private val expiredByVideobridge = CountDownLatch(1)
    private val videobridge = mockk<Videobridge>(relaxed = true) {
        every { expireConference(any()) } answers {
            firstArg<Conference>().expire()
            expiredByVideobridge.countDown()
        }
    }
    private val name = JidCreate.entityBareFrom("roomName@somedomain.com")
    private val meetingId = "meeting-id"

    private fun newConference() = Conference(videobridge, "id", name, meetingId, false)

    init {
        context("A request queued when the conference expires is answered with conference_not_found") {
            val conference = newConference()
            try {
                conference.createLocalEndpoint("a", true, false, false, false, false, false)
                val metricBefore = ColibriQueue.expiredConferenceRequestsMetric.get()

                // Block the handler thread inside the first request's callback, so the second request is guaranteed
                // to be in the queue when the conference expires.
                val blockingRequest = BlockingRequest(emptyModify("1"))
                conference.enqueueColibriRequest(blockingRequest.request)
                blockingRequest.callbackReached.await(5, TimeUnit.SECONDS) shouldBe true

                val secondResponse = CompletableFuture<IQ>()
                conference.enqueueColibriRequest(colibriRequest(emptyModify("2")) { secondResponse.complete(it) })

                // Expire from another thread, as VideobridgeExpireThread would.
                conference.expire()
                blockingRequest.release.countDown()

                blockingRequest.response.get(5, TimeUnit.SECONDS).type shouldBe IQ.Type.result
                secondResponse.get(5, TimeUnit.SECONDS).shouldBeConferenceNotFound()
                ColibriQueue.expiredConferenceRequestsMetric.get() shouldBe metricBefore + 1
            } finally {
                conference.expire()
            }
        }

        context("A request enqueued after the conference expired is answered with conference_not_found") {
            val conference = newConference()
            val metricBefore = ColibriQueue.expiredConferenceRequestsMetric.get()
            conference.expire()

            val response = CompletableFuture<IQ>()
            conference.enqueueColibriRequest(colibriRequest(emptyModify("3")) { response.complete(it) })
            response.get(5, TimeUnit.SECONDS).shouldBeConferenceNotFound()
            ColibriQueue.expiredConferenceRequestsMetric.get() shouldBe metricBefore + 1
        }

        context("A request dequeued before the conference expired is answered with conference_not_found") {
            // Simulates the request having been taken off the queue (so close() does not see it) before the conference
            // expired from another thread, by invoking the queue's handler directly.
            val conference = newConference()
            val metricBefore = ColibriQueue.expiredConferenceRequestsMetric.get()
            conference.expire()

            var response: IQ? = null
            conference.handleColibriRequest(colibriRequest(emptyModify("4")) { response = it }) shouldBe true
            response.shouldNotBeNull().shouldBeConferenceNotFound()
            ColibriQueue.expiredConferenceRequestsMetric.get() shouldBe metricBefore + 1
        }

        context("Removing the last endpoint does not expire the conference while other requests are queued") {
            val conference = newConference()
            try {
                conference.createLocalEndpoint("a", true, false, false, false, false, false)

                // Block the handler thread in the first request's callback, so that both of the following requests
                // are queued before the second one (which removes the last endpoint) is handled.
                val blockingRequest = BlockingRequest(emptyModify("5"))
                conference.enqueueColibriRequest(blockingRequest.request)
                blockingRequest.callbackReached.await(5, TimeUnit.SECONDS) shouldBe true

                val expireLastEndpoint = CompletableFuture<IQ>()
                conference.enqueueColibriRequest(
                    colibriRequest(expireEndpointModify("6", "a")) { expireLastEndpoint.complete(it) }
                )
                val thirdRequest = BlockingRequest(emptyModify("7"))
                conference.enqueueColibriRequest(thirdRequest.request)
                blockingRequest.release.countDown()

                expireLastEndpoint.get(5, TimeUnit.SECONDS).type shouldBe IQ.Type.result
                thirdRequest.callbackReached.await(5, TimeUnit.SECONDS) shouldBe true
                // Removing the last endpoint did not expire the conference, because the third request was pending,
                // and the third request was processed normally.
                conference.isExpired shouldBe false
                conference.endpointCount shouldBe 0
                thirdRequest.release.countDown()
                thirdRequest.response.get(5, TimeUnit.SECONDS).type shouldBe IQ.Type.result

                // The third request left the conference empty with nothing pending, so it expired it.
                expiredByVideobridge.await(5, TimeUnit.SECONDS) shouldBe true
                conference.isExpired shouldBe true
            } finally {
                conference.expire()
            }
        }

        context("An endpoint constructed while the conference expired is rejected and expired") {
            val conference = newConference()
            // Simulates the conference expiring between constructing the endpoint and adding it.
            val endpoint = Endpoint("a", conference, createLogger(), true, false, false, false, false, false)
            conference.expire()

            shouldThrow<Conference.ConferenceExpiredException> {
                conference.addLocalEndpoint(endpoint, false)
            }
            endpoint.isExpired shouldBe true
            conference.endpointCount shouldBe 0
            verify(exactly = 0) { videobridge.localEndpointCreated(any()) }
        }

        context("An error response which leaves the conference empty expires it") {
            val conference = newConference()
            try {
                conference.createLocalEndpoint("a", true, false, false, false, false, false)

                // Queue the next two requests behind a blocking one, so that the removal of the last endpoint
                // defers the expiration to the request after it, which fails.
                val blockingRequest = BlockingRequest(emptyModify("9"))
                conference.enqueueColibriRequest(blockingRequest.request)
                blockingRequest.callbackReached.await(5, TimeUnit.SECONDS) shouldBe true

                val expireLastEndpoint = CompletableFuture<IQ>()
                conference.enqueueColibriRequest(
                    colibriRequest(expireEndpointModify("10", "a")) { expireLastEndpoint.complete(it) }
                )
                // Updating the sources of an unknown endpoint is an error.
                val unknownEndpoint = Colibri2Endpoint.getBuilder().apply {
                    setId("unknown")
                    setSources(Sources.getBuilder().build())
                }.build()
                val failingRequest = CompletableFuture<IQ>()
                conference.enqueueColibriRequest(
                    colibriRequest(
                        ConferenceModifyIQ.builder("11").setMeetingId(meetingId).addEndpoint(unknownEndpoint).build()
                    ) { failingRequest.complete(it) }
                )
                blockingRequest.release.countDown()

                expireLastEndpoint.get(5, TimeUnit.SECONDS).type shouldBe IQ.Type.result
                failingRequest.get(5, TimeUnit.SECONDS).type shouldBe IQ.Type.error
                expiredByVideobridge.await(5, TimeUnit.SECONDS) shouldBe true
                conference.isExpired shouldBe true
            } finally {
                conference.expire()
            }
        }

        context("Endpoints and relays can not be created in an expired conference") {
            val conference = newConference()
            conference.expire()

            shouldThrow<Conference.ConferenceExpiredException> {
                conference.createLocalEndpoint("a", true, false, false, false, false, false)
            }
            shouldThrow<Conference.ConferenceExpiredException> {
                conference.createRelay("relay-id", "mesh-id", true, true)
            }
            conference.endpointCount shouldBe 0
            conference.relayCount shouldBe 0
        }

        context("The synchronous colibri path answers conference_not_found for an expired conference") {
            val conference = newConference()
            conference.expire()

            conference.handleConferenceModifyIQ(emptyModify("8")).shouldBeConferenceNotFound()
        }
    }

    private fun emptyModify(stanzaId: String): ConferenceModifyIQ =
        ConferenceModifyIQ.builder(stanzaId).setMeetingId(meetingId).build()

    private fun expireEndpointModify(stanzaId: String, endpointId: String): ConferenceModifyIQ {
        val endpoint = Colibri2Endpoint.getBuilder().apply {
            setId(endpointId)
            setExpire(true)
        }.build()
        return ConferenceModifyIQ.builder(stanzaId).setMeetingId(meetingId).addEndpoint(endpoint).build()
    }

    private fun colibriRequest(iq: ConferenceModifyIQ, callback: (IQ) -> Unit) =
        XmppConnection.ColibriRequest(iq, DelayStats(), DelayStats(), callback = callback)

    /** A request whose callback blocks the handler thread until [release] is counted down. */
    private inner class BlockingRequest(iq: ConferenceModifyIQ) {
        val callbackReached = CountDownLatch(1)
        val release = CountDownLatch(1)
        val response = CompletableFuture<IQ>()
        val request = colibriRequest(iq) {
            callbackReached.countDown()
            release.await(5, TimeUnit.SECONDS)
            response.complete(it)
        }
    }

    private fun IQ.shouldBeConferenceNotFound() {
        type shouldBe IQ.Type.error
        error.shouldNotBeNull()
        error.condition shouldBe StanzaError.Condition.item_not_found
        val colibri2Error = error.getExtension<Colibri2Error>(Colibri2Error.ELEMENT, Colibri2Error.NAMESPACE)
        colibri2Error.shouldNotBeNull()
        colibri2Error.reason shouldBe Colibri2Error.Reason.CONFERENCE_NOT_FOUND
    }
}
