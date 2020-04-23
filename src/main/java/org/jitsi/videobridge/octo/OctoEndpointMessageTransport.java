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

import org.jitsi.utils.logging2.*;
import org.jitsi.videobridge.*;
import org.json.simple.*;

import java.util.*;

/**
 * Extends {@link AbstractEndpointMessageTransport} for the purposes of Octo.
 *
 * Most {@code on*Event} methods are overridden as no-ops because they don't make
 * sense for Octo and are never used. The single exception is
 * {@link #onClientEndpointMessage(Object, JSONObject)} which is not overridden
 * and the logic in the super class applies.
 */
class OctoEndpointMessageTransport
    extends AbstractEndpointMessageTransport
{
    /**
     * The associated {@link OctoEndpoints}.
     */
    private final OctoEndpoints octoEndpoints;

    /**
     * Initializes a new {@link AbstractEndpointMessageTransport} instance.
     */
    OctoEndpointMessageTransport(OctoEndpoints octoEndpoints, Logger parentLogger)
    {
        super(null, parentLogger);
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
        if (!(id instanceof String))
        {
            return null;
        }
        return (String) id;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onPinnedEndpointsChangedEvent(
        JSONObject jsonObject, Set<String> newPinnedEndpoints)
    {
        // This is a message from a remote bridge for a remote endpoint.
        String targetEndpointId
            = (String) jsonObject.get(PROP_TARGET_OCTO_ENDPOINT_ID);

        AbstractEndpoint targetEndpoint
            = getConference().getEndpoint(targetEndpointId);

        if (targetEndpoint != null)
        {
            targetEndpoint.pinnedEndpointsChanged(newPinnedEndpoints);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onSelectedEndpointsChangedEvent(
        JSONObject jsonObject, Set<String> newSelectedEndpoints)
    {
        // This is a message from a remote bridge for a remote endpoint.
        String targetEndpointId
            = (String) jsonObject.get(PROP_TARGET_OCTO_ENDPOINT_ID);

        AbstractEndpoint targetEndpoint
            = getConference().getEndpoint(targetEndpointId);

        if (targetEndpoint != null)
        {
            targetEndpoint.selectedEndpointsChanged(newSelectedEndpoints);
        }
    }

    /**
     * {@inheritDoc}
     * </p>
     * We don't expect any of these messages to go through Octo, so we log a
     * warning.
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
     * warning.
     */
    @Override
    protected void onReceiverVideoConstraintEvent(
        Object src,
        JSONObject jsonObject)
    {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    /**
     * {@inheritDoc}
     * </p>
     * We don't expect any of these messages to go through Octo, so we log a
     * warning.
     */
    @Override
    protected void onLastNChangedEvent(
            Object src,
            JSONObject jsonObject)
    {
        logUnexpectedMessage(jsonObject.toJSONString());
    }

    @Override
    public boolean isConnected()
    {
        return true;
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
