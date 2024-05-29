/*
 * Copyright @ 2017 Atlassian Pty Ltd
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
package org.jitsi.videobridge.relay

import org.eclipse.jetty.websocket.client.ClientUpgradeRequest
import org.eclipse.jetty.websocket.client.WebSocketClient
import org.eclipse.jetty.websocket.core.CloseStatus
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.AbstractEndpointMessageTransport
import org.jitsi.videobridge.VersionConfig
import org.jitsi.videobridge.datachannel.DataChannel
import org.jitsi.videobridge.datachannel.DataChannelStack.DataChannelMessageListener
import org.jitsi.videobridge.datachannel.protocol.DataChannelMessage
import org.jitsi.videobridge.datachannel.protocol.DataChannelStringMessage
import org.jitsi.videobridge.message.AddReceiverMessage
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.ClientHelloMessage
import org.jitsi.videobridge.message.EndpointConnectionStatusMessage
import org.jitsi.videobridge.message.EndpointMessage
import org.jitsi.videobridge.message.EndpointStats
import org.jitsi.videobridge.message.ServerHelloMessage
import org.jitsi.videobridge.message.SourceVideoTypeMessage
import org.jitsi.videobridge.message.VideoTypeMessage
import org.jitsi.videobridge.metrics.VideobridgeMetrics
import org.jitsi.videobridge.websocket.ColibriWebSocket
import org.jitsi.videobridge.websocket.config.WebsocketServiceConfig
import org.json.simple.JSONObject
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for a [Relay]. Supports two underlying transport mechanisms --
 * WebRTC data channels and {@code WebSocket}s.
 */
