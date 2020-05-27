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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ShouldSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.features.websocket.WebSockets
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import org.jitsi.utils.logging2.LoggerImpl
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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

    init {
        thread { server.start() }

        context("Lots of concurrent synchronous requests") {
            val ws = SynchronousWebSocketClient(client, "localhost", wsPort, "/ws/echo", LoggerImpl("test"))
            ws.run()
            val executor = Executors.newFixedThreadPool(32)
            should("work correctly") {
                val numTasks = 10000
                val latch = CountDownLatch(numTasks)
                repeat(numTasks) {
                    val result = executor.submit(Callable {
                        val resp = ws.sendAndGetReply("$it")
                        latch.countDown()
                        resp
                    }).get()
                    // We can't do this verification inside the task, as it swallows
                    // the exception
                    result shouldBe "$it"
                }
                latch.await()
            }
        }
        context("sendAndForget") {
            val ws = SynchronousWebSocketClient(client, "localhost", wsPort, "/ws/delay", LoggerImpl("test"))
            ws.run()
            // The 'delay' websocket endpoint delays for 100ms before responding,
            // so we can verify these calls happen faster
            should("not block the caller").config(timeout = 10.milliseconds) {
                repeat(5) {
                    ws.sendAndForget("$it")
                }
            }
        }
        context("sendAndGetReply") {
            context("when the request takes longer than the timeout") {
                val ws = SynchronousWebSocketClient(
                    client,
                    "localhost",
                    wsPort,
                    "/ws/delay",
                    LoggerImpl("test"),
                    Duration.ofMillis(500)
                )
                ws.run()
                should("throw an exception") {
                    shouldThrow<JvbApiTimeoutException> {
                        ws.sendAndGetReply("hello")
                    }
                }
            }
        }
        context("when the ws connection is closed while waiting for a reply") {
            val ws = SynchronousWebSocketClient(client, "localhost", wsPort, "/ws/delayandclose", LoggerImpl("test"))
            ws.run()
            should("get an exception") {
                shouldThrow<JvbApiException> {
                    ws.sendAndGetReply("hello")
                }
            }
        }
    }
}
