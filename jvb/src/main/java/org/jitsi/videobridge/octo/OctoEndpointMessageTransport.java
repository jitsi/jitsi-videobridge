/*
 * Copyright @ 2018 - Present, 8x8 Inc
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
package org.jitsi.videobridge.octo;

import org.jetbrains.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.message.*;

import java.util.*;
import java.util.stream.*;

/**
 * Extends {@link AbstractEndpointMessageTransport} for the purposes of Octo.
 * This handles incoming messages for a whole conference, and not for a
 * specific {@link OctoEndpoint}.
 */
class OctoEndpointMessageTransport
    extends AbstractEndpointMessageTransport<OctoEndpoint>
{
    /**
     * The associated {@link OctoEndpoints}.
     */
    @NotNull
    private final OctoEndpoints octoEndpoints;

    /**
     * Initializes a new {@link AbstractEndpointMessageTransport} instance.
     */
    OctoEndpointMessageTransport(@NotNull OctoEndpoints octoEndpoints, Logger parentLogger)
    {
        super(parentLogger);
        this.octoEndpoints = octoEndpoints;
    }

    @Override
    public boolean isConnected()
    {
        return true;
    }

    /**
     * Logs a warning about an unexpected message received through Octo.
     * @param message the received message.
     */
    @Override
    public void unhandledMessage(BridgeChannelMessage message)
    {
        logger.warn("Received a message with an unexpected type: " + message.getType());
    }

    /**
     * This message indicates that a remote bridge wishes to receive video
     * with certain constraints for a specific endpoint.
     * @param message
     * @return
     */
    @Nullable
    @Override
    public BridgeChannelMessage addReceiver(@NotNull AddReceiverMessage message)
    {
        Conference conference = octoEndpoints.getConference();
        AbstractEndpoint endpoint = conference.getEndpoint(message.getEndpointId());
        // Since we currently broadcast everything in Octo, we may receive messages intended for another bridge. Handle
        // only those that reference an endpoint local to this bridge.
        if (endpoint instanceof Endpoint)
        {
            endpoint.addReceiver(message.getBridgeId(), message.getVideoConstraints());
        }

        return null;
    }

    @Nullable
    @Override
    public BridgeChannelMessage removeReceiver(@NotNull RemoveReceiverMessage message)
    {
        Conference conference = octoEndpoints.getConference();
        AbstractEndpoint endpoint = conference.getEndpoint(message.getEndpointId());
        // Since we currently broadcast everything in Octo, we may receive messages intended for another bridge. Handle
        // only those that reference an endpoint local to this bridge.
        if (endpoint instanceof Endpoint)
        {
            endpoint.removeReceiver(message.getBridgeId());
        }

        return null;
    }

    /**
     * Handles an opaque message received on the Octo channel. The message originates from an endpoint with an ID of
     * {@code message.getFrom}, as verified by the remote bridge sending the message.
     *
     * @param message the message that was received from the endpoint.
     */
    @Override
    public BridgeChannelMessage endpointMessage(EndpointMessage message)
    {
        // We trust the "from" field, because it comes from another bridge, not an endpoint.
        //String from = getId(message.getFrom());
        //message.setFrom(from);

        Conference conference = octoEndpoints.getConference();

        if (conference == null || conference.isExpired())
        {
            logger.warn("Unable to send EndpointMessage, conference is null or expired");
            return null;
        }

        List<AbstractEndpoint> targets;
        if (message.isBroadcast())
        {
            targets = new LinkedList<>(conference.getLocalEndpoints());
        }
        else
        {
            // 1:1 message
            String to = message.getTo();

            AbstractEndpoint targetEndpoint = conference.getLocalEndpoint(to);
            if (targetEndpoint != null)
            {
                // Broadcast message
                targets = Collections.singletonList(targetEndpoint);
            }
            else
            {
                logger.warn("Unable to find endpoint to send EndpointMessage to: " + to);
                return null;
            }
        }

        conference.sendMessage(message, targets, false /* sendToOcto */);
        return null;
    }

    /**
     * Handles an endpoint statistics message on the Octo channel that should be forwarded to
     * local endpoints as appropriate.
     *
     * @param message the message that was received from the endpoint.
     */
    @Override
    public BridgeChannelMessage endpointStats(@NotNull EndpointStats message)
    {
        // We trust the "from" field, because it comes from another bridge, not an endpoint.

        Conference conference = octoEndpoints.getConference();
        if (conference == null || conference.isExpired())
        {
            logger.warn("Unable to send EndpointStats, conference is null or expired");
            return null;
        }

        if (message.getFrom() == null)
        {
            logger.warn("Unable to send EndpointStats, missing from");
            return null;
        }

        AbstractEndpoint from = conference.getEndpoint(message.getFrom());
        if (from == null)
        {
            logger.warn("Unable to send EndpointStats, unknown endpoint " + from);
            return null;
        }

        List<AbstractEndpoint> targets =
            conference.getLocalEndpoints().stream().
                filter((ep) -> ep.wantsStatsFrom(from)).
                collect(Collectors.toList());

        conference.sendMessage(message, targets, false);
        return null;
    }

    @Nullable
    @Override
    public BridgeChannelMessage endpointConnectionStatus(@NotNull EndpointConnectionStatusMessage message)
    {
        Conference conference = octoEndpoints.getConference();

        if (conference == null || conference.isExpired())
        {
            logger.warn("Unable to send EndpointConnectionStatusMessage, conference is null or expired");
            return null;
        }

        conference.broadcastMessage(message, false /* sendToOcto */);
        return null;
    }
}
