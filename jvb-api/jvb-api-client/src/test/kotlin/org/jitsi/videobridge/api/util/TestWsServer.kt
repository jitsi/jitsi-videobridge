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

import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.routing.route
import io.ktor.routing.routing
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

/**
 * A ws server configuration which defines multiple ws endpoints that can be
 * used for different test scenarios.
 */
class TestWsServer {
    val receivedMessages = mutableListOf<Frame>()
    val app: Application.() -> Unit = {
        install(WebSockets)

        routing {
            route("ws") {
                // Receive messages and don't respond
                webSocket("blackhole") {
                    for (frame in incoming) {
                        receivedMessages.add(frame)
                    }
                }
                // Receive messages and echo the content back
                webSocket("echo") {
                    for (frame in incoming) {
                        receivedMessages.add(frame)
                        frame as Frame.Text
                        send(Frame.Text(frame.readText()))
                    }
                }
                // Receive messages and echo the content back after a delay
                webSocket("delayecho") {
                    for (frame in incoming) {
                        receivedMessages.add(frame)
                        frame as Frame.Text
                        delay(1000)
                        send(Frame.Text(frame.readText()))
                    }
                }
                // Receive a message and then close the connection after a
                // delay
                webSocket("delayandclose") {
                    for (frame in incoming) {
                        receivedMessages.add(frame)
                        delay(1000)
                        cancel()
                    }
                }
            }
        }
    }
}
