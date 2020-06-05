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

package org.jitsi.videobridge.api.util

import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.cio.websocket.Frame
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jxmpp.jid.impl.JidCreate
import java.time.Duration
import kotlin.random.Random
import kotlin.time.ExperimentalTime

@ExperimentalTime
class XmppWebSocketClientTest : ShouldSpec() {
    private val capturedIncomingMessageHandler = slot<(Frame) -> Unit>()
    private val capturedSentIq = slot<String>()
    private val wsClient: WebSocketClient = mockk()

    init {
        every {
            wsClient setProperty "incomingMessageHandler" value capture(capturedIncomingMessageHandler)
        } just runs
        every { wsClient.run() } just runs

        val ws = XmppWebSocketClient(wsClient, parentLogger = LoggerImpl("test"), requestTimeout = Duration.ofSeconds(3))
        ws.run()

        context("sendIqAndGetReply") {
            context("when a correct reply is sent") {
                wsClient.respondCorrectly(capturedIncomingMessageHandler.captured)
                should("work correctly") {
                    val iq = generateIq()
                    val resp = ws.sendIqAndGetReply(iq)
                    resp shouldBeResponseTo iq
                }
            }
            context("when no reply is sent") {
                wsClient.neverRespond()
                should("return null") {
                    val iq = generateIq()
                    val resp = ws.sendIqAndGetReply(iq)
                    resp shouldBe null
                }
            }
            context("when a reply is sent for a different IQ") {
                wsClient.respondToDifferentIq(capturedIncomingMessageHandler.captured)
                should("return null") {
                    val iq = generateIq()
                    val resp = ws.sendIqAndGetReply(iq)
                    resp shouldBe null
                }
            }
            context("when requests and responses are mixed") {
                wsClient.flipResponses(capturedIncomingMessageHandler.captured)
                should("work correctly") {
                    launch(Dispatchers.IO) {
                        println("sending req 1")
                        val iq = generateIq()
                        val resp = ws.sendIqAndGetReply(iq)
                        resp shouldBeResponseTo iq
                    }
                    launch(Dispatchers.IO) {
                        println("sending req 2")
                        val iq = generateIq()
                        val resp = ws.sendIqAndGetReply(iq)
                        resp shouldBeResponseTo iq
                    }
                }
            }
        }
    }
}

/**
 * Configure the mock [WebSocketClient] to simulate a proper response to any
 * IQ it receives
 */
private fun WebSocketClient.respondCorrectly(msgHandler: (Frame) -> Unit) {
    val iqStr = slot<String>()
    every { sendString(capture(iqStr)) } answers {
        val response = iqStr.captured.createResponseIq()
        msgHandler.invoke(Frame.Text(response.toXML().toString()))
    }
}

/**
 * Configure the mock [WebSocketClient] to receive an IQ but never respond
 */
private fun WebSocketClient.neverRespond() {
    every { sendString(any()) } just runs
}

/**
 * Configure the mock [WebSocketClient] to receive an IQ and respond, but with a
 * different stanza ID
 */
private fun WebSocketClient.respondToDifferentIq(msgHandler: (Frame) -> Unit) {
    val iqStr = slot<String>()
    every { sendString(capture(iqStr)) } answers {
        val response = iqStr.captured.createResponseIq()
        response.stanzaId = "${response.stanzaId}-wrong"
        msgHandler.invoke(Frame.Text(response.toXML().toString()))
    }
}

/**
 * Configure the mock [WebSocketClient] to wait for 2 IQs and send their
 * responses in the opposite order they were received
 */
private fun WebSocketClient.flipResponses(msgHandler: (Frame) -> Unit) {
    val iqStr = mutableListOf<String>()
    every { sendString(capture(iqStr)) } answers {
        if (iqStr.size == 2) {
            val resp1 = iqStr[0].createResponseIq()
            val resp2 = iqStr[1].createResponseIq()

            msgHandler.invoke(Frame.Text(resp2.toXML().toString()))
            msgHandler.invoke(Frame.Text(resp1.toXML().toString()))
        }
    }
}

/**
 * Configure the mock [WebSocketClient] to wait for an IQ and then shut itself
 * down via [WebSocketClient.stop]
 */
private fun WebSocketClient.stopAfterIq(xmppWsClient: XmppWebSocketClient) {
    every { sendString(any()) } answers {
        xmppWsClient.stop()
    }
}

/**
 * Create a response IQ to the IQ request represented by this String
 */
private fun String.createResponseIq(): IQ {
    val iqReq = SmackXmlSerDes.deserialize(this) as IQ
    return IQ.createResultIQ(iqReq)
}

private fun generateIq(
    toJidStr: String? = null,
    fromJidStr: String? = null
): IQ = ColibriConferenceIQ().apply {
        to = JidCreate.bareFrom(toJidStr ?: Random.nextPrintableAlphaString(5))
        from = JidCreate.bareFrom(fromJidStr ?: Random.nextPrintableAlphaString(5))
    }

private fun Random.nextPrintableAlphaString(length: Int): String {
    return (0 until length).map { nextInt(from = 65, until = 90).toChar() }.joinToString("")
}

private infix fun Stanza?.shouldBeResponseTo(req: IQ) {
    this.shouldNotBeNull()
    from.shouldBeEqualComparingTo(req.to)
    to.shouldBeEqualComparingTo(req.from)
}
