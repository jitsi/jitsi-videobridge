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

import io.kotest.assertions.timing.eventually
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import org.jitsi.utils.logging2.LoggerImpl
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

@ExperimentalTime
class WebSocketClientTest : ShouldSpec() {
    override fun isolationMode(): IsolationMode? = IsolationMode.InstancePerLeaf

    private val wsPort = Random.nextInt(1024, 65535).also {
        println("Server running on port $it")
    }

    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private val wsServer = TestWsServer()

    private val server = embeddedServer(Jetty, port = wsPort) {
        wsServer.app(this)
    }

    private val receivedMessages = mutableListOf<Frame>()

    private fun incomingMessageHandler(frame: Frame) {
        receivedMessages.add(frame)
    }

    init {
        thread { server.start() }

        context("sendString") {
            context("when no reply is expected") {
                val ws = WebSocketClient(client, "localhost", wsPort, "/ws/blackhole", ::incomingMessageHandler, LoggerImpl("test"))
                ws.run()
                ws.sendString("hello")
                should("send a message") {
                    eventually(1.seconds) {
                        wsServer.receivedMessages shouldHaveSize 1
                    }
                    wsServer.receivedMessages.first().shouldBeInstanceOf<Frame.Text>()
                    (wsServer.receivedMessages.first() as Frame.Text).readText() shouldBe "hello"
                }
            }
            context("when a reply is expected") {
                val ws = WebSocketClient(client, "localhost", wsPort, "/ws/echo", ::incomingMessageHandler, LoggerImpl("test"))
                ws.run()
                ws.sendString("hello")
                should("invoke the incoming message handler") {
                    eventually(1.seconds) {
                        receivedMessages shouldHaveSize 1
                    }
                    receivedMessages.first().shouldBeInstanceOf<Frame.Text>()
                    (receivedMessages.first() as Frame.Text).readText() shouldBe "hello"
                }
            }
        }
    }
}
