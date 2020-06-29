/*
 * Copyright @ 2017 - Present, 8x8 Inc
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

import com.google.common.collect.*;
import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.message.*;
import org.jitsi.videobridge.octo.*;
import org.jitsi.videobridge.util.*;
import org.json.simple.*;

import java.io.*;
import java.util.*;


/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. An abstract implementation.
 *
 * @author Boris Grozev
 */
public abstract class AbstractEndpointMessageTransport<T extends AbstractEndpoint>
    extends MessageHandler
{
    /**
     * The {@link Endpoint} associated with this
     * {@link EndpointMessageTransport}.
     */
    protected final T endpoint;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    protected final @NotNull Logger logger;

    /**
     * Initializes a new {@link AbstractEndpointMessageTransport} instance.
     * @param endpoint the endpoint to which this transport belongs
     */
    public AbstractEndpointMessageTransport(T endpoint, @NotNull Logger parentLogger)
    {
        this.endpoint = endpoint;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    /**
     *
     * @return true if this message transport is 'connected', false otherwise
     */
    public abstract boolean isConnected();

    /**
     * Fires the message transport ready event for the associated endpoint.
     */
    protected void notifyTransportChannelConnected()
    {
    }

    /**
     * Handles an opaque message from this {@code Endpoint} that should be
     * forwarded to either: a) another client in this conference (1:1
     * message) or b) all other clients in this conference (broadcast message)
     *
     * @param message the message that was received from the endpoint.
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
    @Override
    public BridgeChannelMessage endpointMessage(EndpointMessage message)
    {
        String to = message.getTo();

        // First insert/overwrite the "from" to prevent spoofing.
        message.setFrom(getId());
        Conference conference = getConference();

        if (conference == null || conference.isExpired())
        {
            logger.warn(
                "Unable to send EndpointMessage, conference is null or expired");
            return null;
        }

        AbstractEndpoint sourceEndpoint = conference.getEndpoint(getId());

        if (sourceEndpoint == null)
        {
            logger.warn("Can not forward message, source endpoint not found.");
            // The source endpoint might have expired. If it was an Octo
            // endpoint and we forward the message, we may mistakenly forward
            // it back through Octo and cause a loop.
            return null;
        }

        List<AbstractEndpoint> targets;
        if ("".equals(to))
        {
            // Broadcast message
            targets = new LinkedList<>(conference.getEndpoints());
            targets.removeIf(e -> e.getID().equalsIgnoreCase(getId()));
        }
        else
        {
            // 1:1 message
            AbstractEndpoint targetEndpoint = conference.getEndpoint(to);
            if (targetEndpoint != null)
            {
                targets = Collections.singletonList(targetEndpoint);
            }
            else
            {
                logger.warn(
                    "Unable to find endpoint " + to
                        + " to send EndpointMessage");
                return null;
            }
        }

        boolean sendToOcto
            = !(sourceEndpoint instanceof OctoEndpoint)
              && targets.stream().anyMatch(e -> (e instanceof OctoEndpoint));

        // TODO: do we want to off-load this to another IO thread?
        conference.sendMessage(
                message.toJson(),
                targets,
                sendToOcto);
        return null;
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
    protected String getId()
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
     * Notifies this {@link EndpointMessageTransport} that a specific message
     * has been received on a specific transport channel.
     * @param src the transport channel on which the message has been received.
     * @param msg the message that was received.
     */
    public void onMessage(Object src, String msg)
    {
        BridgeChannelMessage message;

        try
        {
            message = BridgeChannelMessage.Companion.parse(msg);
        }
        catch (IOException ioe)
        {
            logger.warn("Invalid message received: " + msg, ioe);
            return;
        }

        TaskPools.IO_POOL.submit(() ->
        {
            BridgeChannelMessage response = handleMessage(message);
            if (response != null)
            {
                sendMessage(src, response.toJson());
            }
        });
    }

    /**
     * Sends a specific message over the active transport channels of this
     * {@link EndpointMessageTransport}.
     *
     * @param msg message text to send.
     */
    protected void sendMessage(String msg)
    {
    }

    protected void sendMessage(Object dst, String message)
    {
    }

    /**
     * Closes this {@link EndpointMessageTransport}.
     */
    protected void close()
    {
    }

    public JSONObject getDebugState() {
        return new JSONObject();
    }

    /**
     * Events generated by {@link AbstractEndpointMessageTransport} types which
     * are of interest to other entities.
     */
    interface EndpointMessageTransportEventHandler {
        void endpointMessageTransportConnected(@NotNull AbstractEndpoint endpoint);
    }
}
