/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import org.jitsi.impl.neomedia.rtcp.termination.strategies.*;
import org.jitsi.videobridge.*;

/**
 * @author George Politis
 */
public abstract class AbstractBridgeRTCPTerminationStrategy
    extends AbstractRTCPTerminationStrategy
{
    private Conference conference;

    /**
     * Sets the <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     *
     * @param conference The <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     */
    public void setConference(Conference conference)
    {
        this.conference = conference;
    }

    /**
     * Gets the <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     *
     * @return The <tt>Conference</tt> associated to this
     * <tt>BridgeRTCPTerminationStrategy</tt>
     */
    public Conference getConference()
    {
        return conference;
    }
}
