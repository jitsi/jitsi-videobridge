/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.simulcast.messages;

import org.jitsi.videobridge.simulcast.*;

/**
 * Associates a simulcast layer with an endpoint ID.
 */
public class EndpointSimulcastLayer
{
    public EndpointSimulcastLayer(String endpoint,
                                  SimulcastLayer simulcastLayer)
    {
        this.endpoint = endpoint;
        this.simulcastLayer = simulcastLayer;
    }

    public final String endpoint;
    public final SimulcastLayer simulcastLayer;
}
