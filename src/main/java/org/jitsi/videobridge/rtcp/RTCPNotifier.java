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
 * Implements a transformer which intercepts incoming RTCP packets and notifies
 * configured handlers.
 *
 * @author Boris Grozev
 * @author George Politis
 */
public class RTCPNotifier
        extends SinglePacketTransformer
        implements TransformEngine
{
    /**
     * The handler to use for intercepted NACK packets.
     */
    private final NACKHandler nackHandler;

    /**
     * The handler to use for intercepted REMB packets.
     */
    private final REMBHandler rembHandler;

   /**
     * Whether this transformer is enabled or not.
     */
    private boolean enabled = true;

    /**
     * The parser for RTCP packets.
     */
    private RTCPPacketParserEx parser = new RTCPPacketParserEx();

    /**
     * The <tt>Function</tt> that generates <tt>RawPacket</tt>s from
     * <tt>RTCPCompoundPacket</tt>s.
     */
    private RTCPGenerator generator = new RTCPGenerator();

    /**
     * The flag which determines whether NACK packets will be dropped by this
     * transformer, or forwarded.
     */
    private boolean dropNack = true;

    /**
     * Initializes a new <tt>RTCPNotifier</tt> given a generic <tt>Object</tt>
     * as handler.
     * @param object an <tt>Object</tt> which will be used as handler for
     * all supported RTCP packet types (if it implements the required
     * interface).
     */
    public RTCPNotifier(Object object)
    {
        nackHandler
                = (object instanceof NACKHandler) ? (NACKHandler) object : null;
        rembHandler
                = (object instanceof REMBHandler) ? (REMBHandler) object : null;

        if (nackHandler == null && rembHandler == null)
        {
            enabled = false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RawPacket transform(RawPacket pkt)
    {
        return pkt;
    }

    /**
     * Parses the (compound) RTCP packet <tt>pkt</tt> and looks for recognized
     * packet types. Notifies the configured handlers.
     * @param pkt the input RTCP compound packet.
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
        if (!enabled)
        {
            return pkt;
        }

        RTCPCompoundPacket compound;
        try
        {
            compound = (RTCPCompoundPacket) parser.parse(
                    pkt.getBuffer(),
                    pkt.getOffset(),
                    pkt.getLength());
        }
        catch (BadFormatException e)
        {
            return pkt;
        }

        if (compound == null || compound.packets == null
                || compound.packets.length == 0)
        {
            return pkt;
        }

        List<RTCPPacket> outRtcps = new LinkedList<>();
        boolean removed = false;

        for (RTCPPacket rtcp : compound.packets)
        {
            if (rtcp == null)
            {
                continue;
            }

            if (rtcp instanceof NACKPacket)
            {
                if (nackHandler != null)
                {
                    nackHandler.handleNACK((NACKPacket) rtcp);
                }
            }
            else if (rembHandler != null
                    && rtcp.type == RTCPFBPacket.PSFB
                    && ((RTCPFBPacket) rtcp).fmt == RTCPREMBPacket.FMT)
            {
                RTCPREMBPacket remb = (RTCPREMBPacket) rtcp;
                rembHandler.handleREMB(remb.getBitrate());
            }

            if (dropNack && rtcp instanceof NACKPacket)
            {
                removed = true;
            }
            else
            {
                outRtcps.add(rtcp);
            }
        }

        if (removed)
        {
            if (outRtcps.size() > 0)
            {
                RTCPCompoundPacket outPacket
                        = new RTCPCompoundPacket(outRtcps.toArray(
                        new RTCPPacket[outRtcps.size()]));

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

    /**
     * Sets the flag which determines whether this transformer will drop NACK
     * packets or not.
     * @param drop
     */
    public void setDropNackPackets(boolean drop)
    {
        dropNack = drop;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return this;
    }
}
