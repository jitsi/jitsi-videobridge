/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.videobridge.rtcp;

import net.sf.fmj.media.rtp.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.service.neomedia.*;

import java.util.*;

/**
 * Implements a transformer which intercepts RTCP NACK packets and processes
 * them via a <tt>NACKHandler</tt> instance. The processed packets are removed
 * (are not included in the result of the transformation).
 * @author Boris Grozev
 */
public class NACKNotifier
        implements Transformer<RTCPCompoundPacket>
{
    /**
     * The handler to use for intercepted NACK packets.
     */
    private NACKHandler handler;

    /**
     * Whether this transformer is enabled or not.
     */
    private boolean enabled = true;

    /**
     * Initializes a new <tt>NACKNotifier</tt> which is to use a specific
     * <tt>NACKHandler</tt> instalce.
     * @param handler the <tt>NACKHandler</tt> instance to use.
     */
    public NACKNotifier(NACKHandler handler)
    {
        this.handler = handler;
    }

    /**
     * Looks for RTCP NACK packets contained in the compound packet
     * <tt>inPacket</tt>, passes them on the the handler and removes them from
     * the resulting compount packet.
     * @param inPacket the input RTCP compound packet.
     * @return a packet which consists of the packets from <tt>inPacket</tt>,
     * with NACK packets removed.
     */
    @Override
    public RTCPCompoundPacket reverseTransform(RTCPCompoundPacket inPacket)
    {
        if (!enabled || inPacket == null || inPacket.packets == null
                || inPacket.packets.length == 0)
        {
            return inPacket;
        }

        List<RTCPPacket> outPackets = new LinkedList<RTCPPacket>();
        boolean removed = false;

        // Intercept NACKs and forward them to the handler (e.g. the
        // VideoChannel)
        for (RTCPPacket p : inPacket.packets)
        {
            if (p != null && p instanceof NACKPacket)
            {
                NACKPacket nack = (NACKPacket) p;
                handler.handleNACK(nack);

                // Drop the packet by not including it in outPackets
                removed = true;
            }
            else
            {
                outPackets.add(p);
            }
        }

        if (removed)
        {
            if (outPackets.size() > 0)
                return new RTCPCompoundPacket(outPackets.toArray(
                        new RTCPPacket[outPackets.size()]));
            else
                return null;
        }
        else
        {
            return inPacket;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        // nothing to be done here
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RTCPCompoundPacket transform(RTCPCompoundPacket inPacket)
    {
        return inPacket;
    }
}

