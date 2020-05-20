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

import io.ktor.application.ApplicationCall
import io.ktor.application.call
import io.ktor.http.HttpStatusCode
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.Route
import io.ktor.routing.post
import io.ktor.routing.route
import io.ktor.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.webSocket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jitsi.xmpp.extensions.colibri.ColibriConferenceIQ
import org.jitsi.xmpp.extensions.health.HealthCheckIQ
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import org.jivesoftware.smack.util.PacketParserUtils
import java.io.StringReader
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles incoming Colibri messages over HTTP
 */
fun Route.colibriApi() {
    route("colibri") {
        post {
            val req = call.receive<ColibriConferenceIQ>()
            val result = call.confManager.handleColibriConferenceIQ(req)
            call.respond(result)
        }
    }
    webSocket("ws") {
        while (true) {
            when (val iq = receiveIq()) {
                is HealthCheckIQ -> {
                    val result = call.confManager.handleHealthIq(iq)
                    sendXml(result)
                }
                is ColibriConferenceIQ -> {
                    val result = call.confManager.handleColibriConferenceIQ(iq)
                    sendXml(result)
                }
            }
        }
    }
}

private fun Frame.Text.reader(): StringReader = StringReader(this.readText())

private suspend fun DefaultWebSocketServerSession.receiveIq(): IQ {
    when (val frame = incoming.receive()) {
        is Frame.Text -> {
            @Suppress("BlockingMethodInNonBlockingContext")
            val parser = PacketParserUtils.getParserFor(frame.reader())
            return PacketParserUtils.parseIQ(parser)
        }
        else -> {
            println("Unrecognized WS msg: $frame")
            TODO()
        }
    }
}

private suspend fun DefaultWebSocketServerSession.sendXml(stanza: Stanza) {
    outgoing.send(Frame.Text(stanza.toXML().toString()))
}
