/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.videobridge.rtcp;

import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.util.function.*;

import java.util.*;

/**
 * Implements a transformer which intercepts RTCP NACK packets and processes
 * them via a <tt>NACKHandler</tt> instance. The processed packets are removed
 * (are not included in the result of the transformation).
 * @author Boris Grozev
 */
public class NACKNotifier
    extends SinglePacketTransformer
    implements TransformEngine
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
     * The <tt>Function</tt> that generates <tt>RTCPCompoundPacket</tt>s from
     * <tt>RawPacket</tt>s.
     */
    private RTCPPacketParserEx parser = new RTCPPacketParserEx();

    /**
     * The <tt>Function</tt> that generates <tt>RawPacket</tt>s from
     * <tt>RTCPCompoundPacket</tt>s.
     */
    private RTCPGenerator generator = new RTCPGenerator();

    /**
     * Initializes a new <tt>NACKNotifier</tt> which is to use a specific
     * <tt>NACKHandler</tt> instalce.
     * @param handler the <tt>NACKHandler</tt> instance to use.
     */
    public NACKNotifier(NACKHandler handler)
    {
        this.handler = handler;
    }

    @Override
    public RawPacket transform(RawPacket pkt)
    {
        return pkt;
    }

    /**
     * Looks for RTCP NACK packets contained in the compound packet
     * <tt>inPacket</tt>, passes them on the the handler and removes them from
     * the resulting compount packet.
     * @param pkt the input RTCP compound packet.
     * @return a packet which consists of the packets from <tt>inPacket</tt>,
     * with NACK packets removed.
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
        if (!enabled)
        {
            return pkt;
        }

        RTCPCompoundPacket inPacket;
        try
        {
            inPacket = (RTCPCompoundPacket) parser.parse(
                pkt.getBuffer(),
                pkt.getOffset(),
                pkt.getLength());
        }
        catch (BadFormatException e)
        {
            return null;
        }

        if (inPacket == null || inPacket.packets == null
            || inPacket.packets.length == 0)
        {
            return pkt;
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
            {
                RTCPCompoundPacket outPacket
                    = new RTCPCompoundPacket(outPackets.toArray(
                        new RTCPPacket[outPackets.size()]));

                return generator.apply(outPacket);
            }
            else
                return null;
        }
        else
        {
            return pkt;
        }
    }

    public PacketTransformer getRTPTransformer()
    {
        return null;
    }

    public PacketTransformer getRTCPTransformer()
    {
        return this;
    }
}

