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

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.rest.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import java.io.*;
import java.util.*;

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. Supports two underlying transport mechanisms --
 * WebRTC data channels and {@code WebSocket}s.
 *
 * @author Boris Grozev
 */
class EndpointMessageTransport
    implements WebRtcDataStream.DataCallback
{
    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code SelectedEndpointChangedEvent}.
     */
    public static final String COLIBRI_CLASS_SELECTED_ENDPOINT_CHANGED
        = "SelectedEndpointChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code SelectedEndpointChangedEvent}.
     */
    public static final String COLIBRI_CLASS_SELECTED_ENDPOINTS_CHANGED
        = "SelectedEndpointsChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code PinnedEndpointChangedEvent}.
     */
    public static final String COLIBRI_CLASS_PINNED_ENDPOINT_CHANGED
        = "PinnedEndpointChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code PinnedEndpointsChangedEvent}.
     */
    public static final String COLIBRI_CLASS_PINNED_ENDPOINTS_CHANGED
        = "PinnedEndpointsChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code ClientHello} message.
     */
    public static final String COLIBRI_CLASS_CLIENT_HELLO
        = "ClientHello";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code LastNChangedEvent}.
     */
    public static final String COLIBRI_CLASS_LASTN_CHANGED
        = "LastNChangedEvent";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code EndpointMessage}.
     */
    public static final String COLIBRI_CLASS_ENDPOINT_MESSAGE
        = "EndpointMessage";

    /**
     * The {@link Videobridge#COLIBRI_CLASS} value indicating a
     * {@code ReceiverVideoConstraint} message.
     */
    public static final String COLIBRI_CLASS_RECEIVER_VIDEO_CONSTRAINT
        = "ReceiverVideoConstraint";

    /**
     * The string which encodes a COLIBRI {@code ServerHello} message.
     */
    private static final String SERVER_HELLO_STR
        = "{\"colibriClass\":\"ServerHello\"}";

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
     * Initializes a new {@link EndpointMessageTransport} instance.
     * @param endpoint the associated {@link Endpoint}.
     */
    EndpointMessageTransport(Endpoint endpoint)
    {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.logger
            = Logger.getLogger(
                    classLogger, endpoint.getConference().getLogger());
    }

    /**
     * Notifies this {@code Endpoint} that a {@code ClientHello} has been
     * received.
     *
     * @param src the transport channel on which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code ClientHello} which has been received by the associated
     * {@code SctpConnection}
     */
    private void onClientHello(Object src, JSONObject jsonObject)
    {
        // ClientHello was introduced for functional testing purposes. It
        // triggers a ServerHello response from Videobridge. The exchange
        // reveals (to the client) that the transport channel between the
        // remote endpoint and the Videobridge is operational.
        sendMessage(src, SERVER_HELLO_STR, "response to ClientHello");
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
        try
        {
            dst.getRemote().sendString(message);
            endpoint.getConference().getVideobridge().getStatistics()
                .totalColibriWebSocketMessagesSent.incrementAndGet();
        }
        catch (Exception e)
        {
            logger.error(
                "Failed to send a message over a WebSocket (endpoint="
                    + endpoint.getID() + "): " + errorMessage,
                e);
        }
    }

    /**
     * Notifies this {@code Endpoint} that a specific JSON object has been
     * received.
     *
     * @param src the transport channel by which the specified
     * {@code jsonObject} has been received.
     * @param jsonObject the JSON data received by {@code src}.
     * @param colibriClass the non-{@code null} value of the mandatory JSON
     * property {@link Videobridge#COLIBRI_CLASS} required of all JSON objects
     * received.
     */
    private void onJSONData(
        Object src,
        JSONObject jsonObject,
        String colibriClass)
    {
        switch (colibriClass)
        {
        case COLIBRI_CLASS_SELECTED_ENDPOINT_CHANGED:
            onSelectedEndpointChangedEvent(src, jsonObject);
            break;
        case COLIBRI_CLASS_SELECTED_ENDPOINTS_CHANGED:
            onSelectedEndpointsChangedEvent(src, jsonObject);
            break;
        case COLIBRI_CLASS_PINNED_ENDPOINT_CHANGED:
            onPinnedEndpointChangedEvent(src, jsonObject);
            break;
        case COLIBRI_CLASS_PINNED_ENDPOINTS_CHANGED:
            onPinnedEndpointsChangedEvent(src, jsonObject);
            break;
        case COLIBRI_CLASS_CLIENT_HELLO:
            onClientHello(src, jsonObject);
            break;
        case COLIBRI_CLASS_ENDPOINT_MESSAGE:
            onClientEndpointMessage(src, jsonObject);
            break;
        case COLIBRI_CLASS_LASTN_CHANGED:
            onLastNChangedEvent(src, jsonObject);
            break;
        case COLIBRI_CLASS_RECEIVER_VIDEO_CONSTRAINT:
            onReceiverVideoConstraintEvent(src, jsonObject);
            break;
        default:
            break;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onBinaryData(WebRtcDataStream src, byte[] data)
    {
    }

    /**
     * Handles an opaque message from this {@code Endpoint} that should be
     * forwarded to either: a) another client in this conference (1:1
     * message) or b) all other clients in this conference (broadcast message)
     *
     * @param src the transport channel on which {@code jsonObject} has
     * been received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code EndpointMessage} which has been received.
     *
     * EndpointMessage definition:
     * 'to': If the 'to' field contains the endpoint id of another endpoint
     * in this conference, the message will be treated as a 1:1 message and
     * forwarded just to that endpoint. If the 'to' field is an empty
     * string, the message will be treated as a broadcast and sent to all other
     * endpoints in this conference.
     * 'msgPayload': An opaque payload. The bridge does not need to know or
     * care what is contained in the 'msgPayload' field, it will just forward
     * it blindly.
     *
     * NOTE: This message is designed to allow endpoints to pass their own
     * application-specific messaging to one another without requiring the
     * bridge to know of or understand every message type. These messages
     * will be forwarded by the bridge using the same transport channel as other
     * jitsi messages (e.g. active speaker and last-n notifications).
     * It is not recommended to send high-volume message traffic on this
     * channel (e.g. file transfer), such that it may interfere with other
     * jitsi messages.
     */
    @SuppressWarnings("unchecked")
    private void onClientEndpointMessage(
        Object src,
        JSONObject jsonObject)
    {
        String to = (String)jsonObject.get("to");
        jsonObject.put("from", endpoint.getID());
        Conference conference = endpoint.getConference();
        if (conference == null || conference.isExpired())
        {
            logger.warn(
                "Unable to send EndpointMessage, conference is null or expired");
            return;
        }

        if ("".equals(to))
        {
            // Broadcast message
            List<Endpoint> endpointSubset = new ArrayList<>();
            for (Endpoint e : conference.getEndpoints())
            {
                if (!endpoint.getID().equalsIgnoreCase(e.getID()))
                {
                    endpointSubset.add(e);
                }
            }
            conference.sendMessage(
                jsonObject.toString(), endpointSubset);
        }
        else
        {
            // 1:1 message
            Endpoint targetEndpoint = conference.getEndpoint(to);
            if (targetEndpoint != null)
            {
                List<Endpoint> endpointSubset = new ArrayList<>();
                endpointSubset.add(targetEndpoint);
                conference.sendMessage(
                    jsonObject.toString(), endpointSubset);
            }
            else
            {
                logger.warn(
                    "Unable to find endpoint " + to
                        + " to send EndpointMessage");
            }
        }
    }

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received.
     */
    private void onPinnedEndpointChangedEvent(
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
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointsChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received.
     */
    private void onPinnedEndpointsChangedEvent(
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
     * Notifies this {@code Endpoint} that a {@code SelectedEndpointChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received.
     */
    private void onSelectedEndpointChangedEvent(
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
     * Notifies this {@code Endpoint} that a
     * {@code SelectedEndpointsChangedEvent} has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received.
     */
    private void onSelectedEndpointsChangedEvent(
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
     * Notifies this {@code Endpoint} that a {@code LastNChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has been
     * received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code LastNChangedEvent} which has been received.
     */
    private void onLastNChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        // Find the new value for LastN.
        Object o = jsonObject.get("lastN");
        if (!(o instanceof Number))
        {
            return;
        }
        int lastN = ((Number) o).intValue();

        for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO)) {
            channel.setLastN(lastN);
        }
    }

    /**
     * Notifies this {@code Endpoint} that a {@code ReceiverVideoConstraint}
     * event has been received
     *
     * @param src the transport channel by which {@code jsonObject} has been
     * received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code LastNChangedEvent} which has been received.
     */
    private void onReceiverVideoConstraintEvent(
        Object src,
        JSONObject jsonObject)
    {
        Object o = jsonObject.get("maxFrameHeight");
        if (!(o instanceof Number))
        {
            logger.warn("Received a non-number maxFrameHeight video constraint from " + endpoint.getID() +
                ": " + o.toString());
            return;
        }
        int maxFrameHeight = ((Number) o).intValue();
        logger.debug("Received a maxFrameHeight video constraint from " + endpoint.getID() + ": " + maxFrameHeight);

        for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO))
        {
            VideoChannel videoChannel = (VideoChannel)channel;
            videoChannel.setMaxFrameHeight(maxFrameHeight);
        }
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
     * Notifies this {@link EndpointMessageTransport} that a specific message
     * has been received on a specific transport channel.
     * @param src the transport channel on which the message has been received.
     * @param msg the message which has been received.
     */
    private void onMessage(Object src, String msg)
    {
        Object obj;
        JSONParser parser = new JSONParser(); // JSONParser is NOT thread-safe.

        try
        {
            obj = parser.parse(msg);
        }
        catch (ParseException ex)
        {
            logger.warn(
                    "Malformed JSON received from endpoint " + endpoint.getID(),
                    ex);
            obj = null;
        }

        // We utilize JSONObjects only.
        if (obj instanceof JSONObject)
        {
            JSONObject jsonObject = (JSONObject) obj;
            // We utilize JSONObjects with colibriClass only.
            String colibriClass = (String)jsonObject.get(Videobridge.COLIBRI_CLASS);

            if (colibriClass != null)
            {
                onJSONData(src, jsonObject, colibriClass);
            }
            else
            {
                logger.warn(
                    "Malformed JSON received from endpoint " + endpoint.getID()
                        + ". JSON object does not contain the colibriClass"
                        + " field.");
            }
        }
    }

    /**
     * Sends a specific message over the active transport channels of this
     * {@link EndpointMessageTransport}.
     *
     * @param msg message text to send.
     * @throws IOException
     */
    void sendMessage(String msg)
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
        throws IOException
    {
        SctpConnection sctpConnection = endpoint.getSctpConnection();
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
                dst = sctpConnection.getDefaultDataStream();

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
            sendMessage(ws, SERVER_HELLO_STR, "initial ServerHello");
        }
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
}
