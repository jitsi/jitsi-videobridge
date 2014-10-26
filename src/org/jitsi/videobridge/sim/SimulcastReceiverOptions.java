/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.sim;

/**
* Created by gp on 16/10/14.
*/
public class SimulcastReceiverOptions
{

    private int targetOrder;

    /**
     * A switch that requires a key frame.
     */
    private boolean hardSwitch;

    public void setUrgent(boolean urgent)
    {
        this.urgent = urgent;
    }

    public void setTargetOrder(int targetOrder)
    {
        this.targetOrder = targetOrder;
    }

    public void setHardSwitch(boolean hardSwitch)
    {
        this.hardSwitch = hardSwitch;
    }

    /**
     * A switch that is urgent (e.g. because of a layer drop).
     */
    private boolean urgent;

    public int getTargetOrder()
    {
        return targetOrder;
    }

    public boolean isHardSwitch()
    {
        return hardSwitch;
    }

    public boolean isUrgent()
    {
        return urgent;
    }
}
