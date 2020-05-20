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
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jitsi.utils.logging2.LoggerImpl
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.milliseconds

@ExperimentalTime
class SynchronousWebSocketClientTest : ShouldSpec() {
    private val wsPort = Random.nextInt(1024, 65535).also {
        println("Server running on port $it")
    }

    /**
     * There's no test-harness for a websocket client in ktor yet
     * https://github.com/ktorio/ktor/issues/1413) so we have to run an actual
     * client and server to test it.  The server is run on a random port to
     * decrease the chances of conflicts if tests are run in parallel on the
     * same machine.
     */
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private val server = embeddedServer(Jetty, port = wsPort) {
        testModule()
    }

    private val debugEnabled = false

    init {
        thread { server.start() }

        context("Multiple, concurrent requests") {
            val ws = SynchronousWebSocketClient(client, "localhost", wsPort, "/ws/echo", LoggerImpl("test"))
            ws.run()
            should("still have responses match up correctly") {
                launch {
                    delay(100)
                    debug("Sending req 1")
                    val resp = ws.sendAndGetReply("hello 1")
                    debug("Got resp 1")
                    resp shouldBe "hello 1"
                }
                launch {
                    debug("Sending req 2")
                    val resp = ws.sendAndGetReply("hello 2")
                    debug("Got resp 2")
                    resp shouldBe "hello 2"
                }
                launch {
                    debug("Sending and forgetting 3")
                    ws.sendAndForget("hello 3")
                }
                launch {
                    debug("Sending req 4")
                    val resp = ws.sendAndGetReply("hello 4")
                    debug("Got resp 4")
                    resp shouldBe "hello 4"
                }
                launch {
                    delay(500)
                    debug("Sending req 5")
                    val resp = ws.sendAndGetReply("hello 5")
                    debug("Got resp 5")
                    resp shouldBe "hello 5"
                }
                launch {
                    debug("Sending and forgetting 6")
                    ws.sendAndForget("hello 6")
                }
            }
            ws.stop()
        }
        context("sendAndForget") {
            should("not block the caller").config(timeout = 200.milliseconds) {
                val ws = SynchronousWebSocketClient(client, "localhost", wsPort, "/ws/delay", LoggerImpl("test"))
                ws.run()
                repeat (5) {
                    ws.sendAndForget("$it")
                }
            }
        }
    }

    private fun debug(msg: String) {
        if (debugEnabled) {
            println(msg)
        }
    }
}

