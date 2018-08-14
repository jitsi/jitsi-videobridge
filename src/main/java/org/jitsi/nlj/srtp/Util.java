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
package org.jitsi.nlj.srtp;

import org.bouncycastle.crypto.tls.*;
import org.jitsi.rtp.util.*;

/**
 * @author bbaldino
 */
public class Util
{
    public static class SrtpContextFactories {
        public SRTPContextFactory decryptFactory;
        public SRTPContextFactory encryptFactory;

        public SrtpContextFactories(SRTPContextFactory decryptFactory, SRTPContextFactory encryptFactory) {
            this.decryptFactory = decryptFactory;
            this.encryptFactory = encryptFactory;
        }
    }

    public static class ProtectionProfileInformation {
        public int cipherKeyLength;
        public int cipherSaltLength;
        public int cipher;
        public int authFunction;
        public int authKeyLength;
        public int rtcpAuthTagLength;
        public int rtpAuthTagLength;

        public ProtectionProfileInformation(
                int cipherKeyLength,
                int cipherSaltLength,
                int cipher,
                int authFunction,
                int authKeyLength,
                int rtcpAuthTagLength,
                int rtpAuthTagLength)
        {
            this.cipherKeyLength = cipherKeyLength;
            this.cipherSaltLength = cipherSaltLength;
            this.cipher = cipher;
            this.authFunction = authFunction;
            this.authKeyLength = authKeyLength;
            this.rtcpAuthTagLength = rtcpAuthTagLength;
            this.rtpAuthTagLength = rtpAuthTagLength;
        }

        @Override
        public String toString() {
            return "cipher key length: " + this.cipherKeyLength +
                    " cipher salt length: " + this.cipherSaltLength +
                    " cipher: " + this.cipher +
                    " auth function: " + this.authFunction +
                    " auth key length: " + this.authKeyLength +
                    " rtcp auth tag length: " + this.rtcpAuthTagLength +
                    " rtp auth tag length: " + this.rtpAuthTagLength;
        }
    }

