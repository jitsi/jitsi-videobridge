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

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.WebSocketAdapter
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest
import org.eclipse.jetty.websocket.client.WebSocketClient
import org.jitsi.config.JitsiConfig
import org.jitsi.metaconfig.config
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.websocket.config.WebsocketServiceConfig
import java.net.URI
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.pow
import kotlin.time.Duration

internal class Exporter(
    private val url: URI,
    private val httpHeaders: Map<String, String>,
    val logger: Logger,
    private val handleTranscriptionResult: ((JsonNode) -> Unit)
) {
    private val isShuttingDown = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectFuture: ScheduledFuture<*>? = null

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
                if (!isShuttingDown.get()) {
                    scheduleReconnect()
                }
            }

        override fun onWebSocketConnect(session: Session?) = super.onWebSocketConnect(session).also {
            logger.info("Websocket connected: $isConnected")
            serializer = initSerializer(this)
            reconnectAttempts.set(0)
            cancelReconnect()
        }

        override fun onWebSocketError(cause: Throwable?) = super.onWebSocketError(cause).also {
            logger.error("Websocket error", cause)
            webSocketFailures.inc()
            if (!isShuttingDown.get()) {
                scheduleReconnect()
            }
        }

        override fun onWebSocketText(message: String?) = super.onWebSocketText(message).also {
            message?.let { handleIncomingMessage(it) }
        }
    }

    private var serializer: MediaJsonSerializer? = null

    private fun initSerializer(ws: WebSocketAdapter) = MediaJsonSerializer {
        if (ws.isConnected) {
            ws.remote?.sendString(it.toJson())
                ?: logger.warn("Websocket is connected, but remote is null")
        } else {
            logger.warn("Not connected, cannot send event: $it")
        }
    }

    fun isConnected() = recorderWebSocket.isConnected

    private fun handleIncomingMessage(message: String) {
        try {
            val jsonNode = objectMapper.readTree(message)
            logger.debug { "Received message from websocket: $jsonNode" }

            if (jsonNode.get("type")?.asText() == "transcription-result") {
                transcriptsReceivedCount.inc()
                handleTranscriptionResult(jsonNode)
            } else {
                otherMessagesReceivedCount.inc()
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse incoming websocket message: $message", e)
        }
    }

    /** Run inside the queue thread, handle a packet. */
    private fun doHandlePacket(packet: PacketInfo): Boolean {
        if (recorderWebSocket.isConnected) {
            serializer?.encode(packet.packetAs(), packet.endpointId!!)
            packetsSentCount.inc()
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

    private fun scheduleReconnect() {
        if (isShuttingDown.get()) {
            return
        }

        val attempt = reconnectAttempts.incrementAndGet()
        if (attempt > maxReconnectAttempts) {
            logger.warn("Max reconnection attempts ($maxReconnectAttempts) reached, giving up")
            return
        }

        val delayMs = getDelayMs(attempt)
        logger.info("Scheduling reconnection attempt $attempt in $delayMs ms")

        cancelReconnect()
        reconnectFuture = TaskPools.SCHEDULED_POOL.schedule({
            if (!isShuttingDown.get()) {
                try {
                    logger.info("Attempting reconnection (attempt $attempt)")
                    webSocketClient.connect(recorderWebSocket, url, createUpgradeRequest())
                } catch (e: Exception) {
                    logger.warn("Reconnection attempt $attempt failed", e)
                    scheduleReconnect()
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelReconnect() {
        reconnectFuture?.cancel(false)
        reconnectFuture = null
    }

    private fun createUpgradeRequest() = ClientUpgradeRequest().apply {
        httpHeaders.forEach { (name, value) ->
            setHeader(name, value)
        }
    }

    fun start() {
        isShuttingDown.set(false)
        startsCount.inc()
        webSocketClient.connect(recorderWebSocket, url, createUpgradeRequest())
    }

    fun stop() {
        isShuttingDown.set(true)
        cancelReconnect()
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

        private val startsCount = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_starts",
            "Number of times Exporter has been started"
        )

        private val packetsSentCount = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_packets_sent",
            "Number of packets sent by Exporter"
        )

        private val transcriptsReceivedCount = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_transcripts_received",
            "Number of transcription results received by Exporter"
        )

        private val otherMessagesReceivedCount = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_other_messages_received",
            "Number of non-transcription messages received by Exporter"
        )

        private val objectMapper = jacksonObjectMapper()

        private val maxReconnectAttempts: Int by config {
            "videobridge.exporter.max-reconnect-attempts".from(JitsiConfig.newConfig)
        }

        private val baseDelay: Duration by config {
            "videobridge.exporter.base-delay".from(JitsiConfig.newConfig)
        }

        private val maxDelay: Duration by config {
            "videobridge.exporter.max-delay".from(JitsiConfig.newConfig)
        }

        // 0, base, base * 2, max at 30 seconds
        private fun getDelayMs(attempt: Int) = if (attempt == 1) {
            0
        } else {
            min(baseDelay.inWholeMilliseconds * 2.0.pow(attempt - 2).toLong(), maxDelay.inWholeMilliseconds)
        }
    }
}
