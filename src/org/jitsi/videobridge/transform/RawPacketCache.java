/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.transform;

import org.jitsi.impl.neomedia.*;

/**
 * An simple interface which allows a packet to be retrieved from a
 * cache/storage by an SSRC identifier and a sequence number.
 * @author Boris Grozev
 */
public interface RawPacketCache
{
    /**
     * Gets the packet with the given SSRC and RTP sequence number from the
     * cache. If no such packet is found, returns <tt>null</tt>.
     * @param ssrc The SSRC of the packet.
     * @param seq the RTP sequence number of the packet.
     * @return the packet with the given SSRC and RTP sequence number from the
     * cache. If no such packet is found, returns <tt>null</tt>.
     */
    public RawPacket get(long ssrc, int seq);
}