    public static ProtectionProfileInformation getProtectionProfileInformation(int srtpProtectionProfile)
    {
        int cipherKeyLength;
        int cipherSaltLength;
        int cipher;
        int authFunction;
        int authKeyLength;
        int rtcpAuthTagLength;
        int rtpAuthTagLength;
        switch (srtpProtectionProfile)
        {
            case SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_32:
                System.out.println("BRIAN: using profile SRTP_AES128_CM_HMAC_SHA1_32");
                cipherKeyLength = 128 / 8;
                cipherSaltLength = 112 / 8;
                cipher = SRTPPolicy.AESCM_ENCRYPTION;
                authFunction = SRTPPolicy.HMACSHA1_AUTHENTICATION;
                authKeyLength = 160 / 8;
                rtcpAuthTagLength = 80 / 8;
                rtpAuthTagLength = 32 / 8;
                break;
            case SRTPProtectionProfile.SRTP_AES128_CM_HMAC_SHA1_80:
                System.out.println("BRIAN: using profile SRTP_AES128_CM_HMAC_SHA1_80");
                cipherKeyLength = 128 / 8;
                cipherSaltLength = 112 / 8;
                cipher = SRTPPolicy.AESCM_ENCRYPTION;
                authFunction = SRTPPolicy.HMACSHA1_AUTHENTICATION;
                authKeyLength = 160 / 8;
                rtcpAuthTagLength = rtpAuthTagLength = 80 / 8;
                break;
            case SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_32:
                System.out.println("BRIAN: using profile SRTP_NULL_HMAC_SHA1_32");
                cipherKeyLength = 0;
                cipherSaltLength = 0;
                cipher = SRTPPolicy.NULL_ENCRYPTION;
                authFunction = SRTPPolicy.HMACSHA1_AUTHENTICATION;
                authKeyLength = 160 / 8;
                rtcpAuthTagLength = 80 / 8;
                rtpAuthTagLength = 32 / 8;
                break;
            case SRTPProtectionProfile.SRTP_NULL_HMAC_SHA1_80:
                System.out.println("BRIAN: using profile SRTP_NULL_HMAC_SHA1_80");
                cipherKeyLength = 0;
                cipherSaltLength = 0;
                cipher = SRTPPolicy.NULL_ENCRYPTION;
                authFunction = SRTPPolicy.HMACSHA1_AUTHENTICATION;
                authKeyLength = 160 / 8;
                rtcpAuthTagLength = rtpAuthTagLength = 80 / 8;
                break;
            default:
                throw new IllegalArgumentException("negotiatedSrtpProtectionProfile");
        }

        return new ProtectionProfileInformation(
                cipherKeyLength,
                cipherSaltLength,
                cipher,
                authFunction,
                authKeyLength,
                rtcpAuthTagLength,
                rtpAuthTagLength);
    }
    /**
     * Initializes a new <tt>SRTPTransformer</tt> instance with a specific
     * (negotiated) <tt>SRTPProtectionProfile</tt> and the keying material
     * specified by a specific <tt>TlsContext</tt>.
     *
//     * @param negotiatedSrtpProtectionProfile the (negotiated)
     *                              <tt>SRTPProtectionProfile</tt> to initialize the new instance with
//     * @param tlsContext            the <tt>TlsContext</tt> which represents the keying
     *                              material
     * @return a new <tt>SRTPTransformer</tt> instance initialized with
     * <tt>srtpProtectionProfile</tt> and <tt>tlsContext</tt>
     */
    public static SrtpContextFactories createSrtpContextFactories(
            ProtectionProfileInformation protectionProfileInformation,
            byte[] keyingMaterial,
            boolean isClient)
    {
        System.out.println("Creating context factories with ppi: " + protectionProfileInformation.toString() +
                "\nkeying material: " + toHex(keyingMaterial) +
                "\nis client? " + isClient);
        byte[] client_write_SRTP_master_key = new byte[protectionProfileInformation.cipherKeyLength];
        byte[] server_write_SRTP_master_key = new byte[protectionProfileInformation.cipherKeyLength];
        byte[] client_write_SRTP_master_salt = new byte[protectionProfileInformation.cipherSaltLength];
        byte[] server_write_SRTP_master_salt = new byte[protectionProfileInformation.cipherSaltLength];
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
                protectionProfileInformation.cipher,
                protectionProfileInformation.cipherKeyLength,
                protectionProfileInformation.authFunction,
                protectionProfileInformation.authKeyLength,
                protectionProfileInformation.rtcpAuthTagLength,
                protectionProfileInformation.cipherSaltLength);
        SRTPPolicy srtpPolicy
                = new SRTPPolicy(
                protectionProfileInformation.cipher,
                protectionProfileInformation.cipherKeyLength,
                protectionProfileInformation.authFunction,
                protectionProfileInformation.authKeyLength,
                protectionProfileInformation.rtpAuthTagLength,
                protectionProfileInformation.cipherSaltLength);
        System.out.println("BRIAN: creating client srtp factory with values: " +
                "\nsender? " + isClient +
                "\nclient write srtp master key: " + toHex(client_write_SRTP_master_key) +
                "\nclient write srtp master salt: " + toHex(client_write_SRTP_master_salt) +
                "\nsrtp policy: " + srtpPolicy.toString() +
                "\nsrtcp policy: " + srtcpPolicy.toString());
        SRTPContextFactory clientSRTPContextFactory
                = new SRTPContextFactory(
                /* sender */ isClient,
                client_write_SRTP_master_key,
                client_write_SRTP_master_salt,
                srtpPolicy,
                srtcpPolicy);
        System.out.println("BRIAN: creating server srtp factory with values: " +
                "\nsender? " + !isClient +
                "\nserver write srtp master key: " + toHex(server_write_SRTP_master_key) +
                "\nserver write srtp master salt: " + toHex(server_write_SRTP_master_salt) +
                "\nsrtp policy: " + srtpPolicy.toString() +
                "\nsrtcp policy: " + srtcpPolicy.toString());
        SRTPContextFactory serverSRTPContextFactory
                = new SRTPContextFactory(
                /* sender */ !isClient,
                server_write_SRTP_master_key,
                server_write_SRTP_master_salt,
                srtpPolicy,
                srtcpPolicy);
        SRTPContextFactory encryptFactory;
        SRTPContextFactory decryptFactory;

        if (isClient)
        {
            encryptFactory = clientSRTPContextFactory;
            decryptFactory = serverSRTPContextFactory;
        } else
        {
            encryptFactory = serverSRTPContextFactory;
            decryptFactory = clientSRTPContextFactory;
        }

        return new SrtpContextFactories(decryptFactory, encryptFactory);
    }

    private static final char[] HEX_ENCODE_TABLE
            = {
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    public static String toHex(byte[] fingerprint)
    {
//        if (fingerprint.length == 0)
//            throw new IllegalArgumentException("fingerprint");
//
//        char[] chars = new char[3 * fingerprint.length - 1];
//
//        for (int f = 0, fLast = fingerprint.length - 1, c = 0;
//             f <= fLast;
//             f++)
//        {
//            int b = fingerprint[f] & 0xff;
//
//            chars[c++] = HEX_ENCODE_TABLE[b >>> 4];
//            chars[c++] = HEX_ENCODE_TABLE[b & 0x0f];
//        }
//        return new String(chars);
        return toHex(fingerprint, 0, fingerprint.length);
    }


    public static String toHex(BufferView bufferView)
    {
        return toHex(bufferView.getArray(), bufferView.getOffset(), bufferView.getLength());
    }

    public static String toHex(byte[] data, int off, int length)
    {
        try
        {
            char[] chars = new char[3 * length - 1];

            for (int f = off, fLast = off + length - 1, c = 0;
                 f <= fLast;
                 f++)
            {
                int b = data[f] & 0xff;

                chars[c++] = HEX_ENCODE_TABLE[b >>> 4];
                chars[c++] = HEX_ENCODE_TABLE[b & 0x0f];
            }
            return new String(chars);
        } catch (Exception e) {
            System.out.println("BRIAN: exception converting to hex: " + e.toString());
            return e.toString();
        }

    }
}
