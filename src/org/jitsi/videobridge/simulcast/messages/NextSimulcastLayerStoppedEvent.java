package org.jitsi.videobridge.simulcast.messages;

/**
 * Created by gp on 14/12/14.
 */
public class NextSimulcastLayerStoppedEvent
{
    final String colibriClass = "NextSimulcastLayerStoppedEvent";
    public EndpointSimulcastLayer[] endpointSimulcastLayers;
}
