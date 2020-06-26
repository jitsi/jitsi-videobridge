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

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Handles the functionality related to sending and receiving COLIBRI messages
 * for an {@link Endpoint}. An abstract implementation.
 *
 * @author Boris Grozev
 */
public abstract class AbstractEndpointMessageTransport
{
    /**
     * The {@link Endpoint} associated with this
     * {@link EndpointMessageTransport}.
     */
    protected final AbstractEndpoint endpoint;

    /**
     * The {@link Logger} to be used by this instance to print debug
     * information.
     */
    protected final @NotNull Logger logger;

    /**
     * The compatibility layer that translates selected, pinned and max
     * resolution messages into video constraints.
     */
    private final VideoConstraintsCompatibility
        videoConstraintsCompatibility = new VideoConstraintsCompatibility();

    /**
     * Initializes a new {@link AbstractEndpointMessageTransport} instance.
     * @param endpoint the endpoint to which this transport belongs
     */
    public AbstractEndpointMessageTransport(AbstractEndpoint endpoint, @NotNull Logger parentLogger)
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
     * Notifies this {@link AbstractEndpointMessageTransport} that a
     * {@link ClientHelloMessage} has been received on a particular channel.
     *
     * @param src the channel on which the message was received.
     * @param message the message.
     */
    protected void clientHello(Object src, ClientHelloMessage message)
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
    protected void clientEndpointMessage(EndpointMessage message)
    {
        String to = message.getTo();

        // First insert/overwrite the "from" to prevent spoofing.
        message.setFrom(getId());
        Conference conference = getConference();

        if (conference == null || conference.isExpired())
        {
            logger.warn(
                "Unable to send EndpointMessage, conference is null or expired");
            return;
        }

        AbstractEndpoint sourceEndpoint = conference.getEndpoint(getId());

        if (sourceEndpoint == null)
        {
            logger.warn("Can not forward message, source endpoint not found.");
            // The source endpoint might have expired. If it was an Octo
            // endpoint and we forward the message, we may mistakenly forward
            // it back through Octo and cause a loop.
            return;
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
                return;
            }
        }

        boolean sendToOcto
            = !(sourceEndpoint instanceof OctoEndpoint)
              && targets.stream().anyMatch(e -> (e instanceof OctoEndpoint));

        conference.sendMessage(
                message.toJson(),
                targets,
                sendToOcto);
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
     * Notifies this {@code Endpoint} that a {@link PinnedEndpointMessage}
     * has been received.
     *
     * @param message the message that was received.
     */
    private void pinnedEndpointChangedEvent(PinnedEndpointMessage message)
    {
        String newPinnedEndpointID = message.getPinnedEndpoint();

        List<String> newPinnedIDs =
                isBlank(newPinnedEndpointID) ?
                        Collections.emptyList() :
                        Collections.singletonList(newPinnedEndpointID);

        pinnedEndpointsChangedEvent(new PinnedEndpointsMessage(newPinnedIDs));
    }

    /**
     * Notifies this {@code Endpoint} that a {@code PinnedEndpointsChangedEvent}
     * has been received.
     *
     * @param message the message that was received.
     */
    private void pinnedEndpointsChangedEvent(PinnedEndpointsMessage message)
    {
        Set<String> newPinnedEndpoints = new HashSet<>(message.getPinnedEndpoints());

        if (logger.isDebugEnabled())
        {
            logger.debug("Pinned " + newPinnedEndpoints);
        }

        videoConstraintsCompatibility.setPinnedEndpoints(newPinnedEndpoints);
        receiverVideoConstraintsChangedEvent(
                videoConstraintsCompatibility.computeVideoConstraints());
    }

    /**
     * Notifies this {@code Endpoint} that a {@link SelectedEndpointsMessage}
     * has been received.
     *
     * @param message the message that was received.
     */
    private void selectedEndpointChangedEvent(SelectedEndpointMessage message)
    {
        String newSelectedEndpointID = message.getSelectedEndpoint();

        List<String> newSelectedIDs =
            isBlank(newSelectedEndpointID) ?
                    Collections.emptyList() :
                    Collections.singletonList(newSelectedEndpointID);

        selectedEndpointsChangedEvent(
                new SelectedEndpointsMessage(newSelectedIDs));
    }

