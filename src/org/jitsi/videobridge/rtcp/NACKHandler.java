/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import org.jitsi.impl.neomedia.rtcp.*;

/**
 * A simple interface for handling RTCP NACK packets.
 * @author Boris Grozev
 */
public interface NACKHandler
{
    /**
     * Handles an RTCP NACK packet.
     * @param nackPacket the packet.
     */
    public void handleNACK(NACKPacket nackPacket);
}
