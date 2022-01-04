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

import org.apache.commons.lang3.StringUtils
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest
import org.eclipse.jetty.websocket.client.WebSocketClient
import org.jitsi.utils.logging2.Logger
import org.jitsi.videobridge.AbstractEndpoint
import org.jitsi.videobridge.AbstractEndpointMessageTransport
import org.jitsi.videobridge.Conference
import org.jitsi.videobridge.Endpoint
import org.jitsi.videobridge.EndpointMessageTransportConfig
import org.jitsi.videobridge.MultiStreamConfig
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.message.AddReceiverMessage
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.ClientHelloMessage
import org.jitsi.videobridge.message.EndpointConnectionStatusMessage
import org.jitsi.videobridge.message.EndpointMessage
import org.jitsi.videobridge.message.EndpointStats
import org.jitsi.videobridge.message.RemoveReceiverMessage
import org.jitsi.videobridge.message.SelectedEndpointMessage
import org.jitsi.videobridge.message.SelectedEndpointsMessage
import org.jitsi.videobridge.message.ServerHelloMessage
import org.jitsi.videobridge.message.SourceVideoTypeMessage
import org.jitsi.videobridge.message.VideoTypeMessage
import org.jitsi.videobridge.websocket.ColibriWebSocket
import org.json.simple.JSONObject
import java.net.URI
import java.util.LinkedList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier
import java.util.stream.Collectors

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for a [Relay].
 */
