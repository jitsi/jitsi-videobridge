/*
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.nlj.srtp_og;
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

import org.bouncycastle.crypto.tls.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;

import java.util.*;

import static org.jitsi.nlj.srtp_og.SRTPCryptoContext.toHex;
import static org.jitsi.nlj.srtp_og.SRTPCryptoContext.toHexArrayDef;


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
    private final Map<Integer,SRTPCryptoContext> contexts;

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
        this.contexts = new HashMap<Integer,SRTPCryptoContext>();
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
                            engine.hashCode() + " with ssrc " + ssrc + " and index " +
                            deriveSrtpKeysIndex);
                    for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                        System.out.println(ste);
                    }
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
     * @param pkt the transformed packet to be restored
     * @return the restored packet
     */
    @Override
    public RawPacket reverseTransform(RawPacket pkt)
    {
//        System.out.println("BRIAN: packet " + pkt.getSSRCAsLong() + " " +
//                pkt.getSequenceNumber() + " (length: " + pkt.getLength() + " before decrypt: " +
//                toHexArrayDef(pkt.getBuffer(), pkt.getOffset(), pkt.getLength()) +
//                "\n will get context from factory " + reverseFactory.hashCode());
        // only accept RTP version 2 (SNOM phones send weird packages when on
        // hold, ignore them with this check (RTP Version must be equal to 2)
        if((pkt.readByte(0) & 0xC0) != 0x80)
        {
            System.out.println("SRTPTransformer#reverseTransform received invalid packet");
            return null;
        }

        SRTPCryptoContext context
                = getContext(
                pkt.getSSRC(),
                reverseFactory,
                pkt.getSequenceNumber());

        RawPacket res =
                ((context != null) && context.reverseTransformPacket(pkt))
                        ? pkt
                        : null;
        if (res == null) {
            System.out.println("BRIAN: decryption of packet " + pkt.getSSRCAsLong() + " " + pkt.getSequenceNumber() +
                    " failed");
            for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
                System.out.println(ste);
            }
        }
