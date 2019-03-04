/*
 * Copyright @ 2018 - present 8x8, Inc.
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
package org.jitsi_modified.impl.neomedia.transform.srtp;

//import org.jitsi.rtp.*;
import org.jitsi.rtp.Packet;
import org.jitsi.rtp.rtp.*;
import org.jitsi.rtp.srtp.*;
import org.jitsi_modified.impl.neomedia.transform.*;

import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;

/**
 * SRTPTransformer implements PacketTransformer and provides implementations
 * for RTP packet to SRTP packet transformation and SRTP packet to RTP packet
 * transformation logic.
 *
 * It will first find the corresponding SRTPCryptoContext for each packet based
 * on their SSRC and then invoke the context object to perform the
 * transformation and reverse transformation operation.
 *
 * @author Bing SU (nova.su@gmail.com)
 */
public class SRTPTransformer
    extends SinglePacketTransformer
{
    SRTPContextFactory forwardFactory;
    SRTPContextFactory reverseFactory;

    /**
     * All the known SSRC's corresponding SRTPCryptoContexts
     */
    private final Map<Integer, SRTPCryptoContext> contexts;

    /**
     * Initializes a new <tt>SRTPTransformer</tt> instance.
     *
     * @param factory the context factory to be used by the new
     * instance for both directions.
     */
    public SRTPTransformer(SRTPContextFactory factory)
    {
        this(factory, factory);
    }

    /**
     * Constructs a SRTPTransformer object.
     *
     * @param forwardFactory The associated context factory for forward
     *            transformations.
     * @param reverseFactory The associated context factory for reverse
     *            transformations.
     */
    public SRTPTransformer(
            SRTPContextFactory forwardFactory,
            SRTPContextFactory reverseFactory)
    {
        this.forwardFactory = forwardFactory;
        this.reverseFactory = reverseFactory;
        this.contexts = new HashMap<Integer, SRTPCryptoContext>();
    }

    /**
     * Sets a new key factory when key material has changed.
     *
     * @param factory The associated context factory for transformations.
     * @param forward <tt>true</tt> if the supplied factory is for forward
     *            transformations, <tt>false</tt> for the reverse transformation
     *            factory.
     */
    public void setContextFactory(SRTPContextFactory factory, boolean forward)
    {
        synchronized (contexts)
        {
            if (forward)
            {
                if (this.forwardFactory != null
                    && this.forwardFactory != factory)
                {
                    this.forwardFactory.close();
                }

                this.forwardFactory = factory;
            }
            else
            {
                if (this.reverseFactory != null &&
                    this.reverseFactory != factory)
                {
                    this.reverseFactory.close();
                }

                this.reverseFactory = factory;
            }
        }
    }

    /**
     * Closes this <tt>SRTPTransformer</tt> and the underlying transform
     * engines.It closes all stored crypto contexts. It deletes key data and
     * forces a cleanup of the crypto contexts.
     */
    public void close()
    {
        synchronized (contexts)
        {
            forwardFactory.close();
            if (reverseFactory != forwardFactory)
                reverseFactory.close();

            for (Iterator<SRTPCryptoContext> i = contexts.values().iterator();
                 i.hasNext();)
            {
                SRTPCryptoContext context = i.next();

                i.remove();
                if (context != null)
                    context.close();
            }
        }
    }

    private SRTPCryptoContext getContext(
            int ssrc,
            SRTPContextFactory engine,
            int deriveSrtpKeysIndex)
    {
        SRTPCryptoContext context;

        synchronized (contexts)
        {
            context = contexts.get(ssrc);
            if (context == null)
            {
                context = engine.getDefaultContext();
                if (context != null)
                {
                    System.out.println("BRIAN: deriving new context from factory " +
                            engine.hashCode() + " with ssrc " + (ssrc & 0xFFFF_FFFFL) + " and index " +
                            deriveSrtpKeysIndex);
                    context = context.deriveContext(ssrc, 0, 0);
                    context.deriveSrtpKeys(deriveSrtpKeysIndex);
                    contexts.put(ssrc, context);
                }
            }
        }

        return context;
    }

    /**
     * Reverse-transforms a specific packet (i.e. transforms a transformed
     * packet back).
     *
     * @param packet the transformed packet to be restored
     * @return the restored packet
     */
    @Override
    public Packet reverseTransform(Packet packet)
    {
        SrtpPacket srtpPacket = (SrtpPacket)packet;
        SRTPCryptoContext context
            = getContext(
                    (int)srtpPacket.getHeader().getSsrc(),
                    reverseFactory,
                    srtpPacket.getHeader().getSequenceNumber());

        return context == null ? null : context.reverseTransformPacket(srtpPacket);
    }

    /**
     * Transforms a specific packet.
     *
     * @param packet the packet to be transformed
     * @return the transformed packet
     */
    @Override
    public Packet transform(Packet packet)
    {
        RtpPacket rtpPacket = (RtpPacket)packet;
        SRTPCryptoContext context
            = getContext((int)rtpPacket.getHeader().getSsrc(), forwardFactory, 0);

        return context == null ? null : context.transformPacket(rtpPacket);
    }
}
