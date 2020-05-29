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

import io.ktor.client.HttpClient
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import org.jitsi.utils.logging2.LoggerImpl
import org.jitsi.utils.logging2.createChildLogger
import org.jivesoftware.smack.packet.IQ
import org.jivesoftware.smack.packet.Stanza
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SynchronousXmppWebSocketClient(
    client: HttpClient,
    host: String,
    port: Int,
    path: String,
    private val requestTimeout: Duration = Duration.ofSeconds(15),
    parentLogger: LoggerImpl
) {
    private val logger = createChildLogger(parentLogger)
    private val requestId = AtomicInteger(1)
    private val responseHandlers: MutableMap<String, CompletableFuture<Stanza>> = ConcurrentHashMap()

    private val wsClient = WebSocketClient(
        client,
        host,
        port,
        path,
        this::handleIncomingMessage,
        logger
    )

    private fun handleIncomingMessage(frame: Frame) {
        when (frame) {
            is Frame.Text -> {
                val stanza = SmackXmlSerDes.deserialize(frame.readText())
                responseHandlers.remove(stanza.stanzaId)?.let {
                    it.complete(stanza)
                }
            }
            else -> TODO("log error?")
        }
    }

    fun sendIqAndGetReply(iq: IQ): Stanza? {
        val id = requestId.getAndIncrement()
        iq.stanzaId = "$id"
        val response = CompletableFuture<Stanza>()
        responseHandlers["$id"] = response
        wsClient.sendString(SmackXmlSerDes.serialize(iq))

        return try {
            response.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            responseHandlers.remove("$id")
            // TODO: handle the specific exception types?
            null
        }
    }

    fun sendIqAndForget(iq: IQ) = wsClient.sendString(SmackXmlSerDes.serialize(iq))

    fun run() = wsClient.run()
}
