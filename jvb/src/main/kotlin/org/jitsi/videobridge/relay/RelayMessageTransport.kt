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
import org.jitsi.videobridge.AbstractEndpointMessageTransport
import org.jitsi.videobridge.EndpointMessageTransportConfig
import org.jitsi.videobridge.MultiStreamConfig
import org.jitsi.videobridge.Videobridge
import org.jitsi.videobridge.message.BridgeChannelMessage
import org.jitsi.videobridge.message.ClientHelloMessage
import org.jitsi.videobridge.message.EndpointMessage
import org.jitsi.videobridge.message.EndpointStats
import org.jitsi.videobridge.message.LastNMessage
import org.jitsi.videobridge.message.ReceiverVideoConstraintMessage
import org.jitsi.videobridge.message.ReceiverVideoConstraintsMessage
import org.jitsi.videobridge.message.SelectedEndpointMessage
import org.jitsi.videobridge.message.SelectedEndpointsMessage
import org.jitsi.videobridge.message.ServerHelloMessage
import org.jitsi.videobridge.message.SourceVideoTypeMessage
import org.jitsi.videobridge.message.VideoTypeMessage
import org.jitsi.videobridge.websocket.ColibriWebSocket
import org.json.simple.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

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

        outgoingWebsocket = WebSocketClient().also {
            it.start()
            it.connect(this, URI(url), ClientUpgradeRequest())
        }
    }

    /**
     * {@inheritDoc}
     */
    override fun notifyTransportChannelConnected() {
        /* TODO */
        // relay.endpointMessageTransportConnected();
        // eventHandler.endpointMessageTransportConnected(endpoint);
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

    override fun videoType(videoTypeMessage: VideoTypeMessage): BridgeChannelMessage? {
        val epId = videoTypeMessage.endpointId
        if (epId == null) {
            logger.warn("Received VideoTypeMessage over relay channel with no endpoint ID")
            return null
        }

        val ep = relay.getEndpoint(epId)

        if (ep == null) {
            logger.warn("Received VideoTypeMessage for unknown epId $epId")
            return null
        }

        ep.setVideoType(videoTypeMessage.videoType)

        return null
    }

    override fun sourceVideoType(sourceVideoTypeMessage: SourceVideoTypeMessage): BridgeChannelMessage? {
        if (!MultiStreamConfig.config.isEnabled()) {
            return null
        }

        val epId = sourceVideoTypeMessage.endpointId
        if (epId == null) {
            logger.warn("Received SourceVideoTypeMessage over relay channel with no endpoint ID")
            return null
        }

        val ep = relay.getEndpoint(epId)

        if (ep == null) {
            logger.warn("Received SourceVideoTypeMessage for unknown epId $epId")
            return null
        }

        ep.setVideoType(sourceVideoTypeMessage.sourceName, sourceVideoTypeMessage.videoType)

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
        dst.remote.sendStringByFuture(message.toJson())
        statisticsSupplier.get().totalColibriWebSocketMessagesSent.incrementAndGet()
    }

    /**
     * {@inheritDoc}
     */
    override fun sendMessage(msg: BridgeChannelMessage) {
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
                webSocket!!.session.close(410, "replaced")
                webSocket = null
                logger.debug { "Endpoint expired, closed colibri web-socket." }
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
     * Notifies this `Endpoint` that a [SelectedEndpointsMessage]
     * has been received.
     *
     * @param message the message that was received.
     */
    override fun selectedEndpoints(message: SelectedEndpointsMessage): BridgeChannelMessage? {
        val newSelectedEndpoints: List<String> = ArrayList(message.selectedEndpoints)
        logger.debug { "Selected $newSelectedEndpoints" }
        // TODO
//        endpoint.setSelectedEndpoints(newSelectedEndpoints);
        return null
    }

    override fun receiverVideoConstraints(message: ReceiverVideoConstraintsMessage): BridgeChannelMessage? {
        /* TODO */
//        endpoint.setBandwidthAllocationSettings(message);
        return null
    }

    /**
     * Notifies this `Endpoint` that a
     * [ReceiverVideoConstraintMessage] has been received
     *
     * @param message the message that was received.
     */
    override fun receiverVideoConstraint(message: ReceiverVideoConstraintMessage): BridgeChannelMessage? {
        val maxFrameHeight = message.maxFrameHeight
        logger.debug { "Received a maxFrameHeight video constraint from " + relay.id + ": " + maxFrameHeight }

        /* TODO */
//        endpoint.setMaxFrameHeight(maxFrameHeight);
        return null
    }

    /**
     * Notifies this `Endpoint` that a [LastNMessage] has been
     * received.
     *
     * @param message the message that was received.
     */
    override fun lastN(message: LastNMessage): BridgeChannelMessage? {
        /* TODO */
//        endpoint.setLastN(message.getLastN());
        return null
    }

    /**
     * Handles an opaque message from this `Endpoint` that should be forwarded to either: a) another client in
     * this conference (1:1 message) or b) all other clients in this conference (broadcast message).
     *
     * @param message the message that was received from the endpoint.
     */
    override fun endpointMessage(message: EndpointMessage): BridgeChannelMessage? {
        /* TODO */
//        // First insert/overwrite the "from" to prevent spoofing.
//        String from = endpoint.getId();
//        message.setFrom(from);
//
//        Conference conference = endpoint.getConference();
//
//        if (conference == null || conference.isExpired())
//        {
//            getLogger().warn("Unable to send EndpointMessage, conference is null or expired");
//            return null;
//        }
//
//        boolean sendToOcto;
//
//        List<AbstractEndpoint> targets;
//        if (message.isBroadcast())
//        {
//            // Broadcast message to all local endpoints + octo.
//            targets = new LinkedList<>(conference.getLocalEndpoints());
//            targets.remove(endpoint);
//            sendToOcto = true;
//        }
//        else
//        {
//            // 1:1 message
//            String to = message.getTo();
//
//            AbstractEndpoint targetEndpoint = conference.getEndpoint(to);
//            if (targetEndpoint instanceof OctoEndpoint)
//            {
//                targets = Collections.emptyList();
//                sendToOcto = true;
//            }
//            else if (targetEndpoint != null)
//            {
//                targets = Collections.singletonList(targetEndpoint);
//                sendToOcto = false;
//            }
//            else
//            {
//                getLogger().warn("Unable to find endpoint to send EndpointMessage to: " + to);
//                return null;
//            }
//        }
//
//        conference.sendMessage(message, targets, sendToOcto);
        return null
    }

    /**
     * Handles an endpoint statistics message from this `Endpoint` that should be forwarded to
     * other endpoints as appropriate, and also to Octo.
     *
     * @param message the message that was received from the endpoint.
     */
    override fun endpointStats(message: EndpointStats): BridgeChannelMessage? {
        /* TODO */
//        // First insert/overwrite the "from" to prevent spoofing.
//        String from = endpoint.getId();
//        message.setFrom(from);
//
//        Conference conference = endpoint.getConference();
//
//        if (conference == null || conference.isExpired())
//        {
//            getLogger().warn("Unable to send EndpointStats, conference is null or expired");
//            return null;
//        }
//
//        List<AbstractEndpoint> targets = conference.getLocalEndpoints().stream()
//            .filter((ep) -> ep != endpoint && ep.wantsStatsFrom(endpoint))
//            .collect(Collectors.toList());
//
//        conference.sendMessage(message, targets, true);
        return null
    }
}
