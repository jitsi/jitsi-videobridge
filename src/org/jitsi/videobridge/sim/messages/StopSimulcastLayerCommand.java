/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim.messages;

import org.jitsi.videobridge.sim.*;

/**
* Created by gp on 14/10/14.
*/
public class StopSimulcastLayerCommand
{
    public StopSimulcastLayerCommand(SimulcastLayer simulcastLayer)
    {
        this.simulcastLayer = simulcastLayer;
    }

    // TODO(gp) rename this to StopSimulcastLayerCommand
    final String colibriClass = "StopSimulcastLayerEvent";
    final SimulcastLayer simulcastLayer;
}
