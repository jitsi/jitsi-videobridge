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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import org.eclipse.jetty.websocket.api.Callback
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest
import org.eclipse.jetty.websocket.client.WebSocketClient
import org.jitsi.config.JitsiConfig
import org.jitsi.mediajson.Event
import org.jitsi.mediajson.InfoEvent
import org.jitsi.mediajson.MediaEvent
import org.jitsi.mediajson.PingEvent
import org.jitsi.mediajson.PongEvent
import org.jitsi.mediajson.SessionEndEvent
import org.jitsi.mediajson.SourcesEvent
import org.jitsi.mediajson.TranscriptionResultEvent
import org.jitsi.metaconfig.config
import org.jitsi.metaconfig.optionalconfig
import org.jitsi.nlj.PacketInfo
import org.jitsi.nlj.rtp.AudioRtpPacket
import org.jitsi.nlj.util.PacketInfoQueue
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.PotentialPacketHandler
import org.jitsi.videobridge.metrics.VideobridgeMetricsContainer
import org.jitsi.videobridge.relay.RelayConfig
import org.jitsi.videobridge.util.ByteBufferPool
import org.jitsi.videobridge.util.TaskPools
import org.jitsi.videobridge.version.JvbVersionService
import org.jitsi.videobridge.websocket.config.WebsocketServiceConfig
import org.jitsi.xmpp.extensions.colibri2.Connect
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
    /** Handles a translated-audio media event received back from the peer. */
    private val handleMediaEvent: ((MediaEvent) -> Unit),
    /** Resolves an audio SSRC to its source name, used to filter outbound audio by this connect's exports. */
    private val getAudioSourceName: (Long) -> String?,
    private val pingEnabled: Boolean = false,
    private val pingIntervalMs: Int = 0,
    private val pingTimeoutMs: Int = 0,
    private val type: Connect.Types = Connect.Types.RECORDER,
    /** Source names to export (send out), communicated to the peer via a [SourcesEvent]. */
    @Volatile private var exports: List<String> = emptyList(),
    /** Source names requested (to receive back), communicated to the peer via a [SourcesEvent]. */
    @Volatile private var requests: List<String> = emptyList()
) : PotentialPacketHandler {
    private val isShuttingDown = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var reconnectFuture: ScheduledFuture<*>? = null

    private var connectionOpenedAtMs: Long = 0

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
    private val instanceMediaEventsReceived = AtomicLong(0)
    private val instanceOtherMessagesReceived = AtomicLong(0)
    private val instanceParseFailures = AtomicLong(0)
    private val instanceInfoReceived = AtomicLong(0)

    val queue: PacketInfoQueue by lazy {
        PacketInfoQueue(
            "${javaClass.simpleName}-packet-queue",
            TaskPools.IO_POOL,
            this::doHandlePacket,
            1024
        )
    }
    inner class RecorderWebSocket : Session.Listener.AutoDemanding {
        @Volatile
        var session: Session? = null

        val isConnected: Boolean
            get() = session != null

        override fun onWebSocketClose(statusCode: Int, reason: String?) {
            val openedAt = connectionOpenedAtMs
            val stableConnection = openedAt > 0 &&
                System.currentTimeMillis() - openedAt > stableConnectionThreshold.toMillis()
            if (stableConnection) {
                reconnectAttempts.set(0)
                connectionOpenedAtMs = 0
            }

            session = null
            logger.info("Websocket closed with status $statusCode, reason: $reason")
            stopPing()
            val internalError = statusCode == 1011
            if (internalError) {
                webSocketInternalErrors.inc()
                instanceWebSocketInternalErrors.incrementAndGet()
            }
            if (!isShuttingDown.get()) {
                scheduleReconnect()
            }
        }

        override fun onWebSocketOpen(session: Session) {
            this.session = session
            connectionOpenedAtMs = System.currentTimeMillis()
            logger.info("Websocket connected: $isConnected")
            serializer = initSerializer()
            cancelReconnect()
            startPing()
            // Declare our exported/requested sources to the peer, if any, now that the connection is up.
            if (exports.isNotEmpty() || requests.isNotEmpty()) {
                sendSources()
            }
            sendInfo()
        }

        override fun onWebSocketError(cause: Throwable?) {
            logger.error("Websocket error", cause)
            webSocketFailures.inc()
            instanceWebSocketFailures.incrementAndGet()
            if (!isShuttingDown.get()) {
                scheduleReconnect()
            }
        }

        override fun onWebSocketText(message: String) {
            handleIncomingMessage(message)
        }
    }

    private val recorderWebSocket = RecorderWebSocket()

    private var serializer: MediaJsonSerializer? = null

    private fun initSerializer() = MediaJsonSerializer(getAudioSourceName) {
        val session = recorderWebSocket.session
        if (session != null) {
            session.sendText(it.toJson(), Callback.NOOP)
        } else {
            logger.warn("Not connected, cannot send event: $it")
        }
    }

    fun isConnected() = recorderWebSocket.isConnected

    /**
     * Whether to forward this packet to this exporter: it must be audio from a source we export. An empty [exports]
     * list means "export all audio"; otherwise only audio from a source named in [exports] is wanted.
     */
    override fun wants(packet: PacketInfo): Boolean {
        if (!isConnected()) return false
        val audioPacket = packet.packet as? AudioRtpPacket ?: return false
        // Avoid resolving the source name in the common "export everything" case.
        if (exports.isEmpty()) return true
        val sourceName = getAudioSourceName(audioPacket.ssrc)
        return sourceName != null && sourceName in exports
    }

    /**
     * Apply an update to the connect with this exporter's URL. Only the exported/requested source names may change
     * on a running exporter; changes to other parameters are rejected by [ExporterWrapper] before reaching here.
     */
    fun update(exports: List<String>, requests: List<String>) {
        logger.info("Updating exports=$exports requests=$requests")
        this.exports = exports
        this.requests = requests
        // If we're connected, propagate the change now; otherwise it will be sent when the connection opens.
        sendSources()
    }

    /** Send the current exported/requested source names to the peer. No-op if not currently connected. */
    private fun sendSources() {
        val session = recorderWebSocket.session ?: return
        logger.info("Sending sources: exports=$exports requests=$requests")
        session.sendText(SourcesEvent(exports, requests).toJson(), Callback.NOOP)
    }

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
                is MediaEvent -> {
                    mediaEventsReceivedCount.inc()
                    instanceMediaEventsReceived.incrementAndGet()
                    handleMediaEvent(event)
                }
                is InfoEvent -> {
                    infoReceivedCount.inc()
                    instanceInfoReceived.incrementAndGet()
                    logger.info("Received InfoEvent: ${event.toJson()}")
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

    /**
     * Send an informational message describing this application to the peer, once the connection
     * is up. Used for runtime observability (the peer logs what it received). No-op if disconnected.
     */
    private fun sendInfo() {
        val session = recorderWebSocket.session ?: return
        val info = InfoEvent()
            .put("application", "jitsi-videobridge")
            .put("version", JvbVersionService.instance.currentVersion.toString())
        RelayConfig.config.region?.let { info.put("region", it) }
        try {
            logger.info("Sending info to transcriber: ${info.toJson()}")
            session.sendText(info.toJson(), Callback.NOOP)
        } catch (e: Exception) {
            logger.warn("Failed to send info message", e)
        }
    }

    private fun sendPing() {
        if (!recorderWebSocket.isConnected || isShuttingDown.get()) {
            return
        }

        val pingId = nextPingId.incrementAndGet()
        val pingEvent = PingEvent(pingId)

        try {
            recorderWebSocket.session?.sendText(pingEvent.toJson(), Callback.NOOP)
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
        recorderWebSocket.session?.close(1000, "Ping timeout", Callback.NOOP)
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

    override fun send(packet: PacketInfo) {
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
        maxReconnectAttempts?.let {
            if (attempt > it) {
                logger.warn("Max reconnection attempts ($it) reached, giving up")
                return
            }
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
        instanceStarts.incrementAndGet()
        webSocketClient.connect(recorderWebSocket, url, createUpgradeRequest())
    }

    fun stop() {
        isShuttingDown.set(true)
        stopPing()
        cancelReconnect()
        if (recorderWebSocket.isConnected) {
            recorderWebSocket.session?.sendText(SessionEndEvent().toJson(), Callback.NOOP)
        }
        recorderWebSocket.session?.close(
            org.eclipse.jetty.websocket.core.CloseStatus.SHUTDOWN,
            "closing",
            Callback.NOOP
        )
        recorderWebSocket.session?.disconnect()
        queue.close()
    }

    fun debugState(): ObjectNode = JsonNodeFactory.instance.objectNode().apply {
        put("url", url.toString())
        put("type", type.toString().lowercase())
        putArray("exports").apply { exports.forEach { add(it) } }
        putArray("requests").apply { requests.forEach { add(it) } }
        put("is_connected", isConnected())
        put("is_shutting_down", isShuttingDown.get())
        put("reconnect_attempts", reconnectAttempts.get())
        put("packets_sent", instancePacketsSent.get())
        put("websocket_failures", instanceWebSocketFailures.get())
        put("websocket_internal_errors", instanceWebSocketInternalErrors.get())
        put("starts", instanceStarts.get())
        put("transcripts_received", instanceTranscriptsReceived.get())
        put("media_events_received", instanceMediaEventsReceived.get())
        put("other_messages_received", instanceOtherMessagesReceived.get())
        put("parse_failures", instanceParseFailures.get())
        put("info_received", instanceInfoReceived.get())
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

        private val mediaEventsReceivedCount = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_media_events_received",
            "Number of media (translated audio) events received by Exporter"
        )

        private val otherMessagesReceivedCount = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_other_messages_received",
            "Number of non-transcription messages received by Exporter"
        )

        private val parseFailuresCount = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_parse_failures",
            "Number of messages that failed to parse"
        )

        private val infoReceivedCount = VideobridgeMetricsContainer.instance.registerCounter(
            "exporter_info_received",
            "Number of info messages received by Exporter"
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

        val stableConnectionThreshold: Duration by config {
            "videobridge.exporter.stable-connection-threshold".from(JitsiConfig.newConfig)
        }

        // 0, base, base * 2, max at 30 seconds
        private fun getDelayMs(attempt: Int) = if (attempt == 1) {
            0
        } else {
            min(baseDelay.toMillis() * 2.0.pow(attempt - 2).toLong(), maxDelay.toMillis())
        }
    }
}
