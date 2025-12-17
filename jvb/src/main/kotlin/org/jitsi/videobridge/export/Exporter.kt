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
import org.jitsi.config.JitsiConfig
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.PingEvent
import org.jitsi.mediajson.PongEvent
import org.jitsi.mediajson.SessionEndEvent
import org.jitsi.mediajson.TranscriptionResultEvent
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.websocket.config.WebsocketServiceConfig
import org.json.simple.JSONObject
import java.net.URI
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

internal class Exporter(
    private val url: URI,
    private val httpHeaders: Map<String, String>,
    val logger: Logger,
    private val handleTranscriptionResult: ((TranscriptionResultEvent) -> Unit),
    private val pingEnabled: Boolean = false,
    private val pingIntervalMs: Int = 0,
    private val pingTimeoutMs: Int = 0
) {
    private val isShuttingDown = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectFuture: ScheduledFuture<*>? = null

    // Ping/pong state
    private var pingScheduledFuture: ScheduledFuture<*>? = null
    private var pingTimeoutFuture: ScheduledFuture<*>? = null
    private val nextPingId = AtomicInteger(0)
    private val lastPingSentId = AtomicInteger(0)
    private val lastPongReceivedMs = AtomicLong(0)

    // Instance-level counters for debugState
    private val instancePacketsSent = AtomicLong(0)
    private val instanceWebSocketFailures = AtomicLong(0)
    private val instanceWebSocketInternalErrors = AtomicLong(0)
    private val instanceStarts = AtomicLong(0)
    private val instanceTranscriptsReceived = AtomicLong(0)
    private val instanceOtherMessagesReceived = AtomicLong(0)
    private val instanceParseFailures = AtomicLong(0)

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
                stopPing()
                val internalError = statusCode == 1011
                if (internalError) {
                    webSocketInternalErrors.inc()
                    instanceWebSocketInternalErrors.incrementAndGet()
                }
                if (!isShuttingDown.get()) {
                    // Avoid reconnect loops with no delay in case of an "internal error" (1011)
                    scheduleReconnect(internalError)
                }
            }

        override fun onWebSocketConnect(session: Session?) = super.onWebSocketConnect(session).also {
            logger.info("Websocket connected: $isConnected")
            serializer = initSerializer(this)
            reconnectAttempts.set(0)
            cancelReconnect()
            startPing()
        }

        override fun onWebSocketError(cause: Throwable?) = super.onWebSocketError(cause).also {
            logger.error("Websocket error", cause)
            webSocketFailures.inc()
            instanceWebSocketFailures.incrementAndGet()
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
            val event = Event.parse(message)
            logger.debug { "Received message from websocket: ${event.toJson()}" }

            when (event) {
                is TranscriptionResultEvent -> {
                    transcriptsReceivedCount.inc()
                    instanceTranscriptsReceived.incrementAndGet()
                    handleTranscriptionResult(event)
                }
                is PongEvent -> {
                    val expectedId = lastPingSentId.get()
                    if (event.id == expectedId) {
                        logger.debug { "Received pong with matching id=${event.id}" }
                        lastPongReceivedMs.set(System.currentTimeMillis())
                        // Cancel any pending timeout
                        pingTimeoutFuture?.cancel(false)
                        pingTimeoutFuture = null
                    } else {
                        logger.warn("Received pong with id=${event.id}, expected id=$expectedId")
                    }
                }
                else -> {
                    otherMessagesReceivedCount.inc()
                    instanceOtherMessagesReceived.incrementAndGet()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse incoming websocket message: $message", e)
            parseFailuresCount.inc()
            instanceParseFailures.incrementAndGet()
        }
    }

    private fun sendPing() {
        if (!recorderWebSocket.isConnected || isShuttingDown.get()) {
            return
        }

        val pingId = nextPingId.incrementAndGet()
        val pingEvent = PingEvent(pingId)

        try {
            recorderWebSocket.remote?.sendString(pingEvent.toJson())
            lastPingSentId.set(pingId)
            logger.debug { "Sent ping with id=$pingId" }

            // Schedule timeout check
            pingTimeoutFuture = TaskPools.SCHEDULED_POOL.schedule({
                handlePingTimeout()
            }, pingTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            logger.warn("Failed to send ping message", e)
        }
    }

    private fun handlePingTimeout() {
        logger.warn("Ping timeout, reconnecting websocket")

        // Force reconnect on timeout
        stopPing()
        recorderWebSocket.session?.close(1000, "Ping timeout")
        scheduleReconnect()
    }

    private fun startPing() {
        if (!pingEnabled || pingIntervalMs <= 0) {
            return
        }

        logger.info("Starting ping with interval=$pingIntervalMs ms, timeout=$pingTimeoutMs ms")
        stopPing()

        // Schedule recurring ping
        pingScheduledFuture = TaskPools.SCHEDULED_POOL.scheduleAtFixedRate(
            { sendPing() },
            pingIntervalMs.toLong(),
            pingIntervalMs.toLong(),
            TimeUnit.MILLISECONDS
        )
    }

    private fun stopPing() {
        pingScheduledFuture?.cancel(false)
        pingScheduledFuture = null
        pingTimeoutFuture?.cancel(false)
        pingTimeoutFuture = null
    }

    /** Run inside the queue thread, handle a packet. */
    private fun doHandlePacket(packet: PacketInfo): Boolean {
        if (recorderWebSocket.isConnected) {
            serializer?.encode(packet)
            packetsSentCount.inc()
            instancePacketsSent.incrementAndGet()
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

    private fun scheduleReconnect(
        // Ignore the number of attempts and schedule using the maximum configured delay instead.
        maxDelay: Boolean = false
    ) {
        if (isShuttingDown.get()) {
            return
        }

        val attempt = reconnectAttempts.incrementAndGet()
        maxReconnectAttempts?.let {
            if (attempt > it) {
                logger.warn("Max reconnection attempts ($it) reached, giving up")
                return
            }
        }

        val delayMs = if (maxDelay) Companion.maxDelay.toMillis() else getDelayMs(attempt)
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
        instanceStarts.incrementAndGet()
        webSocketClient.connect(recorderWebSocket, url, createUpgradeRequest())
    }

    fun stop() {
        isShuttingDown.set(true)
        stopPing()
        cancelReconnect()
        if (recorderWebSocket.isConnected) {
            recorderWebSocket.remote?.sendString(SessionEndEvent().toJson())
        }
        recorderWebSocket.session?.close(org.eclipse.jetty.websocket.core.CloseStatus.SHUTDOWN, "closing")
        recorderWebSocket.session?.disconnect()
        queue.close()
    }

    fun debugState(): JSONObject = JSONObject().apply {
        put("url", url.toString())
        put("is_connected", isConnected())
        put("is_shutting_down", isShuttingDown.get())
        put("reconnect_attempts", reconnectAttempts.get())
        put("packets_sent", instancePacketsSent.get())
        put("websocket_failures", instanceWebSocketFailures.get())
        put("websocket_internal_errors", instanceWebSocketInternalErrors.get())
        put("starts", instanceStarts.get())
        put("transcripts_received", instanceTranscriptsReceived.get())
        put("other_messages_received", instanceOtherMessagesReceived.get())
        put("parse_failures", instanceParseFailures.get())
        put("queue_size", queue.size())
        put("ping_enabled", pingEnabled)
        if (pingEnabled) {
            put("ping_interval_ms", pingIntervalMs)
            put("ping_timeout_ms", pingTimeoutMs)
            put("last_pong_received_ms", lastPongReceivedMs.get())
        }
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

        private val webSocketInternalErrors = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_websocket_internal_errors",
            "Number of websocket connection which were connected but closed with code 1011"
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

        private val parseFailuresCount = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_parse_failures",
            "Number of messages that failed to parse"
        )

        private val maxReconnectAttempts: Int? by optionalconfig {
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
            min(baseDelay.toMillis() * 2.0.pow(attempt - 2).toLong(), maxDelay.toMillis())
        }
    }
}
