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
package org.jitsi.videobridge;

import org.jitsi.eventadmin.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.rest.*;
import org.json.simple.*;

import java.io.*;
import java.lang.ref.*;
import java.util.*;

import static org.jitsi.videobridge.EndpointMessageBuilder.*;

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. Supports two underlying transport mechanisms --
 * WebRTC data channels and {@code WebSocket}s.
 *
 * @author Boris Grozev
 */
class EndpointMessageTransport
    extends AbstractEndpointMessageTransport
    implements WebRtcDataStream.DataCallback
{

    /**
     * The {@link Logger} used by the {@link Endpoint} class to print debug
     * information.
     */
    private static final Logger classLogger
        = Logger.getLogger(EndpointMessageTransport.class);

    /**
     * The {@link Endpoint} associated with this
     * {@link EndpointMessageTransport}.
     *
     */
    private final Endpoint endpoint;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * The last accepted web-socket by this instance, if any.
     */
    private ColibriWebSocket webSocket;

    /**
     * User to synchronize access to {@link #webSocket}
     */
    private final Object webSocketSyncRoot = new Object();

    /**
     * Whether the last active transport channel (i.e. the last to receive a
     * message from the remote endpoint) was the web socket (if {@code true}),
     * or the WebRTC data channel (if {@code false}).
     */
    private boolean webSocketLastActive = false;

    /**
     * SCTP connection bound to this endpoint.
     */
    private WeakReference<SctpConnection> sctpConnection
        = new WeakReference<>(null);

    /**
     * The {@link WebRtcDataStream} currently used by this
     * {@link EndpointMessageTransport} instance for writing.
     */
    private WebRtcDataStream writableWebRtcDataStream;

    /**
     * The listener which will be added to {@link SctpConnection}s associated
     * with this endpoint, which will be notified when {@link SctpConnection}s
     * become ready.
     *
     * Note that we just want to make sure that the initial messages (e.g.
     * a dominant speaker notification) are sent. Because of this we only add
     * listeners to {@link SctpConnection}s which are not yet ready, and we
     * remove the listener after an {@link SctpConnection} becomes ready.
     */
    private final WebRtcDataStreamListener webRtcDataStreamListener
        = new WebRtcDataStreamListener()
    {
        @Override
        public void onChannelOpened(
                SctpConnection source, WebRtcDataStream channel)
        {
            SctpConnection currentConnection = getSctpConnection();

            if (source.equals(currentConnection))
            {
                hookUpDefaultWebRtcDataChannel(currentConnection);
            }
        }
    };

    /**
     * Initializes a new {@link EndpointMessageTransport} instance.
     * @param endpoint the associated {@link Endpoint}.
     */
    EndpointMessageTransport(Endpoint endpoint)
    {
        super(endpoint);
        this.endpoint = endpoint;
        this.logger
            = Logger.getLogger(
                    classLogger, endpoint.getConference().getLogger());
    }

    /**
     * At the time when new data chanel is opened or {@link SctpConnection}
     * instance is replaced, this method will reelect the default WebRTC data
     * channel (the one that's used for writing).
     *
     * @param connection - The currently used {@link SctpConnection} instance
     * from which the default data channel will be obtained.
     */
    private void hookUpDefaultWebRtcDataChannel(SctpConnection connection)
    {
        // FIXME make "data stream" vs "data channel" naming consistent in both
        // code and comments
        WebRtcDataStream _defaultStream
            = connection != null ? connection.getDefaultDataStream() : null;

        if (_defaultStream != null)
        {
            WebRtcDataStream oldDataStream = this.writableWebRtcDataStream;

            this.writableWebRtcDataStream = _defaultStream;

            _defaultStream.setDataCallback(EndpointMessageTransport.this);

            if (oldDataStream == null)
            {
                logger.info(
                    String.format(
                        "WebRTC data channel established for %s",
                        connection.getLoggingId()));

                notifyTransportChannelConnected();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void notifyTransportChannelConnected()
    {
        EventAdmin eventAdmin = endpoint.getConference().getEventAdmin();

        if (eventAdmin != null)
        {
            eventAdmin.postEvent(
                EventFactory.endpointMessageTransportReady(endpoint));
        }

        endpoint.getConference().endpointMessageTransportConnected(endpoint);

        for (RtpChannel channel : endpoint.getChannels())
        {
            channel.endpointMessageTransportConnected();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onClientHello(Object src, JSONObject jsonObject)
    {
        // ClientHello was introduced for functional testing purposes. It
        // triggers a ServerHello response from Videobridge. The exchange
        // reveals (to the client) that the transport channel between the
        // remote endpoint and the Videobridge is operational.
        // We take care to send the reply using the same transport channel on
        // which we received the request..
        sendMessage(src, createServerHelloEvent(), "response to ClientHello");
    }

    /**
     * Sends a string via a particular transport channel.
     * @param dst the transport channel.
     * @param message the message to send.
     */
    private void sendMessage(Object dst, String message)
    {
        sendMessage(dst, message, "");
    }

    /**
     * Sends a string via a particular transport channel.
     * @param dst the transport channel.
     * @param message the message to send.
     * @param errorMessage an error message to be logged in case of failure.
     */
    private void sendMessage(Object dst, String message, String errorMessage)
    {
        if (dst instanceof WebRtcDataStream)
        {
            sendMessage((WebRtcDataStream) dst, message, errorMessage);
        }
        else if (dst instanceof ColibriWebSocket)
        {
            sendMessage((ColibriWebSocket) dst, message, errorMessage);
        }
        else
        {
            throw new IllegalArgumentException("unknown transport:" + dst);
        }
    }

    /**
     * Sends a string via a particular {@link WebRtcDataStream} instance.
     * @param dst the {@link WebRtcDataStream} through which to send the message.
     * @param message the message to send.
     * @param errorMessage an error message to be logged in case of failure.
     */
    private void sendMessage(
            WebRtcDataStream dst, String message, String errorMessage)
    {
        try
        {
            dst.sendString(message);
            endpoint.getConference().getVideobridge().getStatistics()
                .totalDataChannelMessagesSent.incrementAndGet();
        }
        catch (IOException ioe)
        {
            logger.error(
                "Failed to send a message over a WebRTC data channel"
                    +   " (endpoint=" + endpoint.getID() + "): " + errorMessage,
                ioe);
        }
    }

    /**
     * Sends a string via a particular {@link ColibriWebSocket} instance.
     * @param dst the {@link ColibriWebSocket} through which to send the message.
     * @param message the message to send.
     * @param errorMessage an error message to be logged in case of failure.
     */
    private void sendMessage(
        ColibriWebSocket dst, String message, String errorMessage)
    {
        // We'll use the async version of sendString since this may be called
        // from multiple threads.  It's just fire-and-forget though, so we
        // don't wait on the result
        dst.getRemote().sendStringByFuture(message);
        endpoint.getConference().getVideobridge().getStatistics()
            .totalColibriWebSocketMessagesSent.incrementAndGet();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onPinnedEndpointChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        String newPinnedEndpointID = (String) jsonObject.get("pinnedEndpoint");

        Set<String> newPinnedIDs = Collections.EMPTY_SET;
        if (newPinnedEndpointID != null && !"".equals(newPinnedEndpointID))
        {
            newPinnedIDs = Collections.singleton(newPinnedEndpointID);
        }

        endpoint.pinnedEndpointsChanged(newPinnedIDs);
    }

    /**
     * {@inheritDoc}
     */
    protected void onPinnedEndpointsChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        Object o = jsonObject.get("pinnedEndpoints");
        if (!(o instanceof JSONArray))
        {
            logger.warn("Received invalid or unexpected JSON ("
                            + endpoint.getLoggingId() + "):" + jsonObject);
            return;
        }

        JSONArray jsonArray = (JSONArray) o;
        Set<String> newPinnedEndpoints = new HashSet<>();
        for (Object endpointId : jsonArray)
        {
            if (endpointId != null && endpointId instanceof String)
            {
                newPinnedEndpoints.add((String)endpointId);
            }
        }

        if (logger.isDebugEnabled())
        {
            logger.debug(Logger.Category.STATISTICS,
                         "pinned," + endpoint.getLoggingId()
                             + " pinned=" + newPinnedEndpoints);
        }
        endpoint.pinnedEndpointsChanged(newPinnedEndpoints);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSelectedEndpointChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        String newSelectedEndpointID = (String) jsonObject.get("selectedEndpoint");

        Set<String> newSelectedIDs = Collections.EMPTY_SET;
        if (newSelectedEndpointID != null && !"".equals(newSelectedEndpointID))
        {
            newSelectedIDs = Collections.singleton(newSelectedEndpointID);
        }

        endpoint.selectedEndpointsChanged(newSelectedIDs);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSelectedEndpointsChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        // Find the new pinned endpoint.
        Object o = jsonObject.get("selectedEndpoints");
        if (!(o instanceof JSONArray))
        {
            logger.warn("Received invalid or unexpected JSON: " + jsonObject);
            return;
        }

        JSONArray jsonArray = (JSONArray) o;
        Set<String> newSelectedEndpoints = new HashSet<>();
        for (Object endpointId : jsonArray)
        {
            if (endpointId != null && endpointId instanceof String)
            {
                newSelectedEndpoints.add((String)endpointId);
            }
        }

        endpoint.selectedEndpointsChanged(newSelectedEndpoints);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStringData(WebRtcDataStream src, String msg)
    {
        webSocketLastActive = false;
        endpoint.getConference().getVideobridge().getStatistics().
            totalDataChannelMessagesReceived.incrementAndGet();

        onMessage(src, msg);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void sendMessage(String msg)
        throws IOException
    {
        Object dst = getActiveTransportChannel();
        if (dst == null)
        {
            logger.warn("No available transport channel, can't send a message");
        }
        else
        {
            sendMessage(dst, msg);
        }
    }

    /**
     * @return the active transport channel for this
     * {@link EndpointMessageTransport} (either the {@link #webSocket}, or
     * the WebRTC data channel represented by a {@link WebRtcDataStream}).
     * </p>
     * The "active" channel is determined based on what channels are available,
     * and which one was the last to receive data. That is, if only one channel
     * is available, it will be returned. If two channels are available, the
     * last one to have received data will be returned. Otherwise, {@code null}
     * will be returned.
     */
    private Object getActiveTransportChannel()
    {
        SctpConnection sctpConnection = getSctpConnection();
        ColibriWebSocket webSocket = this.webSocket;
        String endpointId = endpoint.getID();

        Object dst = null;
        if (webSocketLastActive)
        {
            dst = webSocket;
        }

        // Either the socket was not the last active channel,
        // or it has been closed.
        if (dst == null)
        {
            if (sctpConnection != null && sctpConnection.isReady())
            {
                dst = writableWebRtcDataStream;

                if (dst == null)
                {
                    logger.warn(
                        "SCTP ready, but WebRtc data channel with " + endpointId
                            + " not opened yet.");
                }
            }
            else
            {
                logger.warn(
                    "SCTP connection with " + endpointId + " not ready yet.");
            }
        }

        // Maybe the WebRTC data channel is the last active, but it is not
        // currently available. If so, and a web-socket is available -- use it.
        if (dst == null && webSocket != null)
        {
            dst = webSocket;
        }

        return dst;
    }

    /**
     * Notifies this {@link EndpointMessageTransport} that a specific
     * {@link ColibriWebSocket} instance associated with its {@link Endpoint}
     * has connected.
     * @param ws the {@link ColibriWebSocket} which has connected.
     */
    void onWebSocketConnect(ColibriWebSocket ws)
    {
        synchronized (webSocketSyncRoot)
        {
            // If we already have a web-socket, discard it and use the new one.
            if (webSocket != null)
            {
                webSocket.getSession().close(200, "replaced");
            }

            webSocket = ws;
            webSocketLastActive = true;
            sendMessage(ws, createServerHelloEvent(), "initial ServerHello");
        }

        notifyTransportChannelConnected();
    }

    /**
     * Notifies this {@link EndpointMessageTransport} that a specific
     * {@link ColibriWebSocket} instance associated with its {@link Endpoint}
     * has been closed.
     * @param ws the {@link ColibriWebSocket} which has been closed.
     */
    public void onWebSocketClose(
            ColibriWebSocket ws, int statusCode, String reason)
    {
        synchronized (webSocketSyncRoot)
        {
            if (ws != null && ws.equals(webSocket))
            {
                webSocket = null;
                webSocketLastActive = false;
                if (logger.isDebugEnabled())
                {
                    logger.debug("Web socket closed for endpoint "
                        + endpoint.getID() + ": " + statusCode + " " + reason);
                }
            }
        }

    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void close()
    {
        synchronized (webSocketSyncRoot)
        {
            if (webSocket != null)
            {
                // 410 Gone indicates that the resource requested is no longer
                // available and will not be available again.
                webSocket.getSession().close(410, "replaced");
                webSocket = null;
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                        "Endpoint expired, closed colibri web-socket.");
                }
            }
        }
    }

    /**
     * Notifies this {@link EndpointMessageTransport} that a message has been
     * received from a specific {@link ColibriWebSocket} instance associated
     * with its {@link Endpoint}.
     * @param ws the {@link ColibriWebSocket} from which a message was received.
     */
    public void onWebSocketText(ColibriWebSocket ws, String message)
    {
        if (ws == null || !ws.equals(webSocket))
        {
            logger.warn("Received text from an unknown web socket "
                            + "(endpoint=" + endpoint.getID() + ").");
            return;
        }

        endpoint.getConference().getVideobridge().getStatistics().
            totalColibriWebSocketMessagesReceived.incrementAndGet();

        webSocketLastActive = true;
        onMessage(ws, message);
    }

    /**
     * @return the {@link SctpConnection} associated with this endpoint.
     */
    SctpConnection getSctpConnection()
    {
        return sctpConnection.get();
    }

    /**
     * Sets the {@link SctpConnection} associated with this endpoint.
     * @param sctpConnection the new {@link SctpConnection}.
     */
    void setSctpConnection(SctpConnection sctpConnection)
    {
        SctpConnection oldValue = getSctpConnection();

        if (!Objects.equals(oldValue, sctpConnection))
        {
            if (oldValue != null && sctpConnection != null)
            {
                // This is not necessarily invalid, but with the current
                // codebase it likely indicates a problem. If we start to
                // actually use it, this warning should be removed.
                logger.warn("Replacing an Endpoint's SctpConnection.");
            }

            this.sctpConnection = new WeakReference<>(sctpConnection);

            if (sctpConnection != null)
            {
                hookUpDefaultWebRtcDataChannel(sctpConnection);

                sctpConnection.addChannelListener(webRtcDataStreamListener);
            }

            if (oldValue != null)
            {
                // Unregister data callback from all data channels associated
                // with the old connection
                oldValue.forEachDataStream((stream -> {
                    if (stream.getDataCallback() == this)
                    {
                        stream.setDataCallback(null);
                    }
                }));

                // It's the case when there's no default data channel available
                // in the new SCTP connection (because the call to
                // hookUpDefaultWebRtcDataChannel didn't select any) and where
                // the current one belongs to the old SCTP connection.
                if (writableWebRtcDataStream != null
                    && writableWebRtcDataStream.getSctpConnection() == oldValue)
                {
                    writableWebRtcDataStream = null;
                }

                oldValue.removeChannelListener(webRtcDataStreamListener);
            }
        }
    }
}
