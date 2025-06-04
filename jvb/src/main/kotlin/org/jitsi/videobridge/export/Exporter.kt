/*
 * Copyright @ 2024 - Present, 8x8 Inc
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
package org.jitsi.videobridge.export

import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketAdapter
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest
import org.eclipse.jetty.websocket.client.WebSocketClient
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.websocket.config.WebsocketServiceConfig
import java.net.URI

internal class Exporter(private val url: URI, val logger: Logger) {
    val queue: PacketInfoQueue by lazy {
        PacketInfoQueue(
            "${javaClass.simpleName}-packet-queue",
            TaskPools.IO_POOL,
            this::doHandlePacket,
            1024
        )
    }
    private val recorderWebSocket = object : WebSocketAdapter() {
        override fun onWebSocketClose(statusCode: Int, reason: String?) =
            super.onWebSocketClose(statusCode, reason).also {
                logger.info("Websocket closed with status $statusCode, reason: $reason")
            }

        override fun onWebSocketConnect(session: Session?) = super.onWebSocketConnect(session).also {
            logger.info("Websocket connected: $isConnected")
        }

        override fun onWebSocketError(cause: Throwable?) = super.onWebSocketError(cause).also {
            logger.error("Websocket error", cause)
            webSocketFailures.inc()
        }
    }

    private val serializer = MediaJsonSerializer {
        if (recorderWebSocket.isConnected) {
            recorderWebSocket.remote?.sendString(it.toJson())
                ?: logger.warn("Websocket is connected, but remote is null")
        } else {
            logger.warn("Not connected, cannot send event: $it")
        }
    }

    fun isConnected() = recorderWebSocket.isConnected

    /** Run inside the queue thread, handle a packet. */
    private fun doHandlePacket(packet: PacketInfo): Boolean {
        if (recorderWebSocket.isConnected) {
            serializer.encode(packet.packetAs(), packet.endpointId!!)
        }
        ByteBufferPool.returnBuffer(packet.packet.buffer)
        return true
    }

    fun send(packet: PacketInfo) {
        if (recorderWebSocket.isConnected) {
            queue.add(packet)
        } else {
            ByteBufferPool.returnBuffer(packet.packet.buffer)
        }
    }

    fun start() {
        webSocketClient.connect(recorderWebSocket, url, ClientUpgradeRequest())
    }

    fun stop() {
        recorderWebSocket.session?.close(org.eclipse.jetty.websocket.core.CloseStatus.SHUTDOWN, "closing")
        recorderWebSocket.session?.disconnect()
        queue.close()
    }

    companion object {
        private val webSocketClient = WebSocketClient().apply {
            idleTimeout = WebsocketServiceConfig.config.idleTimeout
            start()
        }

        private val webSocketFailures = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_websocket_failures",
            "Number of websocket connection failures from Exporter"
        )
    }
}
