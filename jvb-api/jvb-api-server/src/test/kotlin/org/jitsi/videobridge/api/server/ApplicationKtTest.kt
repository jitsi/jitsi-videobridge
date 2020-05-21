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

package org.jitsi.videobridge.api.server

import io.kotest.assertions.failure
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.server.testing.withTestApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.jitsi.videobridge.api.types.v1.ConferenceManager
import org.jitsi.videobridge.api.util.SmackXmlSerDes
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.colibri.ColibriIQProvider
import org.jitsi.xmpp.extensions.health.HealthCheckIQ
import org.jitsi.xmpp.extensions.health.HealthCheckIQProvider
import org.jivesoftware.smack.packet.ErrorIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.SimpleIQ
import org.jivesoftware.smack.provider.ProviderManager
import java.util.concurrent.TimeUnit


class ApplicationKtTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val confMgr: ConferenceManager = mockk()

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
        ProviderManager.addIQProvider(
            ColibriConferenceIQ.ELEMENT_NAME,
            ColibriConferenceIQ.NAMESPACE,
            ColibriIQProvider())
        ProviderManager.addIQProvider(
            HealthCheckIQ.ELEMENT_NAME,
            HealthCheckIQ.NAMESPACE,
            HealthCheckIQProvider())
    }

    init {
        context("Receiving a ColibriConferenceIQ") {
            val capturedIq = slot<ColibriConferenceIQ>()
            every { confMgr.handleColibriConferenceIQ(capture(capturedIq)) } answers { IQ.createResultIQ(capturedIq.captured) }
            withTestApplication({ module(confMgr) }) {
                handleWebSocketConversation("/v1/ws") { incoming, outgoing ->
                    val iq = ColibriConferenceIQ()
                    outgoing.send(Frame.Text(SmackXmlSerDes.serialize(iq)))
                    val resp = incoming.receive()
                    should("call ConferenceManager#handleColibriConferenceIQ and respond") {
                        verify(exactly = 1) { confMgr.handleColibriConferenceIQ(any()) }
                        // The IQ the server passed to ConferenceManager should be the same one we sent
                        capturedIq.captured.stanzaId shouldBe iq.stanzaId
                        resp.shouldBeInstanceOf<Frame.Text>()
                        resp as Frame.Text
                        val respIq = SmackXmlSerDes.deserialize(resp.readText())
                        respIq.stanzaId shouldBe iq.stanzaId
                    }
                }
            }
        }
        context("Receiving a HealthCheckIQ") {
            val capturedIq = slot<HealthCheckIQ>()
            every { confMgr.handleHealthIq(capture(capturedIq)) } answers { IQ.createResultIQ(capturedIq.captured)}
            withTestApplication({ module(confMgr) }) {
                handleWebSocketConversation("/v1/ws") { incoming, outgoing ->
                    val iq = HealthCheckIQ()
                    outgoing.send(Frame.Text(SmackXmlSerDes.serialize(iq)))
                    val resp = incoming.receive()
                    should("call ConferenceManager#handleHealthIq and respond") {
                        verify(exactly = 1) { confMgr.handleHealthIq(any()) }
                        // The IQ the server passed to ConferenceManager should be the same one we sent
                        capturedIq.captured.stanzaId shouldBe iq.stanzaId
                        resp.shouldBeInstanceOf<Frame.Text>()
                        resp as Frame.Text
                        val respIq = SmackXmlSerDes.deserialize(resp.readText())
                        respIq.stanzaId shouldBe iq.stanzaId
                    }
                }
            }
        }
        context("Receiving an unsupported IQ") {
            withTestApplication({ module(confMgr) }) {
                handleWebSocketConversation("/v1/ws") { incoming, outgoing ->
                    val iq = SomeOtherIq()
                    outgoing.send(Frame.Text(SmackXmlSerDes.serialize(iq)))
                    val resp = incoming.receive()
                    should("respond with an error") {
                        resp as Frame.Text
                        val respIq = SmackXmlSerDes.deserialize(resp.readText())
                        respIq.shouldBeInstanceOf<ErrorIQ>()
                        println(resp.readText())
                    }
                }
            }
        }
        context("Receiving a non-IQ") {
            withTestApplication({ module(confMgr) }) {
                handleWebSocketConversation("/v1/ws") { incoming, outgoing ->
                    val msg = Message("someone", "hello")
                    outgoing.send(Frame.Text(SmackXmlSerDes.serialize(msg)))
                    shouldTimeout(200, TimeUnit.MILLISECONDS) {
                        incoming.receive()
                    }
                }
            }
        }
    }
}

private class SomeOtherIq : SimpleIQ("element_name", "element_namespace")

private suspend fun <T> shouldTimeout(timeout: Long, unit: TimeUnit, thunk: suspend () -> T) {
    val timedOut = try {
        withTimeout(unit.toMillis(timeout)) {
            thunk()
            false
        }
    } catch (t: TimeoutCancellationException) {
        true
    }
    if (!timedOut) {
        throw failure("Expected test to timeout for $timeout/$unit")
    }
}
