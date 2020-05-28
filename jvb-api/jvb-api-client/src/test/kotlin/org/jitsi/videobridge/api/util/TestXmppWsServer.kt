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
import kotlinx.coroutines.delay
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza

class TestXmppWsServer {
    val receivedMessages = mutableListOf<Stanza>()

    val app: Application.() -> Unit = {
        install(WebSockets)

        routing {
            route("ws") {
                // Accepts IQs and, depending on the value of "to", does one
                // of 3 behaviors:
                // If the "to" field contains a "0", the server sends no reply
                // If the "to" field contains a "1", the server sends a non-matching reply
                // If the "to" field contains a "2", the server Sends a correct reply
                webSocket("varied") {
                    for (frame in incoming) {
                        frame as Frame.Text
                        val stanza = SmackXmlSerDes.deserialize(frame.readText())
                        receivedMessages.add(stanza)
                        val (_, idStr) = stanza.to.toString().split("-")
                        val id = Integer.valueOf(idStr)
                        when (stanza) {
                            is IQ -> {
                                when (id % 3) {
                                    0 -> {
                                        // Do nothing
                                    }
                                    1 -> {
                                        // Send 'incorrect' response
                                        val resp = ColibriConferenceIQ()
                                        send(Frame.Text(SmackXmlSerDes.serialize(resp)))
                                    }
                                    2 -> {
                                        // Send valid response
                                        val resp = IQ.createResultIQ(stanza)
                                        send(Frame.Text(SmackXmlSerDes.serialize(resp)))
                                    }
                                }
                            }
                        }
                    }
                }
                // Accepts incoming IQs and responds to them
                webSocket("iqreply") {
                    for (frame in incoming) {
                        frame as Frame.Text
                        val stanza = SmackXmlSerDes.deserialize(frame.readText())
                        receivedMessages.add(stanza)
                        when (stanza) {
                            is IQ -> {
                                val resp = IQ.createResultIQ(stanza)
                                send(Frame.Text(SmackXmlSerDes.serialize(resp)))
                            }
                        }
                    }
                }
                // Accepts incoming IQs and responds to them after a 5 second delay
                webSocket("iqreplywithdelay") {
                    for (frame in incoming) {
                        frame as Frame.Text
                        val stanza = SmackXmlSerDes.deserialize(frame.readText())
                        receivedMessages.add(stanza)
                        when (stanza) {
                            is IQ -> {
                                val resp = IQ.createResultIQ(stanza)
                                delay(5000)
                                send(Frame.Text(SmackXmlSerDes.serialize(resp)))
                            }
                        }
                    }
                }
                // Receives incoming IQs and responds to them, but with a different
                // stanza ID
                webSocket("wrongiqreply") {
                    for (frame in incoming) {
                        frame as Frame.Text
                        val stanza = SmackXmlSerDes.deserialize(frame.readText())
                        receivedMessages.add(stanza)
                        when (stanza) {
                            is IQ -> {
                                val resp = ColibriConferenceIQ()
                                send(Frame.Text(SmackXmlSerDes.serialize(resp)))
                            }
                        }
                    }
                }
            }
        }
    }
}
