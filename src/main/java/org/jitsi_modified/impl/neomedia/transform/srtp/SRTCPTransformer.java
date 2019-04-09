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

import org.jitsi.rtp.*;
import org.jitsi.rtp.ByteArrayBuffer;
import org.jitsi.rtp.rtcp.*;
import org.jitsi_modified.impl.neomedia.transform.*;

import java.util.*;

/**
 * SRTCPTransformer implements PacketTransformer.
 * It encapsulate the encryption / decryption logic for SRTCP packets
 *
 * @author Bing SU (nova.su@gmail.com)
 * @author Werner Dittmann &lt;Werner.Dittmann@t-online.de>
 */
public class SRTCPTransformer
    extends SinglePacketTransformer
{
    private SRTPContextFactory forwardFactory;
    private SRTPContextFactory reverseFactory;

    /**
     * All the known SSRC's corresponding SRTCPCryptoContexts
     */
    private final Map<Integer, SRTCPCryptoContext> contexts;

    /**
     * Constructs an <tt>SRTCPTransformer</tt>, sharing its
     * <tt>SRTPContextFactory</tt> instances with a given
     * <tt>SRTPTransformer</tt>.
     *
     * @param srtpTransformer the <tt>SRTPTransformer</tt> with which this
     * <tt>SRTCPTransformer</tt> will share its <tt>SRTPContextFactory</tt>
     * instances.
     */
    public SRTCPTransformer(SRTPTransformer srtpTransformer)
    {
        this(srtpTransformer.forwardFactory,
             srtpTransformer.reverseFactory);
    }

    /**
     * Constructs a SRTCPTransformer object.
     *
     * @param factory The associated context factory for both
     *            transform directions.
     */
    public SRTCPTransformer(SRTPContextFactory factory)
    {
        this(factory, factory);
    }

    /**
     * Constructs a SRTCPTransformer object.
     *
     * @param forwardFactory The associated context factory for forward
     *            transformations.
     * @param reverseFactory The associated context factory for reverse
     *            transformations.
     */
    public SRTCPTransformer(
            SRTPContextFactory forwardFactory,
            SRTPContextFactory reverseFactory)
    {
        this.forwardFactory = forwardFactory;
        this.reverseFactory = reverseFactory;
        this.contexts = new HashMap<Integer, SRTCPCryptoContext>();
    }

    /**
     * Sets a new key factory when key material has changed.
     *
     * @param factory The associated context factory for transformations.
     * @param forward <tt>true</tt> if the supplied factory is for forward
     *            transformations, <tt>false</tt> for the reverse transformation
     *            factory.
     */
    public void updateFactory(SRTPContextFactory factory, boolean forward)
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
     * Closes this <tt>SRTCPTransformer</tt> and the underlying transform
     * engine. It closes all stored crypto contexts. It deletes key data and
     * forces a cleanup of the crypto contexts.
     */
    public void close()
    {
        synchronized (contexts)
        {
            forwardFactory.close();
            if (reverseFactory != forwardFactory)
                reverseFactory.close();

            for (Iterator<SRTCPCryptoContext> i = contexts.values().iterator();
                 i.hasNext();)
            {
                SRTCPCryptoContext context = i.next();

                i.remove();
                if (context != null)
                    context.close();
            }
        }
    }

    private SRTCPCryptoContext getContext(
            ByteArrayBuffer pkt,
            SRTPContextFactory engine)
    {
        int ssrc = (int) RtcpHeader.Companion.getSenderSsrc(pkt.getBuffer(), pkt.getOffset());
        SRTCPCryptoContext context = null;

        synchronized (contexts)
        {
            context = contexts.get(ssrc);
            if (context == null && engine != null)
            {
                context = engine.getDefaultContextControl();
                if (context != null)
                {
                    context = context.deriveContext(ssrc);
                    context.deriveSrtcpKeys();
                    contexts.put(ssrc, context);
                }
            }
        }

        return context;
    }

    /**
     * Decrypts a SRTCP packet
     *
     * @param pkt encrypted SRTCP packet to be decrypted
     * @return decrypted SRTCP packet
     */
    @Override
    public Packet reverseTransform(Packet pkt)
    {
        SRTCPCryptoContext context = getContext(pkt, reverseFactory);

        return
            ((context != null) && context.reverseTransformPacket(pkt))
                ? pkt
                : null;
    }

    /**
     * Encrypts a SRTCP packet
     *
     * @param pkt plain SRTCP packet to be encrypted
     * @return encrypted SRTCP packet
     */
    @Override
    public Packet transform(Packet pkt)
    {
        SRTCPCryptoContext context = getContext(pkt, forwardFactory);

        if(context != null)
        {
            context.transformPacket(pkt);
            // We convert the encrypted RTCP packet to an UnparsedPacket because
            // we don't want any of the RTCP fields trying to parse the data
            // (since it's now encrypted)
            // TODO: better way we can do this?  it's not typically a problem
            // in the pipeline's usage, but it's a bit of a landmine since by
            // accessing the packet it can try and parse the fields.
            return new UnparsedPacket(pkt.getBuffer(), pkt.getOffset(), pkt.getLength());
        }
        else
        {
            // The packet cannot be encrypted. Thus, do not send it.
            return null;
        }
    }
}