//        System.out.println("BRIAN: packet " + pkt.getSSRCAsLong() + " " +
//                pkt.getSequenceNumber() + " (length: " + pkt.getLength() + " after decrypt: " +
//                toHexArrayDef(pkt.getBuffer(), pkt.getOffset(), pkt.getLength()));
        return res;
    }

    /**
     * Transforms a specific packet.
     *
     * @param pkt the packet to be transformed
     * @return the transformed packet
     */
    @Override
    public RawPacket transform(RawPacket pkt)
    {
//        System.out.println("BRIAN: packet " + pkt.getSSRCAsLong() + " " +
//                pkt.getSequenceNumber() + " (length: " + pkt.getLength() + " before encrypt: " +
//                toHexArrayDef(pkt.getBuffer(), pkt.getOffset(), pkt.getLength()) +
//                "\n will get context from factory " + reverseFactory.hashCode());
        SRTPCryptoContext context
                = getContext(pkt.getSSRC(), forwardFactory, 0);

        if (context == null)
            return null;

        RawPacket res = context.transformPacket(pkt) ? pkt : null;
//        System.out.println("BRIAN: packet " + pkt.getSSRCAsLong() + " " +
//                pkt.getSequenceNumber() + " (length: " + pkt.getLength() + " after encrypt: " +
//                toHexArrayDef(pkt.getBuffer(), pkt.getOffset(), pkt.getLength()));
        return res;
    }


    // Copied from DtlsPacketTransformer
    // Now DEPRECATED.  use SrtpUtil#initializeTransformer
    public static SinglePacketTransformer initializeSRTPTransformer(
            int srtpProtectionProfile,
            TlsContext tlsContext,
            boolean rtcp)
    {
        int cipher_key_length;
        int cipher_salt_length;
        int cipher;
        int auth_function;
        int auth_key_length;
        int RTCP_auth_tag_length, RTP_auth_tag_length;

        System.out.println("BRIAN: srtp protection profile: " + srtpProtectionProfile);
        switch (srtpProtectionProfile)
        {
            case SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32:
                cipher_key_length = 128 / 8;
                cipher_salt_length = 112 / 8;
                cipher = SRTPPolicy.AESCM_ENCRYPTION;
                auth_function = SRTPPolicy.HMACSHA1_AUTHENTICATION;
                auth_key_length = 160 / 8;
                RTCP_auth_tag_length = 80 / 8;
                RTP_auth_tag_length = 32 / 8;
                break;
            case SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80:
                cipher_key_length = 128 / 8;
                cipher_salt_length = 112 / 8;
                cipher = SRTPPolicy.AESCM_ENCRYPTION;
                auth_function = SRTPPolicy.HMACSHA1_AUTHENTICATION;
                auth_key_length = 160 / 8;
                RTCP_auth_tag_length = RTP_auth_tag_length = 80 / 8;
                break;
            case SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_32:
                cipher_key_length = 0;
                cipher_salt_length = 0;
                cipher = SRTPPolicy.NULL_ENCRYPTION;
                auth_function = SRTPPolicy.HMACSHA1_AUTHENTICATION;
                auth_key_length = 160 / 8;
                RTCP_auth_tag_length = 80 / 8;
                RTP_auth_tag_length = 32 / 8;
                break;
            case SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_80:
                cipher_key_length = 0;
                cipher_salt_length = 0;
                cipher = SRTPPolicy.NULL_ENCRYPTION;
                auth_function = SRTPPolicy.HMACSHA1_AUTHENTICATION;
                auth_key_length = 160 / 8;
                RTCP_auth_tag_length = RTP_auth_tag_length = 80 / 8;
                break;
            default:
                throw new IllegalArgumentException("srtpProtectionProfile");
        }

        System.out.println("BRIAN: master secret: " + tlsContext.getSecurityParameters().getMasterSecret());
        byte[] keyingMaterial
                = tlsContext.exportKeyingMaterial(
                ExporterLabel.dtls_srtp,
                null,
                2 * (cipher_key_length + cipher_salt_length));
        System.out.println("BRIAN: creating srtp factories. \nkeying material: " + toHexArrayDef(keyingMaterial, 0, keyingMaterial.length));
        byte[] client_write_SRTP_master_key = new byte[cipher_key_length];
        byte[] server_write_SRTP_master_key = new byte[cipher_key_length];
        byte[] client_write_SRTP_master_salt = new byte[cipher_salt_length];
        byte[] server_write_SRTP_master_salt = new byte[cipher_salt_length];
        byte[][] keyingMaterialValues
                = {
                client_write_SRTP_master_key,
                server_write_SRTP_master_key,
                client_write_SRTP_master_salt,
                server_write_SRTP_master_salt
        };

        for (int i = 0, keyingMaterialOffset = 0;
             i < keyingMaterialValues.length;
             i++)
        {
            byte[] keyingMaterialValue = keyingMaterialValues[i];

            System.arraycopy(
                    keyingMaterial, keyingMaterialOffset,
                    keyingMaterialValue, 0,
                    keyingMaterialValue.length);
            keyingMaterialOffset += keyingMaterialValue.length;
        }

        SRTPPolicy srtcpPolicy
                = new SRTPPolicy(
                cipher,
                cipher_key_length,
                auth_function,
                auth_key_length,
                RTCP_auth_tag_length,
                cipher_salt_length);
        SRTPPolicy srtpPolicy
                = new SRTPPolicy(
                cipher,
                cipher_key_length,
                auth_function,
                auth_key_length,
                RTP_auth_tag_length,
                cipher_salt_length);
        SRTPContextFactory clientSRTPContextFactory
                = new SRTPContextFactory(
                /* sender */ tlsContext instanceof TlsClientContext,
                client_write_SRTP_master_key,
                client_write_SRTP_master_salt,
                srtpPolicy,
                srtcpPolicy);
        System.out.println("BRIAN: created client srtp factory " + clientSRTPContextFactory.hashCode() + " with values: " +
                "\nsender? " + (tlsContext instanceof TlsClientContext) +
                "\nclient write srtp master key: " + toHex(client_write_SRTP_master_key) +
                "\nclient write srtp master salt: " + toHex(client_write_SRTP_master_salt) +
                "\nsrtp policy: " + srtpPolicy.toString() +
                "\nsrtcp policy: " + srtcpPolicy.toString());
        SRTPContextFactory serverSRTPContextFactory
                = new SRTPContextFactory(
                /* sender */ tlsContext instanceof TlsServerContext,
                server_write_SRTP_master_key,
                server_write_SRTP_master_salt,
                srtpPolicy,
                srtcpPolicy);
        System.out.println("BRIAN: created server srtp factory " + serverSRTPContextFactory.hashCode() + " with values: " +
                "\nsender? " + (tlsContext instanceof TlsServerContext) +
                "\nserver write srtp master key: " + toHex(server_write_SRTP_master_key) +
                "\nserver write srtp master salt: " + toHex(server_write_SRTP_master_salt) +
                "\nsrtp policy: " + srtpPolicy.toString() +
                "\nsrtcp policy: " + srtcpPolicy.toString());
        SRTPContextFactory forwardSRTPContextFactory;
        SRTPContextFactory reverseSRTPContextFactory;

        if (tlsContext instanceof TlsClientContext)
        {
            System.out.println("BRIAN: this was tls client, reverse factory will use server");
            forwardSRTPContextFactory = clientSRTPContextFactory;
            reverseSRTPContextFactory = serverSRTPContextFactory;
        }
        else if (tlsContext instanceof TlsServerContext)
        {
            System.out.println("BRIAN: this was tls server, reverse factory will use client");
            forwardSRTPContextFactory = serverSRTPContextFactory;
            reverseSRTPContextFactory = clientSRTPContextFactory;
        }
        else
        {
            throw new IllegalArgumentException("tlsContext");
        }

        SinglePacketTransformer srtpTransformer;

        if (rtcp)
        {
            srtpTransformer
                    = new SRTCPTransformer(
                    forwardSRTPContextFactory,
                    reverseSRTPContextFactory);
        }
        else
        {
            srtpTransformer
                    = new SRTPTransformer(
                    forwardSRTPContextFactory,
                    reverseSRTPContextFactory);
        }
        return srtpTransformer;
    }
}



