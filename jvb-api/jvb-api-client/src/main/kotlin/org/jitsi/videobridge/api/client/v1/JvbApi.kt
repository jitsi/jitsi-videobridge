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

@file:Suppress("unused")

package org.jitsi.videobridge.api.client.v1

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.api.util.XmppWebSocketClient
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza

/**
 * JVB Client API for controlling a JVB instance
 */
class JvbApi(jvbHost: String, jvbPort: Int) {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private val wsClient = XmppWebSocketClient(
        client,
        host = jvbHost,
        port = jvbPort,
        path = "/v1/ws",
        parentLogger = LoggerImpl(JvbApi::class.qualifiedName)
    ).also {
        it.run()
    }

    /**
     * Send an [IQ] and return the response [IQ].  Call is synchronous.
     */
    fun sendIqAndGetReply(iq: IQ): Stanza? {
        return wsClient.sendIqAndGetReply(iq)
    }

    /**
     * Send an [IQ] and don't wait for its response
     */
    fun sendAndForget(iq: IQ) {
        wsClient.sendIqAndForget(iq)
    }
}
