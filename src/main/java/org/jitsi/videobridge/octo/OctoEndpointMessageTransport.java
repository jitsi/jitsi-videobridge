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
import org.jitsi.videobridge.message.*;

/**
 * Extends {@link AbstractEndpointMessageTransport} for the purposes of Octo.
 *
 * Most {@link MessageHandler} methods are not overridden and result in a
 * warning being logged, because we don't expect to received them via the Octo
 * channel.
 * {@link #endpointMessage(EndpointMessage)} is an exception, where the logic in
 * the super class applies.
 */
class OctoEndpointMessageTransport
    extends AbstractEndpointMessageTransport<OctoEndpoint>
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
}
