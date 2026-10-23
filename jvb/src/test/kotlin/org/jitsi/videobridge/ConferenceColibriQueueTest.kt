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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.jitsi.ConfigTest
import org.jitsi.nlj.stats.DelayStats
import org.jitsi.utils.logging2.createLogger
import org.jitsi.videobridge.colibri2.Colibri2ConferenceHandler
import org.jitsi.videobridge.xmpp.XmppConnection
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.Colibri2Relay
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jitsi.xmpp.extensions.colibri2.Connect
import org.jitsi.xmpp.extensions.colibri2.Sources
import org.jitsi.xmpp.util.RedactColibri
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.impl.JidCreate
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord

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
                conference.createLocalEndpoint("a", true, false, false, false, false, false, false)
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
                conference.createLocalEndpoint("a", true, false, false, false, false, false, false)

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
                conference.createLocalEndpoint("a", true, false, false, false, false, false, false)

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
                conference.createLocalEndpoint("a", true, false, false, false, false, false, false)
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

        context("The log lines carry the redacted request, on both colibri paths") {
            // An endpoint and a relay in one request is unsupported, so the request is logged, warned about and
            // rejected without its connect ever being applied.
            fun unsupportedRequestWithSecret(stanzaId: String): ConferenceModifyIQ {
                val connect = Connect(
                    id = "agent-1",
                    url = URI("wss://relay.example.com/agent"),
                    protocol = Connect.Protocols.MEDIAJSON,
                    type = Connect.Types.AGENT,
                    audio = true,
                    create = true
                ).apply { addHttpHeader("X-Agent-Token", "s3cret-token") }
                return ConferenceModifyIQ.builder(stanzaId).apply {
                    setMeetingId(meetingId)
                    addEndpoint(Colibri2Endpoint.getBuilder().apply { setId("e") }.build())
                    addRelay(Colibri2Relay.getBuilder().apply { setId("r") }.build())
                    addConnect(connect)
                }.build()
            }

            val records = CopyOnWriteArrayList<String>()
            val capture = object : Handler() {
                override fun publish(record: LogRecord) {
                    records.add("${record.level}: ${record.message}")
                }
                override fun flush() = Unit
                override fun close() = Unit
            }
            // Conference creates its own logger, so capture at the java.util.logging loggers it and the handler
            // delegate to.
            val julLoggers = listOf(Conference::class.java.name, Colibri2ConferenceHandler::class.java.name)
                .map { java.util.logging.Logger.getLogger(it) }
            julLoggers.forEach { it.addHandler(capture) }
            try {
                var response: IQ? = null
                newConference().handleColibriRequest(
                    colibriRequest(unsupportedRequestWithSecret("redact-q")) { response = it }
                ) shouldBe true
                response.shouldNotBeNull().type shouldBe IQ.Type.error

                newConference().handleConferenceModifyIQ(unsupportedRequestWithSecret("redact-s")).type shouldBe
                    IQ.Type.error
            } finally {
                julLoggers.forEach { it.removeHandler(capture) }
            }

            val requestLines =
                records.filter { it.contains("RECV colibri2 request") || it.contains("Unsupported request") }
            // The queued path logs RECV and the warning; the synchronous path logs RECV, the warning and SENT. The
            // redacted XML comes out of the transform with double-quoted attributes.
            requestLines.count { it.contains("RECV colibri2 request") && it.contains("id=\"redact-q\"") } shouldBe 1
            requestLines.count { it.contains("RECV colibri2 request") && it.contains("id=\"redact-s\"") } shouldBe 1
            requestLines.count { it.contains("Unsupported request") } shouldBe 2
            requestLines.forEach {
                it shouldContain "name=\"X-Agent-Token\""
                it shouldContain "value=\"[redacted]\""
                it shouldNotContain "s3cret-token"
            }
            records.filter { it.contains("colibri2") }.forEach { it shouldNotContain "s3cret-token" }
        }

        context("A request is answered normally when redacting it for the log fails") {
            mockkObject(RedactColibri.Companion)
            try {
                every { RedactColibri.redactHttpHeaderValues(any()) } throws RuntimeException("no transformer")

                // Each empty request expires its (empty) conference, so use one per path.
                var response: IQ? = null
                newConference().handleColibriRequest(colibriRequest(emptyModify("9")) { response = it }) shouldBe true
                response.shouldNotBeNull().type shouldBe IQ.Type.result

                newConference().handleConferenceModifyIQ(emptyModify("10")).type shouldBe IQ.Type.result
            } finally {
                unmockkObject(RedactColibri.Companion)
            }
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
