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

package org.jitsi.videobridge.api.server.v1

import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.WebSocketSession
import io.ktor.http.cio.websocket.readText
import io.ktor.routing.Route
import io.ktor.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.webSocket
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.produce
import org.jitsi.videobridge.api.util.SmackXmlSerDes
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.health.HealthCheckIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.IQ.createErrorResponse
import org.jivesoftware.smack.packet.Message
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.packet.XMPPError

/**
 * Handles incoming Colibri messages over HTTP websocket
 */
fun Route.colibriApi() {
    webSocket("ws") {
        for (stanza in incomingStanzas) {
            if (stanza is IQ) {
                when (stanza) {
                    is HealthCheckIQ -> {
                        val result = call.confManager.handleHealthIq(stanza)
                        sendStanza(result)
                    }
                    is ColibriConferenceIQ -> {
                        val result = call.confManager.handleColibriConferenceIQ(stanza)
                        sendStanza(result)
                    }
                    else -> {
                        sendStanza(IQ.createErrorResponse(stanza, XMPPError.Condition.bad_request))
                    }
                }
            }
            // TODO: other messages we just ignore, can we generate error
            // responses for Message & Presence?
        }
    }
}

/**
 * Parse the incoming websocket [Frame]s into [IQ]s.
 */
private val WebSocketSession.incomingStanzas: ReceiveChannel<Stanza>
    get() = produce {
        for (msg in incoming) {
            when (msg) {
                is Frame.Text -> {
                    val stanza = SmackXmlSerDes.deserialize(msg.readText())
                    send(stanza)
                }
                else -> TODO()
            }
        }
    }

private suspend fun DefaultWebSocketServerSession.sendStanza(stanza: Stanza) {
    outgoing.send(Frame.Text(SmackXmlSerDes.serialize(stanza)))
}
