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

import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jitsi.ConfigTest
import org.jitsi.nlj.stats.DelayStats
import org.jitsi.videobridge.xmpp.XmppConnection
import org.jitsi.xmpp.extensions.colibri2.Colibri2Endpoint
import org.jitsi.xmpp.extensions.colibri2.Colibri2Error
import org.jitsi.xmpp.extensions.colibri2.ConferenceModifyIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.StanzaError
import org.jxmpp.jid.impl.JidCreate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tests that a colibri request accepted by a [Conference] is always answered, even if the conference expires before
 * the request is handled (see #2462).
 */
class ConferenceColibriQueueTest : ConfigTest() {
    private val videobridge = mockk<Videobridge>(relaxed = true) {
        every { expireConference(any()) } answers { firstArg<Conference>().expire() }
    }
    private val name = JidCreate.entityBareFrom("roomName@somedomain.com")

    init {
        context("A request queued when the last endpoint is expired is answered with conference_not_found") {
            val conference = Conference(videobridge, "id", name, "meeting-id", false)
            conference.createLocalEndpoint("a", true, false, false, false, false, false)

            // Block the handler thread inside the first request's callback, so the second request is guaranteed to
            // be in the queue when the conference expires.
            val firstCallbackReached = CountDownLatch(1)
            val releaseFirstCallback = CountDownLatch(1)
            val firstResponse = CompletableFuture<IQ>()
            val expireEndpointA = Colibri2Endpoint.getBuilder().apply {
                setId("a")
                setExpire(true)
            }.build()
            val expireLastEndpoint = ConferenceModifyIQ.builder("1").setMeetingId("meeting-id")
                .addEndpoint(expireEndpointA)
                .build()
            conference.enqueueColibriRequest(
                colibriRequest(expireLastEndpoint) {
                    firstCallbackReached.countDown()
                    releaseFirstCallback.await(5, TimeUnit.SECONDS)
                    firstResponse.complete(it)
                }
            )
            firstCallbackReached.await(5, TimeUnit.SECONDS) shouldBe true

            val secondResponse = CompletableFuture<IQ>()
            conference.enqueueColibriRequest(
                colibriRequest(ConferenceModifyIQ.builder("2").setMeetingId("meeting-id").build()) {
                    secondResponse.complete(it)
                }
            )
            releaseFirstCallback.countDown()

            firstResponse.get(5, TimeUnit.SECONDS).type shouldBe IQ.Type.result
            // The second response is only sent once the conference has expired and closed its queue.
            secondResponse.get(5, TimeUnit.SECONDS).shouldBeConferenceNotFound()
            conference.isExpired shouldBe true
        }

        context("A request enqueued after the conference expired is answered with conference_not_found") {
            val conference = Conference(videobridge, "id", name, "meeting-id", false)
            conference.expire()

            val response = CompletableFuture<IQ>()
            conference.enqueueColibriRequest(
                colibriRequest(ConferenceModifyIQ.builder("3").setMeetingId("meeting-id").build()) {
                    response.complete(it)
                }
            )
            response.get(5, TimeUnit.SECONDS).shouldBeConferenceNotFound()
        }
    }

    private fun colibriRequest(iq: ConferenceModifyIQ, callback: (IQ) -> Unit) =
        XmppConnection.ColibriRequest(iq, DelayStats(), DelayStats(), callback = callback)

    private fun IQ.shouldBeConferenceNotFound() {
        type shouldBe IQ.Type.error
        error.shouldNotBeNull()
        error.condition shouldBe StanzaError.Condition.item_not_found
        val colibri2Error = error.getExtension<Colibri2Error>(Colibri2Error.ELEMENT, Colibri2Error.NAMESPACE)
        colibri2Error.shouldNotBeNull()
        colibri2Error.reason shouldBe Colibri2Error.Reason.CONFERENCE_NOT_FOUND
    }
}
