/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.simulcast;

/**
* Created by gp on 16/10/14.
*/
public class SimulcastReceiverOptions
{

    private Integer nextOrder;

    /**
     * A switch that requires a key frame.
     */
    private boolean hardSwitch;

    private Integer overrideOrder;

    public void setUrgent(boolean urgent)
    {
        this.urgent = urgent;
    }

    public void setNextOrder(Integer nextOrder)
    {
        this.nextOrder = nextOrder;
    }

    public void setHardSwitch(boolean hardSwitch)
    {
        this.hardSwitch = hardSwitch;
    }

    /**
     * A switch that is urgent (e.g. because of a layer drop).
     */
    private boolean urgent;

    public Integer getNextOrder()
    {
        return nextOrder;
    }

    public boolean isHardSwitch()
    {
        return hardSwitch;
    }

    public boolean isUrgent()
    {
        return urgent;
    }

    public Integer getOverrideOrder()
    {
        return overrideOrder;
    }

    public void setOverrideOrder(Integer overrideOrder)
    {
        this.overrideOrder = overrideOrder;
    }
}
