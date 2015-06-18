/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.simulcast.messages;

import org.jitsi.videobridge.simulcast.*;

/**
* Created by gp on 14/10/14.
*/
public class StartSimulcastLayerCommand
{
    public StartSimulcastLayerCommand(SimulcastLayer simulcastLayer)
    {
        this.simulcastLayer = simulcastLayer;
    }

    // TODO(gp) rename this to StartSimulcastLayerCommand
    final String colibriClass = "StartSimulcastLayerEvent";
    final SimulcastLayer simulcastLayer;
}
