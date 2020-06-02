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

/**
 * Send and receive XMPP messages over websockets.  Supports sending [IQ]s and
 * waiting for their replies (by correlating stanza IDs) or
 * 'sending-and-forgetting': sending an IQ but not waiting for its response.
 */
class XmppWebSocketClient(
    /**
     * The underlying [HttpClient] to use.  It must support websockets.
     */
    client: HttpClient,
    /**
     * The host we'll use for establishing the websocket connection
     */
    host: String,
    /**
     * The port we'll use for establishing the websocket connection
     */
    port: Int,
    /**
     * The path to the websocket endpoint
     */
    path: String,
    /**
     * How long we'll wait in [#sendIqAndGetReply] for a response before
     * timing out
     */
    private val requestTimeout: Duration = Duration.ofSeconds(15),
    parentLogger: LoggerImpl
) {
    private val logger = createChildLogger(parentLogger)

    /**
     * A monotonically incremening counter we'll use for the stanza ID to
     * correlate requests and responses
     */
    private val requestId = AtomicInteger(1)

    /**
     * A map of stanza ID -> handlers waiting for a response to a stanza with
     * that ID
     */
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
            else -> logger.error("Received a non-test websocket frame: $frame")
        }
    }

    /**
     * Send an [IQ] and wait up to [requestTimeout] for a response, returns
     * null if it times out.
     */
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
            null
        }
    }

    /**
     * Send an [IQ] asynchronously and don't wait for a response
     */
    fun sendIqAndForget(iq: IQ) = wsClient.sendString(SmackXmlSerDes.serialize(iq))

    /**
     * Connect the websocket client
     */
    fun run() = wsClient.run()

    /**
     * Disconnect the websocket client
     */
    fun stop() = wsClient.stop()
}