class RelayMessageTransport(
    private val relay: Relay,
    private val statisticsSupplier: Supplier<Videobridge.Statistics>,
    private val eventHandler: EndpointMessageTransportEventHandler,
    parentLogger: Logger
) : AbstractEndpointMessageTransport(parentLogger), ColibriWebSocket.EventHandler {
    /**
     * The last connected/accepted web-socket by this instance, if any.
     */
    private var webSocket: ColibriWebSocket? = null

    /**
     * For active websockets, the URL that was connected to.
     */
    private var url: String? = null

    /**
     * An active websocket client.
     */
    private var outgoingWebsocket: WebSocketClient? = null

    /**
     * Use to synchronize access to [.webSocket]
     */
    private val webSocketSyncRoot = Any()
    private val numOutgoingMessagesDropped = AtomicInteger(0)

    /**
     * The number of sent message by type.
     */
    private val sentMessagesCounts: MutableMap<String, AtomicLong> = ConcurrentHashMap()

    init { logger.addContext("relay-id", relay.id) }

    /**
     * Connect the bridge channel message to the websocket URL specified
     */
    fun connectTo(url: String) {
        if (this.url != null && this.url == url) {
            return
        }
        this.url = url

        doConnect()
    }

    private fun doConnect() {
        val url = this.url ?: throw IllegalStateException("Cannot connect Relay transport when no URL set")

        webSocket = ColibriWebSocket(relay.id, this)
        outgoingWebsocket = WebSocketClient().also {
            it.start()
            it.connect(webSocket, URI(url), ClientUpgradeRequest())
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
        logger.info { "Received ServerHelloMessage, version ${message.version}" }
        return null
    }

    /**
     * This message indicates that a remote bridge wishes to receive video
     * with certain constraints for a specific endpoint.
     * @param message
     * @return
     */
    override fun addReceiver(message: AddReceiverMessage): BridgeChannelMessage? {
        val epId = message.endpointId
        val ep = relay.conference.getEndpoint(epId) ?: run {
            logger.warn("Received AddReceiverMessage for unknown epId $epId")
            return null
        }

        ep.addReceiver(message.bridgeId, message.videoConstraints)
        return null
    }

    override fun removeReceiver(message: RemoveReceiverMessage): BridgeChannelMessage? {
        val epId = message.endpointId
        val ep = relay.conference.getEndpoint(epId) ?: run {
            logger.warn("Received RemoveReceiverMessage for unknown epId $epId")
            return null
        }
        ep.removeReceiver(message.bridgeId)
        return null
    }

    override fun videoType(message: VideoTypeMessage): BridgeChannelMessage? {
        val epId = message.endpointId
        if (epId == null) {
            logger.warn("Received VideoTypeMessage over relay channel with no endpoint ID")
            return null
        }

        val ep = relay.getEndpoint(epId)

        if (ep == null) {
            logger.warn("Received VideoTypeMessage for unknown epId $epId")
            return null
        }

        ep.setVideoType(message.videoType)

        return null
    }

    override fun sourceVideoType(message: SourceVideoTypeMessage): BridgeChannelMessage? {
        if (!MultiStreamConfig.config.isEnabled()) {
            return null
        }

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

        return null
    }

    override fun unhandledMessage(message: BridgeChannelMessage) {
        logger.warn("Received a message with an unexpected type: " + message.type)
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
        } else {
            throw IllegalArgumentException("unknown transport:$dst")
        }
    }

    /**
     * Sends a string via a particular [ColibriWebSocket] instance.
     * @param dst the [ColibriWebSocket] through which to send the message.
     * @param message the message to send.
     */
    private fun sendMessage(dst: ColibriWebSocket, message: BridgeChannelMessage) {
        // We'll use the async version of sendString since this may be called
        // from multiple threads.  It's just fire-and-forget though, so we
        // don't wait on the result
        dst.remote?.sendStringByFuture(message.toJson())
        statisticsSupplier.get().totalColibriWebSocketMessagesSent.incrementAndGet()
    }

    /**
     * {@inheritDoc}
     */
    public override fun sendMessage(msg: BridgeChannelMessage) {
        if (webSocket == null) {
            logger.debug("No available transport channel, can't send a message")
            numOutgoingMessagesDropped.incrementAndGet()
        } else {
            sentMessagesCounts.computeIfAbsent(
                msg.javaClass.simpleName
            ) { k: String? -> AtomicLong() }.incrementAndGet()
            sendMessage(webSocket, msg)
        }
    }

    override val isConnected: Boolean
        get() = webSocket != null

    val isActive: Boolean
        get() = outgoingWebsocket != null

    /**
     * {@inheritDoc}
     */
    override fun webSocketConnected(ws: ColibriWebSocket) {
        synchronized(webSocketSyncRoot) {

            // If we already have a web-socket, discard it and use the new one.
            if (webSocket != null) {
                webSocket!!.session.close(200, "replaced")
            }
            webSocket = ws
            sendMessage(ws, createServerHello())
        }
        try {
            notifyTransportChannelConnected()
        } catch (e: Exception) {
            logger.warn("Caught an exception in notifyTransportConnected", e)
        }
    }

    private fun createServerHello(): ServerHelloMessage {
        return if (EndpointMessageTransportConfig.config.announceVersion()) {
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
                logger.debug { "Web socket closed, statusCode $statusCode ( $reason)." }
            }
        }
        if (outgoingWebsocket != null) {
            // Try to reconnect.  TODO: how to handle failures?
            doConnect()
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun close() {
        synchronized(webSocketSyncRoot) {
            if (webSocket != null) {
                // 410 Gone indicates that the resource requested is no longer
                // available and will not be available again.
                webSocket?.session?.close(410, "replaced")
                webSocket = null
                logger.debug { "Relay expired, closed colibri web-socket." }
            }
        }
        outgoingWebsocket?.stop()
        outgoingWebsocket = null
    }

    /**
     * {@inheritDoc}
     */
    override fun webSocketTextReceived(ws: ColibriWebSocket, message: String) {
        if (ws != webSocket) {
            logger.warn("Received text from an unknown web socket.")
            return
        }
        statisticsSupplier.get().totalColibriWebSocketMessagesReceived.incrementAndGet()
        onMessage(ws, message)
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
     * Notifies this `Endpoint` that a [SelectedEndpointsMessage]
     * has been received.
     *
     * @param message the message that was received.
     */
    override fun selectedEndpoint(message: SelectedEndpointMessage): BridgeChannelMessage? {
        val newSelectedEndpointID = message.selectedEndpoint
        val newSelectedIDs: List<String> =
            if (newSelectedEndpointID == null || StringUtils.isBlank(newSelectedEndpointID)) emptyList()
            else listOf(newSelectedEndpointID)
        selectedEndpoints(SelectedEndpointsMessage(newSelectedIDs))
        return null
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
        val targets: List<AbstractEndpoint> = if (message.isBroadcast()) {
            // Broadcast message
            LinkedList<AbstractEndpoint>(conference.localEndpoints)
        } else {
            // 1:1 message
            val to = message.to
            val targetEndpoint: AbstractEndpoint? = conference.getLocalEndpoint(to)
            if (targetEndpoint != null) {
                listOf(targetEndpoint)
            } else {
                logger.warn("Unable to find endpoint to send EndpointMessage to: $to")
                return null
            }
        }
        conference.sendMessage(message, targets, false /* sendToOcto */)
        return null
    }

    /**
     * Handles an endpoint statistics message on the Octo channel that should be forwarded to
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
        val targets = conference.localEndpoints.stream()
            .filter { ep: Endpoint ->
                ep.wantsStatsFrom(
                    from
                )
            }
            .collect(Collectors.toList())
        conference.sendMessage(message, targets as List<AbstractEndpoint>, false)
        return null
    }

    override fun endpointConnectionStatus(message: EndpointConnectionStatusMessage): BridgeChannelMessage? {
        val conference: Conference = relay.conference
        if (conference.isExpired) {
            logger.warn("Unable to send EndpointConnectionStatusMessage, conference is null or expired")
            return null
        }
        conference.broadcastMessage(message, false /* sendToOcto */)
        return null
    }
}
