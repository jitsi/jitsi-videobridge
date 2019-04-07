/*
 * Copyright @ 2018 Atlassian Pty Ltd
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

import org.jitsi.utils.logging.*;
import org.jitsi.videobridge.*;
import org.json.simple.*;

import java.util.*;

/**
 * Extends {@link AbstractEndpointMessageTransport} for the purposes of Octo.
 */
public class OctoEndpointMessageTransport
    extends AbstractEndpointMessageTransport
{
    /**
     * The {@link Logger} used by the {@link RtpChannel} class and its instances
     * to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(OctoEndpointMessageTransport.class);

    /**
     * The associated {@link OctoEndpoints}.
     */
    private final OctoEndpoints octoEndpoints;

    /**
     * Initializes a new {@link AbstractEndpointMessageTransport} instance.
     */
    public OctoEndpointMessageTransport(OctoEndpoints octoEndpoints)
    {
        super(null);
        this.octoEndpoints = octoEndpoints;
    }

    @Override
    protected Conference getConference()
    {
        return octoEndpoints.getConference();
    }

    /**
     * We know this message came from another jitsi-videobridge instance (as
     * opposed to a client endpoint), so we trust the ID that it provided.
     * @param id a suggested ID.
     */
    @Override
    protected String getId(Object id)
    {
        if (id == null || !(id instanceof String))
        {
            return null;
        }
        return (String) id;
    }

    /**
     * Overrides the default implementation to prevent the message from being
     * sent on the Octo channel (which is where it came from).
     *
     * @param msg the message to send.
     * @param endpoints the list of endpoints to receive the message.
     */
    @Override
    protected void sendMessageToEndpoints(
        String msg,
        List<AbstractEndpoint> endpoints)
    {
        Conference conference = getConference();
        if (conference != null)
        {
            // Do NOT send the message to Octo, that's where it came from!
            conference.sendMessage(msg, endpoints, false /* sendToOcto */);
        }
    }

    /**
     * {@inheritDoc}
     * </p>
     * We don't expect any of these messages to go through Octo, so we log a
     * message.
     */
    @Override
    protected void onSelectedEndpointChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    /**
     * {@inheritDoc}
     * </p>
     * We don't expect any of these messages to go through Octo, so we log a
     * message.
     */
    @Override
    protected void onSelectedEndpointsChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    /**
     * {@inheritDoc}
     * </p>
     * We don't expect any of these messages to go through Octo, so we log a
     * message.
     */
    @Override
    protected void onPinnedEndpointChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    /**
     * {@inheritDoc}
     * </p>
     * We don't expect any of these messages to go through Octo, so we log a
     * message.
     */
    @Override
    protected void onPinnedEndpointsChangedEvent(
        Object src,
        JSONObject jsonObject)
    {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    /**
     * {@inheritDoc}
     * </p>
     * We don't expect any of these messages to go through Octo, so we log a
     * message.
     */
    @Override
    protected void onClientHello(Object src, JSONObject jsonObject)
    {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    /**
     * {@inheritDoc}
     * </p>
     * We don't expect any of these messages to go through Octo, so we log a
     * message.
     */
    @Override
    protected void onReceiverVideoConstraintEvent(
        Object src,
        JSONObject jsonObject)
    {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    /**
     * Logs a warning about an unexpected message received through Octo.
     * @param msg the received message.
     */
    private void logUnexpectedMessage(String msg)
    {
        logger.warn("Received an unexpected message type through Octo: " + msg);
    }
}
