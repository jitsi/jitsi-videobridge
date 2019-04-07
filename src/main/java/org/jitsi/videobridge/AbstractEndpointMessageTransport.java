/*
 * Copyright @ 2017-2018 Atlassian Pty Ltd
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

import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import java.io.*;
import java.util.*;

import static org.jitsi.videobridge.EndpointMessageBuilder.*;

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. An abstract implementation.
 *
 * @author Boris Grozev
 */
public abstract class AbstractEndpointMessageTransport
{
    /**
     * The {@link Logger} used by the {@link Endpoint} class to print debug
     * information.
     */
    private static final Logger classLogger
        = Logger.getLogger(AbstractEndpointMessageTransport.class);

    /**
     * The {@link Endpoint} associated with this
     * {@link EndpointMessageTransport}.
     */
    protected final AbstractEndpoint endpoint;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    private final Logger logger;

    /**
     * Initializes a new {@link AbstractEndpointMessageTransport} instance.
     * @param endpoint
     */
    public AbstractEndpointMessageTransport(AbstractEndpoint endpoint)
    {
        this.endpoint = endpoint;
        this.logger
            = Logger.getLogger(
                classLogger,
                endpoint == null ? null : endpoint.getConference().getLogger());
    }

    /**
     * Fires the message transport ready event for the associated endpoint.
     */
    protected void notifyTransportChannelConnected()
    {
    }

    /**
     * Notifies this {@link AbstractEndpointMessageTransport} that a
     * {@code ClientHello} has been received.
     *
     * @param src the transport channel on which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code ClientHello} which has been received by the associated
     * {@code SctpConnection}
     */
    protected void onClientHello(Object src, JSONObject jsonObject)
    {
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
            logger.info(
                "Received a message with unknown colibri class: "
                    + colibriClass);
            break;
        }
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

        // First insert the "from" to prevent spoofing.
        jsonObject.put("from", getId(jsonObject.get("from")));
        Conference conference = getConference();
        if (conference == null || conference.isExpired())
        {
            logger.warn(
                "Unable to send EndpointMessage, conference is null or expired");
            return;
        }

        List<AbstractEndpoint> endpointSubset;
        if ("".equals(to))
        {
            // Broadcast message
            endpointSubset = new LinkedList<>(conference.getEndpoints());
            endpointSubset.removeIf(
                e -> e.getID().equalsIgnoreCase(getId()));
        }
        else
        {
            // 1:1 message
            AbstractEndpoint targetEndpoint = conference.getEndpoint(to);
            if (targetEndpoint != null)
            {
                endpointSubset = Collections.singletonList(targetEndpoint);
            }
            else
            {
                endpointSubset = Collections.emptyList();
                logger.warn(
                    "Unable to find endpoint " + to
                        + " to send EndpointMessage");
            }
        }

        sendMessageToEndpoints(jsonObject.toString(), endpointSubset);
    }

    /**
     * Sends a specific message coming from this endpoint to other endpoints in
     * the conference.
     * @param msg the message to send.
     * @param endpoints the list of endpoints to receive the message.
     */
    protected void sendMessageToEndpoints(
        String msg,
        List<AbstractEndpoint> endpoints)
    {
        Conference conference = getConference();
        if (conference != null)
        {
            conference.sendMessage(msg, endpoints, true);
        }
    }

    /**
     * @return the associated {@link Conference} or {@code null}.
     */
    protected Conference getConference()
    {
        return endpoint != null ? endpoint.getConference() : null;
    }

    /**
     * @return the ID of the associated endpoint or {@code null}.
     */
    private String getId()
    {
        return getId(null);
    }

    /**
     * @return the ID of the associated endpoint or {@code null}.
     * @param id a suggested ID.
     */
    protected String getId(Object id)
    {
        return endpoint != null ? endpoint.getID() : null;
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
    abstract protected void onPinnedEndpointChangedEvent(
        Object src,
        JSONObject jsonObject);

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointsChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code PinnedEndpointChangedEvent} which has been received.
     */
    abstract protected void onPinnedEndpointsChangedEvent(
        Object src,
        JSONObject jsonObject);

    /**
     * Notifies this {@code Endpoint} that a {@code SelectedEndpointChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received.
     */
    abstract protected void onSelectedEndpointChangedEvent(
        Object src,
        JSONObject jsonObject);

    /**
     * Notifies this {@code Endpoint} that a
     * {@code SelectedEndpointsChangedEvent} has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has
     * been received
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code SelectedEndpointChangedEvent} which has been received.
     */
    abstract protected void onSelectedEndpointsChangedEvent(
        Object src,
        JSONObject jsonObject);

    /**
     * Notifies this {@code Endpoint} that a {@code LastNChangedEvent}
     * has been received.
     *
     * @param src the transport channel by which {@code jsonObject} has been
     * received.
     * @param jsonObject the JSON object with {@link Videobridge#COLIBRI_CLASS}
     * {@code LastNChangedEvent} which has been received.
     */
    protected void onLastNChangedEvent(
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

        if (endpoint != null)
        {
            for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO))
            {
                channel.setLastN(lastN);
            }
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
    protected void onReceiverVideoConstraintEvent(
        Object src,
        JSONObject jsonObject)
    {
        Object o = jsonObject.get("maxFrameHeight");
        if (!(o instanceof Number))
        {
            logger.warn(
                "Received a non-number maxFrameHeight video constraint from "
                    + getId() + ": " + o.toString());
            return;
        }
        int maxFrameHeight = ((Number) o).intValue();
        if (logger.isDebugEnabled())
        {
            logger.debug(
                "Received a maxFrameHeight video constraint from "
                    + getId() + ": " + maxFrameHeight);
        }

        if (endpoint != null)
        {
            for (RtpChannel channel : endpoint.getChannels(MediaType.VIDEO))
            {
                if (channel instanceof VideoChannel)
                {
                    ((VideoChannel) channel).setMaxFrameHeight(maxFrameHeight);
                }
            }
        }
    }

    /**
     * Notifies this {@link EndpointMessageTransport} that a specific message
     * has been received on a specific transport channel.
     * @param src the transport channel on which the message has been received.
     * @param msg the message which has been received.
     */
    public void onMessage(Object src, String msg)
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
                    "Malformed JSON received from endpoint " + getId(),
                    ex);
            obj = null;
        }

        // We utilize JSONObjects only.
        if (obj instanceof JSONObject)
        {
            JSONObject jsonObject = (JSONObject) obj;
            // We utilize JSONObjects with colibriClass only.
            String colibriClass
                = (String)jsonObject.get(Videobridge.COLIBRI_CLASS);

            if (colibriClass != null)
            {
                onJSONData(src, jsonObject, colibriClass);
            }
            else
            {
                logger.warn(
                    "Malformed JSON received from endpoint " + getId()
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
    protected void sendMessage(String msg)
        throws IOException
    {
    }

    /**
     * Closes this {@link EndpointMessageTransport}.
     */
    protected void close()
    {
    }
}