    /**
     * Notifies this {@code Endpoint} that a {@link SelectedEndpointsMessage}
     * has been received.
     *
     * @param message the message that was received.
     */
    private void selectedEndpointsChangedEvent(SelectedEndpointsMessage message)
    {
        Set<String> newSelectedEndpoints = new HashSet<>(message.getSelectedEndpoints());

        videoConstraintsCompatibility.setSelectedEndpoints(newSelectedEndpoints);
        receiverVideoConstraintsChangedEvent(
                videoConstraintsCompatibility.computeVideoConstraints());
    }

    /**
     * Sets the sender video constraints of this {@link #endpoint}.
     *
     * @param videoConstraintsMap the sender video constraints of this
     * {@link #endpoint}.
     */
    private void receiverVideoConstraintsChangedEvent(
        Map<String, VideoConstraints> videoConstraintsMap)
    {
        // Don't "pollute" the video constraints map with constraints for this
        // endpoint.
        videoConstraintsMap.remove(endpoint.getID());
        endpoint.setSenderVideoConstraints(
            ImmutableMap.copyOf(videoConstraintsMap));
    }

    /**
     * Notifies this {@code Endpoint} that a {@link LastNMessage} has been
     * received.
     *
     * @param message the message that was received.
     */
    protected void lastNChangedEvent(LastNMessage message)
    {
        int lastN = message.getLastN();

        if (endpoint != null)
        {
            endpoint.setLastN(lastN);
        }
    }

    /**
     * Notifies this {@code Endpoint} that a
     * {@link ReceiverVideoConstraintsMessage} has been received
     *
     * @param message the message that was received.
     */
    protected abstract void receiverVideoConstraintsChangedEvent(
            ReceiverVideoConstraintsMessage message);

    /**
     * Notifies this {@code Endpoint} that a
     * {@link ReceiverVideoConstraintMessage} has been received
     *
     * @param message the message that was received.
     */
    protected void receiverVideoConstraintEvent(
            ReceiverVideoConstraintMessage message)
    {
        int maxFrameHeight = message.getMaxFrameHeight();
        if (logger.isDebugEnabled())
        {
            logger.debug(
                "Received a maxFrameHeight video constraint from "
                    + getId() + ": " + maxFrameHeight);
        }

        videoConstraintsCompatibility.setMaxFrameHeight(maxFrameHeight);
        receiverVideoConstraintsChangedEvent(videoConstraintsCompatibility.computeVideoConstraints());
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

        // This cries for kotlin
        TaskPools.IO_POOL.submit(() ->
        {
            if (message instanceof SelectedEndpointsMessage)
            {
                selectedEndpointsChangedEvent((SelectedEndpointsMessage) message);
            }
            else if (message instanceof SelectedEndpointMessage)
            {
                selectedEndpointChangedEvent((SelectedEndpointMessage) message);
            }
            else if (message instanceof PinnedEndpointsMessage)
            {
                pinnedEndpointsChangedEvent((PinnedEndpointsMessage) message);
            }
            else if (message instanceof PinnedEndpointMessage)
            {
                pinnedEndpointChangedEvent((PinnedEndpointMessage) message);
            }
            else if (message instanceof ClientHelloMessage)
            {
                clientHello(src, (ClientHelloMessage) message);
            }
            else if (message instanceof EndpointMessage)
            {
                clientEndpointMessage((EndpointMessage) message);
            }
            else if (message instanceof LastNMessage)
            {
                lastNChangedEvent((LastNMessage) message);
            }
            else if (message instanceof ReceiverVideoConstraintMessage)
            {
                receiverVideoConstraintEvent((ReceiverVideoConstraintMessage) message);
            }
            else if (message instanceof ReceiverVideoConstraintsMessage)
            {
                receiverVideoConstraintsChangedEvent((ReceiverVideoConstraintsMessage) message);
            }
            else
            {
                logger.warn("Unhandled message: " + msg);
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
