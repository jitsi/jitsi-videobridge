/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim.messages;

/**
 * Represents a notification/event that is sent to an endpoint through data
 * channels when there is a change in the simulcast substream the bridge is
 * pushing to that specific endpoint.
 */
public class SimulcastLayersChangedEvent
{
    final String colibriClass = "SimulcastLayersChangedEvent";
    public EndpointSimulcastLayer[] endpointSimulcastLayers;
}
