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
 *
 * Some of the code in this class is derived from ccRtp's SRTP implementation,
 * which has the following copyright notice:
 *
 * Copyright (C) 2004-2006 the Minisip Team
 *
 ** This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307 USA
*/
//package org.jitsi.impl.neomedia.transform.srtp;
//
//import java.util.*;
//
//import org.jitsi.impl.neomedia.transform.*;
//import org.jitsi.service.neomedia.*;
//
///**
// * SRTPTransformer implements PacketTransformer and provides implementations
// * for RTP packet to SRTP packet transformation and SRTP packet to RTP packet
// * transformation logic.
// *
// * It will first find the corresponding SRTPCryptoContext for each packet based
// * on their SSRC and then invoke the context object to perform the
// * transformation and reverse transformation operation.
// *
// * @author Bing SU (nova.su@gmail.com)
// */
//public class SRTPTransformer
//    extends SinglePacketTransformer
//{
//    SRTPContextFactory forwardFactory;
//    SRTPContextFactory reverseFactory;
//
//    /**
//     * All the known SSRC's corresponding SRTPCryptoContexts
//     */
//    private final Map<Integer,SRTPCryptoContext> contexts;
//
//    /**
//     * Initializes a new <tt>SRTPTransformer</tt> instance.
//     *
//     * @param factory the context factory to be used by the new
//     * instance for both directions.
//     */
//    public SRTPTransformer(SRTPContextFactory factory)
//    {
//        this(factory, factory);
//    }
//
//    /**
//     * Constructs a SRTPTransformer object.
//     *
//     * @param forwardFactory The associated context factory for forward
//     *            transformations.
//     * @param reverseFactory The associated context factory for reverse
//     *            transformations.
//     */
//    public SRTPTransformer(
//            SRTPContextFactory forwardFactory,
//            SRTPContextFactory reverseFactory)
//    {
//        this.forwardFactory = forwardFactory;
//        this.reverseFactory = reverseFactory;
//        this.contexts = new HashMap<Integer,SRTPCryptoContext>();
//    }
//
//    /**
//     * Sets a new key factory when key material has changed.
//     *
//     * @param factory The associated context factory for transformations.
//     * @param forward <tt>true</tt> if the supplied factory is for forward
//     *            transformations, <tt>false</tt> for the reverse transformation
//     *            factory.
//     */
//    public void setContextFactory(SRTPContextFactory factory, boolean forward)
//    {
//        synchronized (contexts)
//        {
//            if (forward)
//            {
//                if (this.forwardFactory != null
//                    && this.forwardFactory != factory)
//                {
//                    this.forwardFactory.close();
//                }
//
//                this.forwardFactory = factory;
//            }
//            else
//            {
//                if (this.reverseFactory != null &&
//                    this.reverseFactory != factory)
//                {
//                    this.reverseFactory.close();
//                }
//
//                this.reverseFactory = factory;
//            }
//        }
//    }
//
//    /**
//     * Closes this <tt>SRTPTransformer</tt> and the underlying transform
//     * engines.It closes all stored crypto contexts. It deletes key data and
//     * forces a cleanup of the crypto contexts.
//     */
//    public void close()
//    {
//        synchronized (contexts)
//        {
//            forwardFactory.close();
//            if (reverseFactory != forwardFactory)
//                reverseFactory.close();
//
//            for (Iterator<SRTPCryptoContext> i = contexts.values().iterator();
//                    i.hasNext();)
//            {
//                SRTPCryptoContext context = i.next();
//
//                i.remove();
//                if (context != null)
//                    context.close();
//            }
//        }
//    }
//
//    private SRTPCryptoContext getContext(
//            int ssrc,
//            SRTPContextFactory engine,
//            int deriveSrtpKeysIndex)
//    {
//        SRTPCryptoContext context;
//
//        synchronized (contexts)
//        {
//            context = contexts.get(ssrc);
//            if (context == null)
//            {
//                context = engine.getDefaultContext();
//                if (context != null)
//                {
//                    context = context.deriveContext(ssrc, 0, 0);
//                    context.deriveSrtpKeys(deriveSrtpKeysIndex);
//                    contexts.put(ssrc, context);
//                }
//            }
//        }
//
//        return context;
//    }
//
//    /**
//     * Reverse-transforms a specific packet (i.e. transforms a transformed
//     * packet back).
//     *
//     * @param pkt the transformed packet to be restored
//     * @return the restored packet
//     */
//    @Override
//    public RawPacket reverseTransform(RawPacket pkt)
//    {
//        // only accept RTP version 2 (SNOM phones send weird packages when on
//        // hold, ignore them with this check (RTP Version must be equal to 2)
//        if((pkt.readByte(0) & 0xC0) != 0x80)
//            return null;
//
//        SRTPCryptoContext context
//            = getContext(
//                    pkt.getSSRC(),
//                    reverseFactory,
//                    pkt.getSequenceNumber());
//
//        return
//            ((context != null) && context.reverseTransformPacket(pkt))
//                ? pkt
//                : null;
//    }
//
//    /**
//     * Transforms a specific packet.
//     *
//     * @param pkt the packet to be transformed
//     * @return the transformed packet
//     */
//    @Override
//    public RawPacket transform(RawPacket pkt)
//    {
//        SRTPCryptoContext context
//            = getContext(pkt.getSSRC(), forwardFactory, 0);
//
//        if (context == null)
//            return null;
//        return context.transformPacket(pkt) ? pkt : null;
//    }
//}
