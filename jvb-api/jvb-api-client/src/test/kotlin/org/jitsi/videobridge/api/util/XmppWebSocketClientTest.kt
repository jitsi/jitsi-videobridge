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

import LongTest
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.comparables.shouldBeEqualComparingTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.properties.nextPrintableString
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jxmpp.jid.impl.JidCreate
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.ExperimentalTime

@ExperimentalTime
class XmppWebSocketClientTest : ShouldSpec() {
    private val wsPort = Random.nextInt(1024, 65535).also {
        println("Server running on port $it")
    }

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    val wsServer = TestXmppWsServer()

    private val server = embeddedServer(Jetty, port = wsPort) {
        wsServer.app(this)
    }

    init {
        server.start()

        context("lots of concurrent requests") {
            val ws = XmppWebSocketClient(client, "localhost", wsPort, "/ws/iqreply", parentLogger = LoggerImpl("test"))
            ws.run()
            val executor = Executors.newFixedThreadPool(32)
            should("work correctly").config(tags = setOf(LongTest)) {
                val numTasks = 10000
                val latch = CountDownLatch(numTasks)
                repeat(10000) {
                    val iq = generateIq(toJidStr = "client-$it", fromJidStr = "server-$it")
                    launch {
                        if (Random.nextBoolean()) {
                            // Send and wait for reply
                            val result = executor.submit(Callable {
                                val resp = ws.sendIqAndGetReply(iq)
                                latch.countDown()
                                resp
                            }).get()
                            // We can't do this verification inside the task, as it swallows
                            // the exception
                            result.shouldBeResponseTo(iq)
                        } else {
                            // Send and forget
                            executor.submit {
                                ws.sendIqAndForget(iq)
                                latch.countDown()
                            }
                        }
                    }
                }
            }
        }
        /**
         * This test is based on the behavior defined in TestXmppWsServer, where a value in
         * the 'to' field of the stanza determines a server behavior.  Based on that scheme,
         * the tests launches lots of concurrent requests and knows what to expect for
         * the response (if anything) so it can be verified.  The primary goal here is to
         * verify that there's no mismatching of requests and responses, even with
         * various timeouts.
         */
        context("lots of concurrent requests with different results") {
            val ws = XmppWebSocketClient(client, "localhost", wsPort, "/ws/varied", requestTimeout = Duration.ofSeconds(5), parentLogger = LoggerImpl("test"))
            ws.run()
            val executor = Executors.newFixedThreadPool(32)
            should("work correctly").config(tags = setOf(LongTest)) {
                repeat(100) {
                    val iq = generateIq(toJidStr = "client-$it", fromJidStr = "server-$it")
                    launch(executor.asCoroutineDispatcher()) {
                        val resp = ws.sendIqAndGetReply(iq)
                        when (it % 3) {
                            0 -> {
                                // Do nothing, no response expected
                            }
                            1 -> {
                                // Incorrect response will be sent, should have null
                                resp shouldBe null
                            }
                            2 -> {
                                // Proper response expected
                                withClue("request $it should get a response") {
                                    resp.shouldBeResponseTo(iq)
                                }
                            }
                        }
                    }
                }
            }
        }
        context("sendIqAndGetReply") {
            context("without any timeout") {
                val ws = XmppWebSocketClient(client, "localhost", wsPort, "/ws/iqreply", parentLogger = LoggerImpl("test"))
                ws.run()
                should("work correctly") {
                    val iq = generateIq()
                    val resp = ws.sendIqAndGetReply(iq)
                    resp.shouldBeResponseTo(iq)
                }
            }
            context("when the request times out") {
                val ws = XmppWebSocketClient(client, "localhost", wsPort, "/ws/iqreplywithdelay", requestTimeout = Duration.ofMillis(500), parentLogger = LoggerImpl("test"))
                ws.run()
                should("return null for the response") {
                    val iq = generateIq()
                    val resp = ws.sendIqAndGetReply(iq)
                    resp shouldBe null
                }
            }
            context("when an incorrect response is sent") {
                val ws = XmppWebSocketClient(client, "localhost", wsPort, "/ws/wrongiqreply", requestTimeout = Duration.ofMillis(500), parentLogger = LoggerImpl("test"))
                ws.run()
                should("return null for the response") {
                    val iq = generateIq()
                    val resp = ws.sendIqAndGetReply(iq)
                    resp shouldBe null
                }
            }
        }
        context("stop") {
            context("when an IQ response is pending") {
                val ws = XmppWebSocketClient(client, "localhost", wsPort, "/ws/iqindefinitedelay", requestTimeout = Duration.ofSeconds(3), parentLogger = LoggerImpl("test"))
                ws.run()
                val iq = generateIq()
                val resp = Executors.newSingleThreadExecutor().submit(Callable { ws.sendIqAndGetReply(iq) })
                ws.stop()
                should("clean up properly") {
                    // This time should be the same as the request timeout given to the client
                    resp.get(3, TimeUnit.SECONDS) shouldBe null
                }
            }
        }
    }
}

private fun generateIq(
    toJidStr: String? = null,
    fromJidStr: String? = null
): IQ = ColibriConferenceIQ().apply {
        to = JidCreate.bareFrom(toJidStr ?: Random.nextPrintableString(5))
        from = JidCreate.bareFrom(fromJidStr ?: Random.nextPrintableString(5))
    }

private fun Stanza?.shouldBeResponseTo(req: IQ) {
    this.shouldNotBeNull()
    from.shouldBeEqualComparingTo(req.to)
    to.shouldBeEqualComparingTo(req.from)
}
