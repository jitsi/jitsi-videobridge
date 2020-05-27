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
import io.ktor.client.features.websocket.WebSockets
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.videobridge.api.util.JvbApiException
import org.jitsi.videobridge.api.util.SmackXmlSerDes
import org.jitsi.videobridge.api.util.SynchronousWebSocketClient
import org.jivesoftware.smack.packet.IQ

/**
 * JVB Client API for controlling a JVB instance
 */
class JvbApi(jvbHost: String, jvbPort: Int) {
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    private val wsClient = SynchronousWebSocketClient(
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
    @Throws(JvbApiException::class)
    fun sendIqAndGetReply(iq: IQ): IQ {
        return wsClient.sendIqAndGetReply(iq)
    }

    /**
     * Send an [IQ] and don't wait for its response
     */
    fun sendAndForget(iq: IQ) {
        wsClient.sendIqAndForget(iq)
    }
}

/**
 * Send [IQ]s and get their replies via a [SynchronousWebSocketClient].
 */
private fun SynchronousWebSocketClient.sendIqAndGetReply(iq: IQ): IQ {
    val resp = sendAndGetReply(SmackXmlSerDes.serialize(iq))
    return SmackXmlSerDes.deserialize(resp) as IQ
}

/**
 * Send [IQ]s via a [SynchronousWebSocketClient].
 */
private inline fun SynchronousWebSocketClient.sendIqAndForget(iq: IQ) {
    sendAndForget(SmackXmlSerDes.serialize(iq))
}
