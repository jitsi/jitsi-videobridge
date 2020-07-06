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
     * with certain constraints for a specific endpoin.
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
     *
     * EndpointMessage definition:
     * 'to': If the 'to' field contains the endpoint id of another endpoint in this conference, the message will be
     * treated as a 1:1 message and forwarded just to that endpoint. If the 'to' field is an empty string, the message
     * will be treated as a broadcast and sent to all local endpoints in this conference.
     * 'msgPayload': An opaque payload. The bridge does not need to know or care what is contained in the 'msgPayload'
     * field, it will just forward it blindly.
     */
    @Override
    public BridgeChannelMessage endpointMessage(EndpointMessage message)
    {
        String to = message.getTo();

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
        if ("".equals(to))
        {
            // Broadcast message
            targets = new LinkedList<>(conference.getLocalEndpoints());
        }
        else
        {
            // 1:1 message
            AbstractEndpoint targetEndpoint = conference.getLocalEndpoint(to);
            if (targetEndpoint != null)
            {
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

}
