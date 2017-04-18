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
import org.json.simple.*;
import org.json.simple.parser.*;

import java.io.*;
import java.util.*;

/**
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
     * The {@link Logger} used by the {@link Endpoint} class to print debug
     * information.
     */
    private static final Logger classLogger
        = Logger.getLogger(EndpointMessageTransport.class);

    private final Endpoint endpoint;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    EndpointMessageTransport(Endpoint endpoint)
    {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.logger
            = Logger.getLogger(
                    classLogger, endpoint.getConference().getLogger());
    }

    /**
     * Notifies this {@code Endpoint} that a {@code ClientHello} has been
     * received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code ClientHello} which has been received by the associated
     * {@code SctpConnection}
     */
    private void onClientHello(WebRtcDataStream src, JSONObject jsonObject)
    {
        // ClientHello was introduced for (functional) testing purposes. It
        // triggers a ServerHello (response) from Videobridge. The exchange
        // reveals (to the client) that the WebRTC data channel between the
        // (remote) endpoint and the Videobridge is operational.
        try
        {
            src.sendString("{\"colibriClass\":\"ServerHello\"}");
        }
        catch (IOException ioex)
        {
            logger.error(
                "Failed to respond to a ClientHello over the WebRTC data"
                    + " channel of endpoint " + endpoint.getID() + "!",
                ioex);
        }
    }

    /**
     * Notifies this {@code Endpoint} that a specific JSON object has been
     * received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which the specified
     * {@code jsonObject} has been received
     * @param jsonObject the JSON data received by {@code src}
     * @param colibriClass the non-{@code null} value of the mandatory JSON
     * property {@link Videobridge#COLIBRI_CLASS} required of all JSON objects
     * received by the associated {@code SctpConnection}
     */
    private void onJSONData(
        WebRtcDataStream src,
        JSONObject jsonObject,
        String colibriClass)
    {
        endpoint.getConference().getVideobridge().getStatistics().
            totalDataChannelMessagesReceived.incrementAndGet();

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
     * @param src the {@link WebRtcDataStream) by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with
     * {@link Videobridge#COLIBRI_CLASS} EndpointMessage which has been
     * received by the associated {@code SctpConnection}
     *
     * EndpointMessage definition:
     * 'to': If the 'to' field contains the endpoint id of another endpoint
     * in this conference, the message will be treated as a 1:1 message and
     * forwarded just to that endpoint.  If the 'to' field is an empty
     * string, the message will be treated as a broadcast and sent to all other
     * endpoints in this conference.
     * 'msgPayload': An opaque payload.  The bridge does not need to know or
     * care what is contained in the 'msgPayload' field, it will just forward
     * it blindly.
     *
     * NOTE: This message is designed to allow endpoints to pass their own
     * application-specific messaging to one another without requiring the
     * bridge to know of or understand every message type.  These messages
     * will be forwarded by the bridge using the same DataChannel as other
     * jitsi messages (e.g. active speaker and last-n notifications).
     * It is not recommended to send high-volume message traffic on this
     * channel (e.g. file transfer), such that it may interfere with other
     * jitsi-messages.
     */
    @SuppressWarnings("unchecked")
    private void onClientEndpointMessage(
        WebRtcDataStream src,
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
            for (Endpoint endpoint : conference.getEndpoints())
            {
                if (!endpoint.getID().equalsIgnoreCase(endpoint.getID()))
                {
                    endpointSubset.add(endpoint);
                }
            }
            conference.sendMessageOnDataChannels(
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
                conference.sendMessageOnDataChannels(
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
     * has been received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received by the
     * associated {@code SctpConnection}
     */
    private void onPinnedEndpointChangedEvent(
        WebRtcDataStream src,
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
     * has been received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received by the
     * associated {@code SctpConnection}
     */
    private void onPinnedEndpointsChangedEvent(
        WebRtcDataStream src,
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
     * has been received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received by the
     * associated {@code SctpConnection}
     */
    private void onSelectedEndpointChangedEvent(
        WebRtcDataStream src,
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
     * {@code SelectedEndpointsChangedEvent} has been received by the associated
     * {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received by the
     * associated {@code SctpConnection}
     */
    private void onSelectedEndpointsChangedEvent(
        WebRtcDataStream src,
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
     * has been received by the associated {@code SctpConnection}.
     *
     * @param src the {@code WebRtcDataStream} by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code LastNChangedEvent} which has been received by the
     * associated {@code SctpConnection}
     */
    private void onLastNChangedEvent(
        WebRtcDataStream src,
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
     * {@inheritDoc}
     */
    @Override
    public void onStringData(WebRtcDataStream src, String msg)
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
     * Sends a specific <tt>String</tt> <tt>msg</tt> over the data channel of
     * this <tt>Endpoint</tt>.
     *
     * @param msg message text to send.
     * @throws IOException
     */
    public void sendMessageOnDataChannel(String msg)
        throws IOException
    {
        SctpConnection sctpConnection = endpoint.getSctpConnection();
        String endpointId = endpoint.getID();

        if (sctpConnection == null)
        {
            logger.warn("No SCTP connection with " + endpointId + ".");
        }
        else if (sctpConnection.isReady())
        {
            try
            {
                WebRtcDataStream dataStream
                    = sctpConnection.getDefaultDataStream();

                if (dataStream == null)
                {
                    logger.warn(
                        "WebRtc data channel with " + endpointId
                            + " not opened yet.");
                }
                else
                {
                    dataStream.sendString(msg);
                    endpoint.getConference().getVideobridge().getStatistics()
                        .totalDataChannelMessagesSent.incrementAndGet();
                }
            }
            catch (IOException e)
            {
                // We _don't_ want to silently fail to deliver a message because
                // some functions of the bridge depends on being able to
                // reliably deliver a message through data channels.
                throw e;
            }
        }
        else
        {
            logger.warn(
                "SCTP connection with " + endpointId + " not ready yet.");
        }
    }
}