class RelayMessageTransport(
    private val relay: Relay,
    private val eventHandler: EndpointMessageTransportEventHandler,
    parentLogger: Logger
) : AbstractEndpointMessageTransport(parentLogger), ColibriWebSocket.EventHandler, DataChannelMessageListener {
    /**
     * The last connected/accepted web-socket by this instance, if any.
     */
    private var webSocket: ColibriWebSocket? = null

    /**
     * For active websockets, the URL that was connected to.
     */
    private var url: String? = null

    /**
     * Use to synchronize access to [webSocket]
     */
    private val webSocketSyncRoot = Any()

    /**
     * Whether the last active transport channel (i.e. the last to receive a
     * message from the remote endpoint) was the web socket (if `true`),
     * or the WebRTC data channel (if `false`).
     */
    private var webSocketLastActive = false

    private var dataChannel = WeakReference<DataChannel>(null)

    private val numOutgoingMessagesDropped = AtomicInteger(0)

    /**
     * The number of sent message by type.
     */
    private val sentMessagesCounts: MutableMap<String, AtomicLong> = ConcurrentHashMap()

    /**
     * Connect the bridge channel message to the websocket URL specified
     */
    fun connectToWebsocket(url: String) {
        if (this.url != null && this.url == url) {
            return
        }
        this.url = url

        doConnect()
    }

    private fun doConnect() {
        val url = this.url ?: throw IllegalStateException("Cannot connect Relay transport when no URL set")

        webSocket?.let {
            logger.warn("Re-connecting while webSocket != null, possible leak.")
            webSocket = null
        }

        ColibriWebSocket(relay.id, this).also {
            webSocketClient.connect(it, URI(url), ClientUpgradeRequest())
            synchronized(webSocketSyncRoot) {
                webSocket = it
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun notifyTransportChannelConnected() {
        relay.relayMessageTransportConnected()
    }

    /**
     * {@inheritDoc}
     */
    override fun clientHello(message: ClientHelloMessage): BridgeChannelMessage? {
        // ClientHello was introduced for functional testing purposes. It
        // triggers a ServerHello response from Videobridge. The exchange
        // reveals (to the peer) that the transport channel between the
        // remote relay and the Videobridge is operational.
        // We take care to send the reply using the same transport channel on
        // which we received the request..
        return createServerHello()
    }

    override fun serverHello(message: ServerHelloMessage): BridgeChannelMessage? {
        if (message.version?.equals(relay.conference.videobridge.version.toString()) != true) {
            logger.warn {
                "Received ServerHelloMessage with version ${message.version}, but " +
                    "this server is version ${relay.conference.videobridge.version}"
            }
        } else {
            logger.info { "Received ServerHelloMessage, version ${message.version}" }
        }
        return null
    }

    /**
     * This message indicates that a remote bridge wishes to receive video
     * with certain constraints for a specific endpoint.
     * @param message
     * @return
     */
    override fun addReceiver(message: AddReceiverMessage): BridgeChannelMessage? {
        val sourceName = message.sourceName ?: run {
            logger.error("Received AddReceiverMessage for with sourceName = null")
            return null
        }
        val ep = relay.conference.findSourceOwner(sourceName) ?: run {
            logger.warn("Received AddReceiverMessage for unknown or non-local: $sourceName")
            return null
        }

        ep.addReceiver(relay.id, sourceName, message.videoConstraints)
        return null
    }

    override fun videoType(message: VideoTypeMessage): BridgeChannelMessage? {
        logger.error("Relay: unexpected video type message: ${message.toJson()}")
        return null
    }

    override fun sourceVideoType(message: SourceVideoTypeMessage): BridgeChannelMessage? {
        val epId = message.endpointId
        if (epId == null) {
            logger.warn("Received SourceVideoTypeMessage over relay channel with no endpoint ID")
            return null
        }

        val ep = relay.getEndpoint(epId)

        if (ep == null) {
            logger.warn("Received SourceVideoTypeMessage for unknown epId $epId")
            return null
        }

        ep.setVideoType(message.sourceName, message.videoType)

        relay.conference.sendMessageFromRelay(message, false, relay.meshId)

        return null
    }

    override fun unhandledMessage(message: BridgeChannelMessage) {
        logger.warn("Received a message with an unexpected type: ${message.javaClass.simpleName}")
    }

    /**
     * Sends a string via a particular transport channel.
     * @param dst the transport channel.
     * @param message the message to send.
     */
    override fun sendMessage(dst: Any?, message: BridgeChannelMessage) {
        super.sendMessage(dst, message) // Log message
        if (dst is ColibriWebSocket) {
            sendMessage(dst, message)
        } else if (dst is DataChannel) {
            sendMessage(dst, message)
        } else {
            throw IllegalArgumentException("unknown transport:$dst")
        }
    }

    /**
     * Sends a string via a particular [DataChannel].
     * @param dst the data channel to send through.
     * @param message the message to send.
     */
    private fun sendMessage(dst: DataChannel, message: BridgeChannelMessage) {
        dst.sendString(message.toJson())
        VideobridgeMetrics.dataChannelMessagesSent.inc()
    }

    /**
     * Sends a string via a particular [ColibriWebSocket] instance.
     * @param dst the [ColibriWebSocket] through which to send the message.
     * @param message the message to send.
     */
    private fun sendMessage(dst: ColibriWebSocket, message: BridgeChannelMessage) {
        dst.sendString(message.toJson())
        VideobridgeMetrics.colibriWebSocketMessagesSent.inc()
    }

    override fun onDataChannelMessage(dataChannelMessage: DataChannelMessage?) {
        webSocketLastActive = false
        VideobridgeMetrics.dataChannelMessagesReceived.inc()
        if (dataChannelMessage is DataChannelStringMessage) {
            onMessage(dataChannel.get(), dataChannelMessage.data)
        }
    }

    /**
     * {@inheritDoc}
     */
    public override fun sendMessage(msg: BridgeChannelMessage) {
        val dst = getActiveTransportChannel()
        if (dst == null) {
            logger.debug("No available transport channel, can't send a message")
            numOutgoingMessagesDropped.incrementAndGet()
        } else {
            sentMessagesCounts.computeIfAbsent(msg.javaClass.simpleName) { AtomicLong() }.incrementAndGet()
            sendMessage(dst, msg)
        }
    }

    /**
     * @return the active transport channel for this
     * [RelayMessageTransport] (either the [.webSocket], or
     * the WebRTC data channel represented by a [DataChannel]).
     *
     * The "active" channel is determined based on what channels are available,
     * and which one was the last to receive data. That is, if only one channel
     * is available, it will be returned. If two channels are available, the
     * last one to have received data will be returned. Otherwise, `null`
     * will be returned.
     */
    // TODO(brian): seems like it'd be nice to have the websocket and datachannel
    // share a common parent class (or, at least, have a class that is returned
    // here and provides a common API but can wrap either a websocket or
    // datachannel)
    private fun getActiveTransportChannel(): Any? {
        val dataChannel = dataChannel.get()
        val webSocket = webSocket
        var dst: Any? = null
        if (webSocketLastActive) {
            dst = webSocket
        }

        // Either the socket was not the last active channel,
        // or it has been closed.
        if (dst == null) {
            if (dataChannel != null && dataChannel.isReady) {
                dst = dataChannel
            }
        }

        // Maybe the WebRTC data channel is the last active, but it is not
        // currently available. If so, and a web-socket is available -- use it.
        if (dst == null && webSocket != null) {
            dst = webSocket
        }
        return dst
    }

    override val isConnected: Boolean
        get() = getActiveTransportChannel() != null

    val isActive: Boolean
        get() = url != null

    /**
     * {@inheritDoc}
     */
    override fun webSocketConnected(ws: ColibriWebSocket) {
        synchronized(webSocketSyncRoot) {
            // If we already have a web-socket, discard it and use the new one.
            if (ws != webSocket) {
                if (webSocket != null) {
                    logger.info("Replacing an existing websocket.")
                    webSocket?.session?.close(CloseStatus.NORMAL, "replaced")
                }
                webSocketLastActive = true
                webSocket = ws
                sendMessage(ws, createServerHello())
            } else {
                logger.warn("Websocket already connected.")
            }
        }
        try {
            notifyTransportChannelConnected()
        } catch (e: Exception) {
            logger.warn("Caught an exception in notifyTransportConnected", e)
        }
    }

    private fun createServerHello(): ServerHelloMessage {
        return if (VersionConfig.config.announceVersion()) {
            ServerHelloMessage(relay.conference.videobridge.version.toString())
        } else {
            ServerHelloMessage()
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun webSocketClosed(ws: ColibriWebSocket, statusCode: Int, reason: String) {
        synchronized(webSocketSyncRoot) {
            if (ws == webSocket) {
                webSocket = null
                webSocketLastActive = false
                logger.debug { "Web socket closed, statusCode $statusCode ( $reason)." }
                // 1000 is normal, 1001 is e.g. a tab closing. 1005 is "No Status Rcvd" and we see the majority of
                // sockets close this way.
                if (statusCode == 1000 || statusCode == 1001 || statusCode == 1005) {
                    VideobridgeMetrics.colibriWebSocketCloseNormal.inc()
                } else {
                    VideobridgeMetrics.colibriWebSocketCloseAbnormal.inc()
                }
            }
        }

        // This check avoids trying to establish a new WS when the closing of the existing WS races the signaling to
        // expire the relay. 1001 with RELAY_CLOSED means that the remote side willingly closed the socket.
        if (statusCode != 1001 || reason != RELAY_CLOSED) {
            doConnect()
        }
    }

    override fun webSocketError(ws: ColibriWebSocket, cause: Throwable) {
        logger.error("Colibri websocket error: ${cause.message}")
        VideobridgeMetrics.colibriWebSocketErrors.inc()
    }

    /**
     * {@inheritDoc}
     */
    override fun close() {
        synchronized(webSocketSyncRoot) {
            if (webSocket != null) {
                // 410 Gone indicates that the resource requested is no longer
                // available and will not be available again.
                webSocket?.session?.close(CloseStatus.SHUTDOWN, RELAY_CLOSED)
                webSocket = null
                logger.debug { "Relay expired, closed colibri web-socket." }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun webSocketTextReceived(ws: ColibriWebSocket, message: String) {
        if (ws != webSocket) {
            logger.warn("Received text from an unknown web socket.")
            return
        }
        VideobridgeMetrics.colibriWebSocketMessagesReceived.inc()
        webSocketLastActive = true
        onMessage(ws, message)
    }

    /**
     * Sets the data channel for this endpoint.
     * @param dataChannel the [DataChannel] to use for this transport
     */
    fun setDataChannel(dataChannel: DataChannel) {
        val prevDataChannel = this.dataChannel.get()
        if (prevDataChannel == null) {
            this.dataChannel = WeakReference(dataChannel)
            // We install the handler first, otherwise the 'ready' might fire after we check it but before we
            //  install the handler
            dataChannel.onDataChannelEvents { notifyTransportChannelConnected() }
            if (dataChannel.isReady) {
                notifyTransportChannelConnected()
            }
            dataChannel.onDataChannelMessage(this)
        } else if (prevDataChannel === dataChannel) {
            // TODO: i think we should be able to ensure this doesn't happen,
            // so throwing for now.  if there's a good
            // reason for this, we can make this a no-op
            throw Error("Re-setting the same data channel")
        } else {
            throw Error("Overwriting a previous data channel!")
        }
    }

    override val debugState: JSONObject
        get() {
            val debugState = super.debugState
            debugState["numOutgoingMessagesDropped"] = numOutgoingMessagesDropped.get()
            val sentCounts = JSONObject()
            sentCounts.putAll(sentMessagesCounts)
            debugState["sent_counts"] = sentCounts
            return debugState
        }

    /**
     * Handles an opaque message received on the Relay channel. The message originates from an endpoint with an ID of
     * `message.getFrom`, as verified by the remote bridge sending the message.
     *
     * @param message the message that was received from the endpoint.
     */
    override fun endpointMessage(message: EndpointMessage): BridgeChannelMessage? {
        // We trust the "from" field, because it comes from another bridge, not an endpoint.
        val conference = relay.conference
        if (conference.isExpired) {
            logger.warn("Unable to send EndpointMessage, conference is expired")
            return null
        }
        if (message.isBroadcast()) {
            conference.sendMessageFromRelay(message, true, relay.meshId)
        } else {
            // 1:1 message
            val to = message.to
            val targetEndpoint = conference.getLocalEndpoint(to)
            if (targetEndpoint == null) {
                logger.warn("Unable to find endpoint to send EndpointMessage to: $to")
                return null
            }

            conference.sendMessage(
                message,
                listOf(targetEndpoint),
                // sendToRelays
                false
            )
        }
        return null
    }

    /**
     * Handles an endpoint statistics message on the Relay channel that should be forwarded to
     * local endpoints as appropriate.
     *
     * @param message the message that was received from the endpoint.
     */
    override fun endpointStats(message: EndpointStats): BridgeChannelMessage? {
        // We trust the "from" field, because it comes from another bridge, not an endpoint.
        val conference = relay.conference
        if (conference.isExpired) {
            logger.warn("Unable to send EndpointStats, conference is null or expired")
            return null
        }
        if (message.from == null) {
            logger.warn("Unable to send EndpointStats, missing from")
            return null
        }
        val from = conference.getEndpoint(message.from!!)
        if (from == null) {
            logger.warn("Unable to send EndpointStats, unknown endpoint " + message.from)
            return null
        }
        conference.localEndpoints.filter { it.wantsStatsFrom(from) }.forEach { it.sendMessage(message) }
        conference.relays.filter { it.meshId != relay.meshId }.forEach { it.sendMessage(message) }
        return null
    }

    override fun endpointConnectionStatus(message: EndpointConnectionStatusMessage): BridgeChannelMessage? {
        val conference = relay.conference
        if (conference.isExpired) {
            logger.warn("Unable to send EndpointConnectionStatusMessage, conference is expired")
            return null
        }
        conference.sendMessageFromRelay(message, true, relay.meshId)
        return null
    }

    companion object {
        /**
         * The single [WebSocketClient] instance that all [Relay]s use to initiate a web socket connection.
         */
        val webSocketClient = WebSocketClient().apply {
            idleTimeout = WebsocketServiceConfig.config.idleTimeout
            start()
        }

        /**
         * Reason to use when closing a WS due to the relay being expired.
         */
        const val RELAY_CLOSED = "relay_closed"
    }
}
