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

/**
 * @author bb
 */
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

import java.util.*;

import org.bouncycastle.crypto.*;
import org.bouncycastle.crypto.params.*;

/**
 * SRTPCipherF8 implements SRTP F8 Mode Encryption for 128 bits block cipher.
 * F8 Mode AES Encryption algorithm is defined in RFC3711, section 4.1.2.
 *
 * Other than Null Cipher, RFC3711 defined two two encryption algorithms:
 * Counter Mode AES Encryption and F8 Mode AES encryption. Both encryption
 * algorithms are capable to encrypt / decrypt arbitrary length data, and the
 * size of packet data is not required to be a multiple of the cipher block
 * size (128bit). So, no padding is needed.
 *
 * Please note: these two encryption algorithms are specially defined by SRTP.
 * They are not common AES encryption modes, so you will not be able to find a
 * replacement implementation in common cryptographic libraries.
 *
 * As defined by RFC3711: F8 mode encryption is optional.
 *
 *                        mandatory to impl     optional      default
 * -------------------------------------------------------------------------
 *   encryption           AES-CM, NULL          AES-f8        AES-CM
 *   message integrity    HMAC-SHA1                -          HMAC-SHA1
 *   key derivation       (PRF) AES-CM             -          AES-CM
 *
 *
 * @author Bing SU (nova.su@gmail.com)
 * @author Werner Dittmann <werner.dittmann@t-online.de>
 */
public class SRTPCipherF8
{
    /**
     * block size, just a short name.
     */
    private final static int BLKLEN = 16;

    /**
     * F8 mode encryption context, see RFC3711 section 4.1.2 for detailed
     * description.
     */
    static class F8Context
    {
        public byte[] S;
        public byte[] ivAccent;
        long J;
    }

    /**
     * Encryption key
     * (k_e)
     */
    private byte[] encKey;

    /**
     * Masked Encryption key (F8 mode specific)
     * (k_e XOR (k_s || 0x555..5))
     */
    private byte[] maskedKey;

    /**
     * A 128 bits block cipher (AES or TwoFish)
     */
    private BlockCipher cipher;

    public SRTPCipherF8(BlockCipher cipher)
    {
        this.cipher = cipher;
    }

    /**
     * @param k_e encryption key
     * @param k_s salt key
     */
    public void init(byte[] k_e, byte[] k_s)
    {
        if (k_e.length != BLKLEN)
            throw new IllegalArgumentException("k_e.length != BLKLEN");
        if (k_s.length > k_e.length)
            throw new IllegalArgumentException("k_s.length > k_e.length");

        encKey = Arrays.copyOf(k_e, k_e.length);

        /*
         * XOR the original key with the salt||0x55 to get
         * the special key maskedKey.
         */
        maskedKey = Arrays.copyOf(k_e, k_e.length);
        int i = 0;
        for (; i < k_s.length; ++i)
            maskedKey[i] ^= k_s[i];
        for (; i < maskedKey.length; ++i)
            maskedKey[i] ^= 0x55;
    }

    public void process(byte[] data, int off, int len, byte[] iv)
    {
        if (iv.length != BLKLEN)
            throw new IllegalArgumentException("iv.length != BLKLEN");
        if (off < 0)
            throw new IllegalArgumentException("off < 0");
        if (len < 0)
            throw new IllegalArgumentException("len < 0");
        if (off + len > data.length)
            throw new IllegalArgumentException("off + len > data.length");
        /*
         * RFC 3711 says we should not encrypt more than 2^32 blocks which is
         * way more than java array max size, so no checks needed here
         */

        F8Context f8ctx = new F8Context();

        /*
         * Get memory for the derived IV (IV')
         */
        f8ctx.ivAccent = new byte[BLKLEN];

        /*
         * Encrypt the original IV to produce IV'.
         */
        cipher.init(true, new KeyParameter(maskedKey));
        cipher.processBlock(iv, 0, f8ctx.ivAccent, 0);

        /*
         * re-init cipher with the "normal" key
         */
        cipher.init(true, new KeyParameter(encKey));

        f8ctx.J = 0; // initialize the counter
        f8ctx.S = new byte[BLKLEN]; // get the key stream buffer
        Arrays.fill(f8ctx.S, (byte) 0);

        int inLen = len;

        while (inLen >= BLKLEN)
        {
            processBlock(f8ctx, data, off, BLKLEN);
            inLen -= BLKLEN;
            off += BLKLEN;
        }

        if (inLen > 0)
        {
            processBlock(f8ctx, data, off, inLen);
        }
    }

    /**
     * Encrypt / Decrypt a block using F8 Mode AES algorithm, read len bytes
     * data from in at inOff and write the output into out at outOff
     *
     * @param f8ctx
     *            F8 encryption context
     * @param inOut
     *            byte array holding the data to be processed
     * @param off
     *            start offset of the data to be processed inside inOut array
     * @param len
     *            length of the data to be processed inside inOut array from off
     */
    private void processBlock(F8Context f8ctx, byte[] inOut, int off, int len)
    {
        /*
         * XOR the previous key stream with IV'
         * ( S(-1) xor IV' )
         */
        for (int i = 0; i < BLKLEN; i++)
            f8ctx.S[i] ^= f8ctx.ivAccent[i];

        /*
         * Now XOR (S(n-1) xor IV') with the current counter, then increment
         * the counter
         */
        f8ctx.S[12] ^= f8ctx.J >> 24;
        f8ctx.S[13] ^= f8ctx.J >> 16;
        f8ctx.S[14] ^= f8ctx.J >> 8;
        f8ctx.S[15] ^= f8ctx.J;
        f8ctx.J++;

        /*
         * Now compute the new key stream using AES encrypt
         */
        cipher.processBlock(f8ctx.S, 0, f8ctx.S, 0);

        /*
         * As the last step XOR the plain text with the key stream to produce
         * the cipher text.
         */
        for (int i = 0; i < len; i++)
            inOut[off + i] ^= f8ctx.S[i];
    }
}

