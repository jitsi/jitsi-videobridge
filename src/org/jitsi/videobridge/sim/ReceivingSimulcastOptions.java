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
public class ReceivingSimulcastOptions
{
    public ReceivingSimulcastOptions(int targetOrder, boolean hardSwitch)
    {
        this.targetOrder = targetOrder;
        this.hardSwitch = hardSwitch;
    }

    private final int targetOrder;

    private final boolean hardSwitch;

    public int getTargetOrder()
    {
        return targetOrder;
    }

    public boolean isHardSwitch()
    {
        return hardSwitch;
    }
}
