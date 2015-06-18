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

package org.jitsi.videobridge.transform;

import net.sf.fmj.media.rtp.*;
import net.sf.fmj.media.rtp.util.*;
import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtcp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

/**
 * A <tt>TransformEngine</tt> implementation which parses RTCP packets and
 * transforms them using a transformer for <tt>RTCPCompoundPacket</tt>s.
 * This is similar to (and based on) <tt>libjitsi</tt>'s
 * <tt>RTCPTerminationTransformEngine</tt> but is not connected with a
 * <tt>MediaStream</tt>.
 * @author Boris Grozev
 */
public class RTCPTransformEngine
    extends SinglePacketTransformer
    implements TransformEngine, Transformer<RTCPCompoundPacket>
{
    /**
     * The <tt>Logger</tt> used by the <tt>RTCPTransformEngine</tt> class
     * and its instances to print debug information.
     */
    private static final Logger logger
            = Logger.getLogger(RTCPTransformEngine.class);

    /**
     * The chain of transformers that operate on <tt>RTCPCompoundPacket</tt>s
     * (already parsed).
     */
    private Transformer<RTCPCompoundPacket>[] chain;

    /**
     * The instance used to parse the input <tt>RawPacket</tt>s/bytes into
     * <tt>RTCPCompoundPacket</tt>s.
     */
    private final RTCPPacketParserEx parser = new RTCPPacketParserEx();

    /**
     * Initializes this transformer with the given chain of transformers.
     * @param chain
     */
    public RTCPTransformEngine(Transformer<RTCPCompoundPacket>[] chain)
    {
        this.chain = chain;
    }

    /**
     * Implements
     * {@link org.jitsi.service.neomedia.Transformer#transform(Object)}.
     *
     * Does not touch outgoing packets.
     */
    @Override
    public RTCPCompoundPacket transform(RTCPCompoundPacket rtcpCompoundPacket)
    {
        // Not implemented.
        return rtcpCompoundPacket;
    }

    /**
     * Implements
     * {@link org.jitsi.service.neomedia.Transformer#reverseTransform(Object)}.
     *
     * Transforms incoming RTCP packets through the configured transformer
     * chain.
     */
    @Override
    public RTCPCompoundPacket reverseTransform(
        RTCPCompoundPacket inPacket)
    {
        if (chain != null)
        {
            for (Transformer<RTCPCompoundPacket> transformer : chain)
            {
                if (transformer != null)
                    inPacket = transformer.reverseTransform(inPacket);
            }
        }

        return inPacket;
    }

    /**
     * Implements
     * {@link org.jitsi.service.neomedia.Transformer#close()}.
     */
    @Override
    public void close()
    {
    }

    /**
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.TransformEngine#getRTPTransformer()}.
     */
    @Override
    public PacketTransformer getRTPTransformer()
    {
        return null;
    }

    /**
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.TransformEngine#getRTCPTransformer()}.
     */
    @Override
    public PacketTransformer getRTCPTransformer()
    {
        return this;
    }

    /**
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.SinglePacketTransformer#transform(org.jitsi.impl.neomedia.RawPacket)}
     *
     * Does not touch outgoing packets.
     */
    @Override
    public RawPacket transform(RawPacket packet)
    {
        // Not implemented.
        return packet;
    }

    /**
     * Implements
     * {@link org.jitsi.impl.neomedia.transform.SinglePacketTransformer#reverseTransform(org.jitsi.impl.neomedia.RawPacket)}
     *
     * Parses the given packet as an RTCP packet and transforms the result
     * using the configured transformer chain. Returns the <tt>RawPacket</tt>
     * constructed from the transformed <tt>RTCPCompoundPacket</tt>.
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
        // Parse the RTCP packet.
        RTCPCompoundPacket inRTCPPacket;
        try
        {
            inRTCPPacket = (RTCPCompoundPacket) parser.parse(
                    pkt.getBuffer(),
                    pkt.getOffset(),
                    pkt.getLength());
        }
        catch (BadFormatException e)
        {
            // TODO(gp) decide what to do with malformed packets!
            logger.error("Could not parse RTCP packet.", e);
            return pkt;
        }

        // Transform the RTCP packet with the transform chain.
        RTCPCompoundPacket outRTCPPacket = reverseTransform(inRTCPPacket);

        if (outRTCPPacket == null
                || outRTCPPacket.packets == null
                || outRTCPPacket.packets.length == 0)
            return null;

        // Assemble the RTCP packet.
        int len = outRTCPPacket.calcLength();
        outRTCPPacket.assemble(len, false);

        pkt.setBuffer(outRTCPPacket.data);
        pkt.setLength(outRTCPPacket.data.length);
        pkt.setOffset(0);

        return pkt;
    }
}
