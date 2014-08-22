/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import org.jitsi.service.neomedia.*;
import org.jitsi.videobridge.*;

/**
 * Created by gp on 20/08/14.
 */
public interface BridgeRTCPTerminationStrategy extends RTCPTerminationStrategy
{
    public void setConference(Conference conference);
}
